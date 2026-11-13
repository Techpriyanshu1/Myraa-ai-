package com.example.data.api

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.data.pref.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class VoiceTestResult {
    data class Success(val message: String, val audioBytes: Int) : VoiceTestResult()
    data class Failure(val errorMessage: String, val isConfigError: Boolean = false) : VoiceTestResult()
}

class ElevenLabsClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val securePrefs by lazy { SecurePreferences(context.applicationContext) }

    private var activeMediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
    }

    suspend fun testVoiceConnection(
        apiKey: String,
        voiceId: String,
        modelId: String = MODEL_ELEVEN_MULTILINGUAL_V2
    ): VoiceTestResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanVoiceId = voiceId.trim()

        if (cleanVoiceId.isBlank()) {
            return@withContext VoiceTestResult.Failure("Voice ID is empty. Please enter or confirm the Mahi Voice ID.", true)
        }

        // If no ElevenLabs key is provided, automatically test Gemini Neural Voice using stored Gemini key
        if (cleanKey.isBlank()) {
            val geminiKey = try {
                securePrefs.getGeminiApiKey().trim()
            } catch (_: Exception) {
                ""
            }
            if (geminiKey.isBlank()) {
                return@withContext VoiceTestResult.Failure("Voice API key is missing. Please enter your ElevenLabs API key.", true)
            }
            val geminiRes = synthesizeWithGeminiTts(
                text = "Namaste! Main Myraa hoon, aapki Mahi voice Gemini ke saath taiyyar hai.",
                geminiApiKey = geminiKey
            )
            return@withContext when (geminiRes) {
                is GeminiTtsOutcome.Success -> {
                    val cacheFile = File(context.cacheDir, "test_gemini_voice_${System.currentTimeMillis()}.${geminiRes.fileExtension}")
                    cacheFile.writeBytes(geminiRes.audioBytes)
                    withContext(Dispatchers.Main) {
                        playAudioFile(cacheFile, {}, {})
                    }
                    VoiceTestResult.Success(
                        message = "Gemini Neural Voice (Mahi • ${geminiRes.modelUsed}) verified without ElevenLabs!",
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
                put("text", "Namaste! This is Myraa testing the configured voice.")
                put("model_id", if (modelId.isNotBlank()) modelId else MODEL_ELEVEN_MULTILINGUAL_V2)
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

            val response = client.newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                val bytes = response.body!!.bytes()
                if (bytes.size > 200) {
                    val cacheFile = File(context.cacheDir, "test_voice_${System.currentTimeMillis()}.mp3")
                    cacheFile.writeBytes(bytes)
                    withContext(Dispatchers.Main) {
                        playAudioFile(cacheFile, {}, {})
                    }
                    return@withContext VoiceTestResult.Success(
                        message = "Voice connection verified successfully with Mahi ($cleanVoiceId)!",
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
                    404 -> "Voice ID '$cleanVoiceId' not found on ElevenLabs. Please verify your Mahi Voice ID."
                    429 -> "ElevenLabs quota or rate limit exceeded."
                    else -> "ElevenLabs returned HTTP $code: $err"
                }
                return@withContext VoiceTestResult.Failure(msg, isConfigError = code == 401 || code == 404)
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

        var playedViaCloudVoice = false
        val cleanKey = apiKey.trim()
        val targetVoiceId = if (voiceId.isNotBlank()) voiceId.trim() else DEFAULT_MAHI_VOICE_ID

        // 1. ElevenLabs Neural Voice (when ElevenLabs subscription key is configured)
        if (cleanKey.isNotBlank()) {
            try {
                val url = "https://api.elevenlabs.io/v1/text-to-speech/$targetVoiceId"
                val jsonPayload = JSONObject().apply {
                    put("text", cleanText)
                    put("model_id", if (modelId.isNotBlank()) modelId.trim() else MODEL_ELEVEN_MULTILINGUAL_V2)
                    put("voice_settings", JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.75)
                        put("style", 0.15)
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

                val audioBytes = com.example.core.NetworkRetryHelper.withExponentialBackoff(
                    maxAttempts = 2
                ) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful && response.body != null) {
                            response.body!!.bytes()
                        } else {
                            val code = response.code
                            val err = response.body?.string() ?: ""
                            if (code == 429 || code >= 500) {
                                throw java.io.IOException("Transient ElevenLabs HTTP $code")
                            }
                            Log.w("ElevenLabsClient", "ElevenLabs failed HTTP $code: $err")
                            onError?.invoke("ElevenLabs error (HTTP $code)")
                            null
                        }
                    }
                }

                if (audioBytes != null && audioBytes.size > 200) {
                    val cacheFile = File(context.cacheDir, "myraa_speech_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(cacheFile).use { output ->
                        output.write(audioBytes)
                    }
                    withContext(Dispatchers.Main) {
                        playAudioFile(cacheFile, onPlaybackStarted, onPlaybackCompleted)
                    }
                    playedViaCloudVoice = true
                    Log.i("ElevenLabsClient", "Mahi voice synthesized via ElevenLabs: ${audioBytes.size} bytes")
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w("ElevenLabsClient", "Error calling ElevenLabs API for $targetVoiceId", e)
                onError?.invoke(e.localizedMessage ?: "Voice network error")
            }
        }

        // 2. Gemini Neural Voice (uses stored Gemini API key — no ElevenLabs or OpenAI key required!)
        if (!playedViaCloudVoice) {
            val geminiKey = try {
                securePrefs.getGeminiApiKey().trim()
            } catch (_: Exception) {
                ""
            }
            if (geminiKey.isNotBlank()) {
                when (val ttsOutcome = synthesizeWithGeminiTts(cleanText, geminiKey)) {
                    is GeminiTtsOutcome.Success -> {
                        try {
                            val cacheFile = File(
                                context.cacheDir,
                                "myraa_gemini_speech_${System.currentTimeMillis()}.${ttsOutcome.fileExtension}"
                            )
                            FileOutputStream(cacheFile).use { output ->
                                output.write(ttsOutcome.audioBytes)
                            }
                            withContext(Dispatchers.Main) {
                                playAudioFile(cacheFile, onPlaybackStarted, onPlaybackCompleted)
                            }
                            playedViaCloudVoice = true
                            Log.i(
                                "ElevenLabsClient",
                                "Mahi voice synthesized via Gemini (${ttsOutcome.modelUsed}): ${ttsOutcome.audioBytes.size} bytes"
                            )
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            Log.w("ElevenLabsClient", "Failed to write/play Gemini TTS file", e)
                        }
                    }
                    is GeminiTtsOutcome.Failure -> {
                        Log.w("ElevenLabsClient", "Gemini AI TTS unavailable: ${ttsOutcome.reason}")
                        onError?.invoke("Gemini AI voice unavailable: ${ttsOutcome.reason}")
                    }
                }
            } else {
                onError?.invoke("AI Voice requires a Gemini or ElevenLabs API key.")
            }
        }

        // Pure AI TTS only — never fall back to robotic Android native TTS
        if (!playedViaCloudVoice) {
            withContext(Dispatchers.Main) {
                onPlaybackCompleted()
            }
        }
    }

    private sealed class GeminiTtsOutcome {
        data class Success(
            val audioBytes: ByteArray,
            val fileExtension: String,
            val modelUsed: String
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

                val (code, respBody) = com.example.core.NetworkRetryHelper.withExponentialBackoff(maxAttempts = 2) {
                    client.newCall(request).execute().use { resp ->
                        Pair(resp.code, resp.body?.string() ?: "")
                    }
                }

                if (code in 200..299 && respBody.isNotBlank()) {
                    val extracted = extractAudioFromGeminiResponse(respBody)
                    if (extracted != null) {
                        verifiedTtsModelByKey[cleanKey] = model
                        return@withContext GeminiTtsOutcome.Success(
                            audioBytes = extracted.first,
                            fileExtension = extracted.second,
                            modelUsed = model
                        )
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
            } catch (ce: kotlinx.coroutines.CancellationException) {
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

    private fun extractAudioFromGeminiResponse(rawJson: String): Pair<ByteArray, String>? {
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
                    return Pair(rawBytes, "wav")
                }
                if (mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true)) {
                    return Pair(rawBytes, "mp3")
                }
                val sampleRate = Regex("rate=(\\d+)").find(mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                return Pair(wrapPcm16AsWav(rawBytes, sampleRate, channels = 1), "wav")
            }
            null
        } catch (e: Exception) {
            Log.w("ElevenLabsClient", "Failed to parse Gemini TTS audio response", e)
            null
        }
    }

    private fun wrapPcm16AsWav(pcmData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(chunkSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(16) // PCM subchunk1 size
        writeShortLE(1) // PCM audio format
        writeShortLE(channels)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(bitsPerSample)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(dataSize)
        out.write(pcmData)
        return out.toByteArray()
    }

    private fun playAudioFile(file: File, onStart: () -> Unit, onEnd: () -> Unit) {
        var completedCalled = false
        fun safeOnEnd() {
            if (!completedCalled) {
                completedCalled = true
                try { file.delete() } catch (ignored: Exception) {}
                onEnd()
            }
        }

        try {
            stopSpeaking()
            val mp = MediaPlayer()
            activeMediaPlayer = mp

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setVolume(1.0f, 1.0f)

            val fis = FileInputStream(file)
            mp.setDataSource(fis.fd)
            fis.close()

            mp.setOnPreparedListener { preparedMp ->
                onStart()
                preparedMp.start()

                // Safety watchdog
                val duration = preparedMp.duration
                val timeout = if (duration > 0) duration + 1500L else 12000L
                mainHandler.postDelayed({ safeOnEnd() }, timeout)
            }
            mp.setOnCompletionListener { completedMp ->
                completedMp.release()
                if (activeMediaPlayer == completedMp) activeMediaPlayer = null
                safeOnEnd()
            }
            mp.setOnErrorListener { errorMp, what, extra ->
                Log.e("ElevenLabsClient", "MediaPlayer error: what=$what, extra=$extra")
                errorMp.release()
                if (activeMediaPlayer == errorMp) activeMediaPlayer = null
                safeOnEnd()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e("ElevenLabsClient", "Failed to play ElevenLabs audio file", e)
            safeOnEnd()
        }
    }

    fun stopSpeaking() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            activeMediaPlayer = null
        } catch (e: Exception) {
            Log.e("ElevenLabsClient", "Error stopping audio", e)
        }
    }

    fun shutdown() {
        stopSpeaking()
    }
}
