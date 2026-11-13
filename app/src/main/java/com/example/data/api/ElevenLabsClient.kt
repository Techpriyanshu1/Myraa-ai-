package com.example.data.api

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.core.NetworkRetryHelper
import com.example.core.SharedHttpClient
import com.example.data.pref.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

sealed class VoiceTestResult {
    data class Success(val message: String, val audioBytes: Int) : VoiceTestResult()
    data class Failure(val errorMessage: String, val isConfigError: Boolean = false) : VoiceTestResult()
}

/**
 * Pure AI Neural Voice Client (NO Android native TextToSpeech).
 * - Uses ElevenLabs Neural Voice when an ElevenLabs key is configured.
 * - Uses Gemini Neural Voice (Mahi / Aoede) via shared HTTP/2 connection pool + zero-disk-I/O AudioTrack streaming
 *   + parallel sentence pipelining + phrase caching for near-instant playback latency.
 */
class ElevenLabsClient(private val context: Context) {

    private val client = SharedHttpClient.instance
    private val securePrefs by lazy { SecurePreferences(context.applicationContext) }
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeMediaPlayer: MediaPlayer? = null
    private var activeAudioTrack: AudioTrack? = null
    private var activeSpeechJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var speechGenerationId: Long = 0L

    private data class CachedPcmAudio(val pcmBytes: ByteArray, val sampleRate: Int)

    companion object {
        const val DEFAULT_MAHI_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
        const val MODEL_ELEVEN_MULTILINGUAL_V2 = "eleven_multilingual_v2"
        const val MODEL_ELEVEN_FLASH_V2_5 = "eleven_flash_v2_5"

        private const val GEMINI_VOICE_MAHI = "Aoede"
        private val DEFAULT_GEMINI_TTS_MODELS = listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts"
        )
        private val discoveredTtsModelsCache = ConcurrentHashMap<String, List<String>>()
        private val verifiedTtsModelByKey = ConcurrentHashMap<String, String>()
        private val phrasePcmCache = ConcurrentHashMap<String, CachedPcmAudio>()
    }

    /**
     * Silently pre-caches common short greetings so starting AI voice mode feels instant.
     */
    fun prewarmPhrase(phrase: String, geminiApiKey: String) {
        val clean = phrase.trim()
        val key = geminiApiKey.trim()
        if (clean.isBlank() || key.isBlank()) return
        val cacheKey = "${GEMINI_VOICE_MAHI}:${clean.lowercase()}"
        if (phrasePcmCache.containsKey(cacheKey)) return

        clientScope.launch {
            val res = synthesizeWithGeminiTts(clean, key)
            if (res is GeminiTtsOutcome.Success && res.isRawPcm) {
                if (phrasePcmCache.size > 24) phrasePcmCache.clear()
                phrasePcmCache[cacheKey] = CachedPcmAudio(res.audioBytes, res.sampleRate)
            }
        }
    }

    suspend fun testVoiceConnection(
        apiKey: String,
        voiceId: String,
        modelId: String = MODEL_ELEVEN_MULTILINGUAL_V2
    ): VoiceTestResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanVoiceId = voiceId.trim()

        if (cleanVoiceId.isBlank()) {
            return@withContext VoiceTestResult.Failure(
                "Voice ID is empty. Please enter or confirm the Mahi Voice ID.",
                true
            )
        }

        // If no ElevenLabs key is provided, automatically test Gemini Neural Voice using stored Gemini key
        if (cleanKey.isBlank()) {
            val geminiKey = try {
                securePrefs.getGeminiApiKey().trim()
            } catch (_: Exception) {
                ""
            }
            if (geminiKey.isBlank()) {
                return@withContext VoiceTestResult.Failure(
                    "Voice API key is missing. Please enter your ElevenLabs API key.",
                    true
                )
            }
            val geminiRes = synthesizeWithGeminiTts(
                text = "Namaste! Main Myraa hoon, Gemini voice ready hai.",
                geminiApiKey = geminiKey
            )
            return@withContext when (geminiRes) {
                is GeminiTtsOutcome.Success -> {
                    if (geminiRes.isRawPcm) {
                        playPcmDirectSuspend(geminiRes.audioBytes, geminiRes.sampleRate, ++speechGenerationId, {}, {})
                    } else {
                        val cacheFile = File(context.cacheDir, "test_gemini_voice_${System.currentTimeMillis()}.${geminiRes.fileExtension}")
                        cacheFile.writeBytes(geminiRes.audioBytes)
                        playAudioFileSuspend(cacheFile, ++speechGenerationId, {}, {})
                    }
                    VoiceTestResult.Success(
                        message = "Gemini Neural Voice (Mahi • ${geminiRes.modelUsed}) verified!",
                        audioBytes = geminiRes.audioBytes.size
                    )
                }
                is GeminiTtsOutcome.Failure -> {
                    VoiceTestResult.Failure(geminiRes.reason, isConfigError = false)
                }
            }
        }

        try {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$cleanVoiceId"
            val jsonPayload = JSONObject().apply {
                put("text", "Namaste! This is Myraa testing Mahi voice.")
                put("model_id", if (modelId.isNotBlank()) modelId else MODEL_ELEVEN_FLASH_V2_5)
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                })
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", cleanKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "audio/mpeg")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val bytes = response.body!!.bytes()
                    if (bytes.size > 200) {
                        val cacheFile = File(context.cacheDir, "test_voice_${System.currentTimeMillis()}.mp3")
                        cacheFile.writeBytes(bytes)
                        playAudioFileSuspend(cacheFile, ++speechGenerationId, {}, {})
                        return@withContext VoiceTestResult.Success(
                            message = "Voice connection verified with Mahi ($cleanVoiceId)!",
                            audioBytes = bytes.size
                        )
                    } else {
                        return@withContext VoiceTestResult.Failure("Received empty audio stream from ElevenLabs.")
                    }
                } else {
                    val code = response.code
                    val err = response.body?.string() ?: ""
                    val msg = when (code) {
                        401 -> "ElevenLabs API key is unauthorized or invalid."
                        404 -> "Voice ID '$cleanVoiceId' not found on ElevenLabs."
                        429 -> "ElevenLabs quota or rate limit exceeded."
                        else -> "ElevenLabs returned HTTP $code: $err"
                    }
                    return@withContext VoiceTestResult.Failure(msg, isConfigError = code == 401 || code == 404)
                }
            }
        } catch (e: Exception) {
            Log.e("ElevenLabsClient", "Voice test error", e)
            return@withContext VoiceTestResult.Failure("Network error: ${e.localizedMessage ?: "Failed to connect to ElevenLabs"}")
        }
    }

    suspend fun speakText(
        text: String,
        apiKey: String,
        voiceId: String,
        modelId: String = MODEL_ELEVEN_MULTILINGUAL_V2,
        speechSpeed: Float = 1.0f,
        onPlaybackStarted: () -> Unit = {},
        onPlaybackCompleted: () -> Unit = {},
        onError: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            withContext(Dispatchers.Main) { onPlaybackCompleted() }
            return@withContext
        }

        stopSpeaking()
        val myGenId = ++speechGenerationId
        val cleanKey = apiKey.trim()
        val targetVoiceId = if (voiceId.isNotBlank()) voiceId.trim() else DEFAULT_MAHI_VOICE_ID

        // 1. ElevenLabs Neural Voice (when ElevenLabs key is configured)
        if (cleanKey.isNotBlank()) {
            try {
                val url = "https://api.elevenlabs.io/v1/text-to-speech/$targetVoiceId?optimize_streaming_latency=3"
                val jsonPayload = JSONObject().apply {
                    put("text", cleanText)
                    put("model_id", if (modelId.isNotBlank()) modelId.trim() else MODEL_ELEVEN_FLASH_V2_5)
                    put("voice_settings", JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.75)
                    })
                }

                val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("xi-api-key", cleanKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "audio/mpeg")
                    .post(requestBody)
                    .build()

                val audioBytes = NetworkRetryHelper.withExponentialBackoff(maxAttempts = 2) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful && response.body != null) {
                            response.body!!.bytes()
                        } else {
                            val code = response.code
                            if (code == 429 || code >= 500) {
                                throw java.io.IOException("Transient ElevenLabs HTTP $code")
                            }
                            Log.w("ElevenLabsClient", "ElevenLabs failed HTTP $code")
                            onError?.invoke("ElevenLabs error (HTTP $code)")
                            null
                        }
                    }
                }

                if (audioBytes != null && audioBytes.size > 200 && myGenId == speechGenerationId) {
                    val cacheFile = File(context.cacheDir, "myraa_speech_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(cacheFile).use { it.write(audioBytes) }
                    playAudioFileSuspend(cacheFile, myGenId, onPlaybackStarted, onPlaybackCompleted)
                    return@withContext
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w("ElevenLabsClient", "Error calling ElevenLabs API", e)
                onError?.invoke(e.localizedMessage ?: "Voice network error")
            }
        }

        // 2. Fast Gemini Neural AI TTS (parallel chunked pipeline + direct AudioTrack PCM playback)
        val geminiKey = try {
            securePrefs.getGeminiApiKey().trim()
        } catch (_: Exception) {
            ""
        }

        if (geminiKey.isBlank()) {
            onError?.invoke("AI Voice requires a Gemini or ElevenLabs API key.")
            withContext(Dispatchers.Main) { onPlaybackCompleted() }
            return@withContext
        }

        val chunks = splitIntoFastSpeechChunks(cleanText)
        val startNotified = java.util.concurrent.atomic.AtomicBoolean(false)
        fun notifyStartOnce() {
            if (startNotified.compareAndSet(false, true)) {
                mainHandler.post { if (myGenId == speechGenerationId) onPlaybackStarted() }
            }
        }

        try {
            // Launch all chunks in parallel so chunk 2+ synthesizes WHILE chunk 1 is playing!
            val deferredResults: List<Deferred<GeminiTtsOutcome>> = chunks.map { chunk ->
                async(Dispatchers.IO) {
                    val cacheKey = "${GEMINI_VOICE_MAHI}:${chunk.lowercase()}"
                    val cached = phrasePcmCache[cacheKey]
                    if (cached != null) {
                        GeminiTtsOutcome.Success(
                            audioBytes = cached.pcmBytes,
                            fileExtension = "pcm",
                            modelUsed = "cache",
                            isRawPcm = true,
                            sampleRate = cached.sampleRate
                        )
                    } else {
                        synthesizeWithGeminiTts(chunk, geminiKey).also { res ->
                            if (res is GeminiTtsOutcome.Success && res.isRawPcm && chunk.length <= 140) {
                                if (phrasePcmCache.size > 24) phrasePcmCache.clear()
                                phrasePcmCache[cacheKey] = CachedPcmAudio(res.audioBytes, res.sampleRate)
                            }
                        }
                    }
                }
            }

            var playedAnyChunk = false
            var lastErrorReason: String? = null

            for (deferred in deferredResults) {
                if (myGenId != speechGenerationId) break
                val outcome = deferred.await()
                if (myGenId != speechGenerationId) break

                when (outcome) {
                    is GeminiTtsOutcome.Success -> {
                        playedAnyChunk = true
                        if (outcome.isRawPcm) {
                            playPcmDirectSuspend(
                                pcmData = outcome.audioBytes,
                                sampleRate = outcome.sampleRate,
                                genId = myGenId,
                                onStart = { notifyStartOnce() },
                                onEnd = {}
                            )
                        } else {
                            val cacheFile = File(
                                context.cacheDir,
                                "myraa_gemini_speech_${System.currentTimeMillis()}.${outcome.fileExtension}"
                            )
                            FileOutputStream(cacheFile).use { it.write(outcome.audioBytes) }
                            playAudioFileSuspend(
                                file = cacheFile,
                                genId = myGenId,
                                onStart = { notifyStartOnce() },
                                onEnd = {}
                            )
                        }
                    }
                    is GeminiTtsOutcome.Failure -> {
                        lastErrorReason = outcome.reason
                        Log.w("ElevenLabsClient", "Gemini TTS chunk failed: ${outcome.reason}")
                    }
                }
            }

            if (!playedAnyChunk && lastErrorReason != null) {
                onError?.invoke("Gemini AI voice unavailable: $lastErrorReason")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w("ElevenLabsClient", "Gemini TTS playback pipeline failed", e)
            onError?.invoke("Gemini AI voice error: ${e.localizedMessage ?: "Playback failed"}")
        } finally {
            withContext(Dispatchers.Main) {
                if (myGenId == speechGenerationId) {
                    onPlaybackCompleted()
                }
            }
        }
    }

    /**
     * Splits multi-sentence text into small chunks (<= 110 chars) so the first sentence
     * reaches AudioTrack immediately while subsequent sentences synthesize in parallel.
     */
    internal fun splitIntoFastSpeechChunks(text: String): List<String> {
        val clean = text.trim()
        if (clean.length <= 95) return listOf(clean)

        val rawSentences = clean.split(Regex("(?<=[.!?।])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawSentences.size <= 1) return listOf(clean)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in rawSentences) {
            if (current.isEmpty()) {
                current.append(sentence)
            } else if (current.length + 1 + sentence.length <= 105) {
                current.append(' ').append(sentence)
            } else {
                chunks.add(current.toString())
                current.clear()
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks.ifEmpty { listOf(clean) }
    }

    private sealed class GeminiTtsOutcome {
        data class Success(
            val audioBytes: ByteArray,
            val fileExtension: String,
            val modelUsed: String,
            val isRawPcm: Boolean = false,
            val sampleRate: Int = 24000
        ) : GeminiTtsOutcome()
        data class Failure(val reason: String) : GeminiTtsOutcome()
    }

    private suspend fun synthesizeWithGeminiTts(
        text: String,
        geminiApiKey: String
    ): GeminiTtsOutcome = withContext(Dispatchers.IO) {
        val cleanKey = geminiApiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext GeminiTtsOutcome.Failure("Gemini API key is missing.")
        }

        val queue = ArrayDeque<String>()
        val attempted = mutableSetOf<String>()
        verifiedTtsModelByKey[cleanKey]?.let { queue.add(it) }
        discoveredTtsModelsCache[cleanKey]?.forEach { if (!queue.contains(it)) queue.add(it) }
        DEFAULT_GEMINI_TTS_MODELS.forEach { if (!queue.contains(it)) queue.add(it) }

        var didDiscovery = !discoveredTtsModelsCache[cleanKey].isNullOrEmpty()
        var lastError = "Gemini TTS synthesis failed."

        while (queue.isNotEmpty()) {
            val model = queue.removeFirst()
            if (!attempted.add(model)) continue

            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", text))
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", GEMINI_VOICE_MAHI)
                                })
                            })
                        })
                    })
                }

                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", cleanKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val (code, respBody) = NetworkRetryHelper.withExponentialBackoff(maxAttempts = 2) {
                    client.newCall(request).execute().use { resp ->
                        Pair(resp.code, resp.body?.string() ?: "")
                    }
                }

                if (code in 200..299 && respBody.isNotBlank()) {
                    val extracted = extractAudioFromGeminiResponse(respBody)
                    if (extracted != null) {
                        verifiedTtsModelByKey[cleanKey] = model
                        return@withContext extracted.copy(modelUsed = model)
                    } else {
                        lastError = "Gemini TTS ($model) returned no audio bytes."
                    }
                } else {
                    lastError = "HTTP $code"
                    if (code == 401 || code == 403) {
                        return@withContext GeminiTtsOutcome.Failure("Gemini API key unauthorized ($code).")
                    }
                    if (!didDiscovery && (code == 404 || code == 400)) {
                        didDiscovery = true
                        val liveTts = discoverGeminiTtsModels(cleanKey)
                        liveTts.asReversed().forEach { m ->
                            if (!attempted.contains(m) && !queue.contains(m)) {
                                queue.addFirst(m)
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "Network error"
                Log.w("ElevenLabsClient", "Gemini TTS error with $model", e)
            }
        }

        GeminiTtsOutcome.Failure(lastError)
    }

    private fun discoverGeminiTtsModels(geminiApiKey: String): List<String> {
        return try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                .addHeader("x-goog-api-key", geminiApiKey)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val raw = resp.body?.string() ?: return emptyList()
                val modelsArr = JSONObject(raw).optJSONArray("models") ?: return emptyList()
                val ttsModels = mutableListOf<String>()
                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.optJSONObject(i) ?: continue
                    val shortName = obj.optString("name", "").trim().removePrefix("models/")
                    if (!shortName.startsWith("gemini-", ignoreCase = true)) continue
                    if (!shortName.contains("tts", ignoreCase = true)) continue
                    val methods = obj.optJSONArray("supportedGenerationMethods")
                    var supportsGenerate = false
                    if (methods != null) {
                        for (m in 0 until methods.length()) {
                            if (methods.optString(m) == "generateContent") {
                                supportsGenerate = true
                                break
                            }
                        }
                    }
                    if (supportsGenerate) ttsModels += shortName
                }
                val sorted = ttsModels.distinct().sortedBy { if (it.contains("flash", ignoreCase = true)) 0 else 1 }
                if (sorted.isNotEmpty()) {
                    discoveredTtsModelsCache[geminiApiKey] = sorted
                }
                sorted
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractAudioFromGeminiResponse(rawJson: String): GeminiTtsOutcome.Success? {
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) {
                val inlineData = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val b64 = inlineData.optString("data", "")
                if (b64.isBlank()) continue
                val mimeType = inlineData.optString("mimeType", "audio/L16;codec=pcm;rate=24000")
                val rawBytes = Base64.decode(b64, Base64.DEFAULT)
                if (rawBytes.size < 64) continue

                val isRiffWav = rawBytes.size >= 4 &&
                    rawBytes[0] == 'R'.code.toByte() &&
                    rawBytes[1] == 'I'.code.toByte() &&
                    rawBytes[2] == 'F'.code.toByte() &&
                    rawBytes[3] == 'F'.code.toByte()
                if (isRiffWav) {
                    return GeminiTtsOutcome.Success(rawBytes, "wav", "", isRawPcm = false)
                }
                if (mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true)) {
                    return GeminiTtsOutcome.Success(rawBytes, "mp3", "", isRawPcm = false)
                }
                val sampleRate = Regex("rate=(\\d+)").find(mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                // Keep raw PCM16 for zero-latency direct AudioTrack streaming (no WAV wrapping/disk I/O!)
                return GeminiTtsOutcome.Success(rawBytes, "pcm", "", isRawPcm = true, sampleRate = sampleRate)
            }
            null
        } catch (e: Exception) {
            Log.w("ElevenLabsClient", "Failed to parse Gemini TTS audio response", e)
            null
        }
    }

    private suspend fun playPcmDirectSuspend(
        pcmData: ByteArray,
        sampleRate: Int,
        genId: Long,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) = withContext(Dispatchers.IO) {
        if (pcmData.isEmpty() || genId != speechGenerationId) {
            onEnd()
            return@withContext
        }

        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                onEnd()
                return@withContext
            }
            val streamBufSize = maxOf(minBuf * 2, 8192)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(streamBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED || genId != speechGenerationId) {
                try { track.release() } catch (_: Exception) {}
                onEnd()
                return@withContext
            }

            activeAudioTrack = track
            val startedAt = System.currentTimeMillis()
            onStart()
            track.play()

            var offset = 0
            val writeChunk = 4096
            while (offset < pcmData.size && isActive && genId == speechGenerationId) {
                val toWrite = minOf(writeChunk, pcmData.size - offset)
                val written = track.write(pcmData, offset, toWrite)
                if (written <= 0) break
                offset += written
            }

            if (genId == speechGenerationId && isActive) {
                val totalAudioMs = (pcmData.size * 1000L) / (sampleRate * 2L)
                val elapsed = System.currentTimeMillis() - startedAt
                val remaining = (totalAudioMs - elapsed).coerceAtLeast(0L)
                if (remaining > 0L) {
                    delay(remaining + 60L)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e("ElevenLabsClient", "AudioTrack playback error", e)
        } finally {
            try {
                track?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (_: Exception) {}
            if (activeAudioTrack == track) activeAudioTrack = null
            onEnd()
        }
    }

    private suspend fun playAudioFileSuspend(
        file: File,
        genId: Long,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) = suspendCancellableCoroutine<Unit> { cont ->
        mainHandler.post {
            if (genId != speechGenerationId) {
                try { file.delete() } catch (_: Exception) {}
                onEnd()
                if (cont.isActive) cont.resume(Unit)
                return@post
            }

            var completedCalled = false
            fun safeFinish() {
                if (!completedCalled) {
                    completedCalled = true
                    try { file.delete() } catch (_: Exception) {}
                    onEnd()
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            try {
                releaseMediaPlayerOnly()
                val mp = MediaPlayer()
                activeMediaPlayer = mp

                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                mp.setVolume(1.0f, 1.0f)

                FileInputStream(file).use { fis ->
                    mp.setDataSource(fis.fd)
                }

                mp.setOnPreparedListener { preparedMp ->
                    if (genId != speechGenerationId) {
                        try { preparedMp.release() } catch (_: Exception) {}
                        if (activeMediaPlayer == preparedMp) activeMediaPlayer = null
                        safeFinish()
                        return@setOnPreparedListener
                    }
                    onStart()
                    preparedMp.start()

                    val duration = preparedMp.duration
                    val timeout = if (duration > 0) duration + 1500L else 12000L
                    mainHandler.postDelayed({ safeFinish() }, timeout)
                }
                mp.setOnCompletionListener { completedMp ->
                    completedMp.release()
                    if (activeMediaPlayer == completedMp) activeMediaPlayer = null
                    safeFinish()
                }
                mp.setOnErrorListener { errorMp, what, extra ->
                    Log.e("ElevenLabsClient", "MediaPlayer error: what=$what, extra=$extra")
                    errorMp.release()
                    if (activeMediaPlayer == errorMp) activeMediaPlayer = null
                    safeFinish()
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.e("ElevenLabsClient", "Failed to play audio file", e)
                safeFinish()
            }
        }
    }

    private fun releaseMediaPlayerOnly() {
        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        activeMediaPlayer = null
    }

    fun stopSpeaking() {
        speechGenerationId++
        activeSpeechJob?.cancel()
        activeSpeechJob = null
        mainHandler.removeCallbacksAndMessages(null)
        releaseMediaPlayerOnly()
        try {
            activeAudioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("ElevenLabsClient", "Error stopping AudioTrack", e)
        }
        activeAudioTrack = null
    }

    fun shutdown() {
        stopSpeaking()
        clientScope.cancel()
    }
}
