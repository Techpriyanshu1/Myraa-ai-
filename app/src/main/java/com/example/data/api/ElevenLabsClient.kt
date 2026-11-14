package com.example.data.api

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.core.NetworkRetryHelper
import com.example.core.SharedHttpClient
import com.example.data.pref.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

sealed class VoiceTestResult {
    data class Success(val message: String, val audioBytes: Int) : VoiceTestResult()
    data class Failure(val errorMessage: String, val isConfigError: Boolean = false) : VoiceTestResult()
}

/**
 * Pure AI Neural Voice Client (NO Android native TextToSpeech).
 * - Persistent disk + RAM cache so repeated phrases play in < 10ms with zero API calls.
 * - Per-model HTTP 429 cooldown protection & smart retry/fallback between Gemini & ElevenLabs.
 * - Direct AudioTrack PCM streaming (with MediaPlayer WAV fallback) + AudioFocus management.
 */
class ElevenLabsClient(private val context: Context) {

    private val client = SharedHttpClient.instance
    private val securePrefs by lazy { SecurePreferences(context.applicationContext) }
    private val audioManager by lazy {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val ttsDiskCacheDir: File by lazy {
        File(context.applicationContext.cacheDir, "myraa_tts_cache").apply { mkdirs() }
    }
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeMediaPlayer: MediaPlayer? = null
    @Volatile
    private var isMediaPlayerPrepared: Boolean = false
    private var activeAudioTrack: AudioTrack? = null
    private var activeSpeechJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var speechGenerationId: Long = 0L

    private data class CachedPcmAudio(val pcmBytes: ByteArray, val sampleRate: Int)

    companion object {
        const val DEFAULT_MAHI_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
        const val MODEL_ELEVEN_MULTILINGUAL_V2 = "eleven_multilingual_v2"
        const val MODEL_ELEVEN_FLASH_V2_5 = "eleven_flash_v2_5"

        private const val GEMINI_VOICE_MAHI = "Aoede"
        private const val MAX_DISK_CACHE_FILES = 150
        private const val MAX_CACHE_PHRASE_CHARS = 280
        private const val RATE_LIMIT_COOLDOWN_MS = 2_500L
        private const val ZERO_QUOTA_COOLDOWN_MS = 300_000L
        private val DEFAULT_GEMINI_TTS_MODELS = listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts"
        )
        private val discoveredTtsModelsCache = ConcurrentHashMap<String, List<String>>()
        private val verifiedTtsModelByKey = ConcurrentHashMap<String, String>()
        private val phrasePcmCache = ConcurrentHashMap<String, CachedPcmAudio>()
        private val modelCooldownUntilMs = ConcurrentHashMap<String, Long>()
        private val zeroQuotaModels = ConcurrentHashMap.newKeySet<String>()
        @Volatile
        private var elevenLabsCooldownUntilMs = 0L
    }

    /**
     * Plays a crisp dual-tone synthesized warning beep via AudioTrack when an unknown/different
     * voice is detected instead of Boss/Jaan (zero Android TTS).
     */
    suspend fun playSecurityAlertCue() = withContext(Dispatchers.IO) {
        var track: AudioTrack? = null
        try {
            val sampleRate = 24000
            val totalSamples = (sampleRate * 220) / 1000
            val pcm = ByteArray(totalSamples * 2)
            for (i in 0 until totalSamples) {
                val t = i.toDouble() / totalSamples
                val inFirstPulse = t < 0.42
                val inSecondPulse = t > 0.56
                val amp = if (inFirstPulse || inSecondPulse) 0.34 else 0.0
                val freq = if (inFirstPulse) 980.0 else 740.0
                val sample = (kotlin.math.sin(2.0 * Math.PI * freq * i / sampleRate) * 32767.0 * amp).toInt()
                    .coerceIn(-32768, 32767)
                pcm[i * 2] = (sample and 0xFF).toByte()
                pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return@withContext
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.write(pcm, 0, pcm.size)
                track.play()
                delay(235L)
            }
        } catch (_: Exception) {
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Plays a crisp 45ms synthesized sine confirmation cue directly through AudioTrack
     * for rapid gesture feedback (scroll/tap) without hitting any cloud API or rate limit.
     */
    suspend fun playQuickAckCue() = withContext(Dispatchers.IO) {
        var track: AudioTrack? = null
        try {
            val sampleRate = 24000
            val numSamples = (sampleRate * 45) / 1000
            val pcm = ByteArray(numSamples * 2)
            for (i in 0 until numSamples) {
                val env = (1.0 - i.toDouble() / numSamples) * 0.22
                val freq = 720.0 + (i.toDouble() / numSamples) * 240.0
                val sample = (kotlin.math.sin(2.0 * Math.PI * freq * i / sampleRate) * 32767.0 * env).toInt()
                    .coerceIn(-32768, 32767)
                pcm[i * 2] = (sample and 0xFF).toByte()
                pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@withContext
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.write(pcm, 0, pcm.size)
                track.play()
                delay(55L)
            }
        } catch (_: Exception) {
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Silently pre-caches common short phrases only if not already cached on disk.
     */
    fun prewarmPhrase(phrase: String, geminiApiKey: String) {
        val clean = phrase.trim()
        val key = geminiApiKey.trim()
        if (clean.isBlank() || key.isBlank()) return
        if (loadCachedGeminiPcm(clean) != null) return

        clientScope.launch {
            val res = synthesizeWithGeminiTts(clean, key, allowRateLimitRetry = false)
            if (res is GeminiTtsOutcome.Success && res.isRawPcm) {
                saveCachedGeminiPcm(clean, res.audioBytes, res.sampleRate)
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

        val geminiKey = try {
            securePrefs.getGeminiApiKey().trim()
        } catch (_: Exception) {
            ""
        }

        if (cleanKey.isBlank() && geminiKey.isBlank()) {
            return@withContext VoiceTestResult.Failure(
                "Gemini API key missing. Settings me apni Gemini API key enter karein.",
                true
            )
        }

        val preferredEngine = try {
            securePrefs.getEffectiveVoiceProvider()
        } catch (_: Exception) {
            if (cleanKey.isNotBlank()) SecurePreferences.VOICE_PROVIDER_ELEVENLABS
            else SecurePreferences.VOICE_PROVIDER_GEMINI
        }

        val useGeminiFirst = (preferredEngine == SecurePreferences.VOICE_PROVIDER_GEMINI && geminiKey.isNotBlank()) ||
            cleanKey.isBlank()

        if (useGeminiFirst) {
            val geminiRes = synthesizeWithGeminiTts(
                text = "Namaste! Main Myraa hoon, Gemini voice ready hai.",
                geminiApiKey = geminiKey,
                allowRateLimitRetry = true
            )
            return@withContext when (geminiRes) {
                is GeminiTtsOutcome.Success -> {
                    requestSpeechAudioFocus()
                    try {
                        if (geminiRes.isRawPcm) {
                            playPcmDirectSuspend(geminiRes.audioBytes, geminiRes.sampleRate, ++speechGenerationId, 1.0f, {}, {})
                        } else {
                            val cacheFile = File(context.cacheDir, "test_gemini_voice_${System.currentTimeMillis()}.${geminiRes.fileExtension}")
                            cacheFile.writeBytes(geminiRes.audioBytes)
                            playAudioFileSuspend(cacheFile, ++speechGenerationId, 1.0f, {}, {})
                        }
                    } finally {
                        abandonSpeechAudioFocus()
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
                        requestSpeechAudioFocus()
                        try {
                            val cacheFile = File(context.cacheDir, "test_voice_${System.currentTimeMillis()}.mp3")
                            cacheFile.writeBytes(bytes)
                            playAudioFileSuspend(cacheFile, ++speechGenerationId, 1.0f, {}, {})
                        } finally {
                            abandonSpeechAudioFocus()
                        }
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
                        429 -> "ElevenLabs quota or rate limit exceeded (HTTP 429)."
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
        val cleanElevenKey = apiKey.trim()
        val targetVoiceId = if (voiceId.isNotBlank()) voiceId.trim() else DEFAULT_MAHI_VOICE_ID
        val geminiKey = try {
            securePrefs.getGeminiApiKey().trim()
        } catch (_: Exception) {
            ""
        }

        if (cleanElevenKey.isBlank() && geminiKey.isBlank()) {
            withContext(Dispatchers.Main) {
                if (onError != null) {
                    onError.invoke("AI Voice ke liye Settings me Gemini API key add karein.")
                } else {
                    onPlaybackCompleted()
                }
            }
            return@withContext
        }

        val startNotified = java.util.concurrent.atomic.AtomicBoolean(false)
        fun notifyStartOnce() {
            if (startNotified.compareAndSet(false, true)) {
                mainHandler.post {
                    if (myGenId == speechGenerationId) onPlaybackStarted()
                }
            }
        }

        val preferredEngine = try {
            securePrefs.getEffectiveVoiceProvider()
        } catch (_: Exception) {
            if (cleanElevenKey.isNotBlank()) SecurePreferences.VOICE_PROVIDER_ELEVENLABS
            else SecurePreferences.VOICE_PROVIDER_GEMINI
        }

        requestSpeechAudioFocus()
        var didInvokeError = false

        try {
            val now = System.currentTimeMillis()
            val elevenReady = cleanElevenKey.isNotBlank() && now >= elevenLabsCooldownUntilMs

            val tryOrder: List<String> = when {
                cleanElevenKey.isBlank() -> listOfNotNull(
                    SecurePreferences.VOICE_PROVIDER_GEMINI.takeIf { geminiKey.isNotBlank() }
                )
                preferredEngine == SecurePreferences.VOICE_PROVIDER_ELEVENLABS -> listOfNotNull(
                    SecurePreferences.VOICE_PROVIDER_ELEVENLABS.takeIf { elevenReady || geminiKey.isBlank() },
                    SecurePreferences.VOICE_PROVIDER_GEMINI.takeIf { geminiKey.isNotBlank() }
                )
                else -> listOfNotNull(
                    SecurePreferences.VOICE_PROVIDER_GEMINI.takeIf { geminiKey.isNotBlank() },
                    SecurePreferences.VOICE_PROVIDER_ELEVENLABS
                )
            }

            var lastFailure: String? = null
            var spokeSuccessfully = false

            for (engine in tryOrder) {
                if (myGenId != speechGenerationId) break
                val err = when (engine) {
                    SecurePreferences.VOICE_PROVIDER_ELEVENLABS -> speakWithElevenLabs(
                        cleanText = cleanText,
                        cleanKey = cleanElevenKey,
                        targetVoiceId = targetVoiceId,
                        modelId = modelId,
                        genId = myGenId,
                        speechSpeed = speechSpeed,
                        onStarted = { notifyStartOnce() }
                    )
                    SecurePreferences.VOICE_PROVIDER_GEMINI -> speakWithGeminiTts(
                        cleanText = cleanText,
                        geminiKey = geminiKey,
                        genId = myGenId,
                        speechSpeed = speechSpeed,
                        allowRateLimitRetry = cleanElevenKey.isBlank(),
                        onStarted = { notifyStartOnce() }
                    )
                    else -> "Unsupported voice engine"
                }

                if (err == null) {
                    spokeSuccessfully = true
                    break
                } else {
                    lastFailure = err
                    Log.w("ElevenLabsClient", "Voice engine $engine failed, checking fallback: $err")
                }
            }

            if (!spokeSuccessfully && myGenId == speechGenerationId && lastFailure != null) {
                // Always play gentle acoustic ack cue so response arrival isn't completely silent
                try { playQuickAckCue() } catch (_: Exception) {}
                didInvokeError = true
                withContext(Dispatchers.Main) {
                    onError?.invoke(lastFailure)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w("ElevenLabsClient", "Voice playback pipeline failed", e)
            if (myGenId == speechGenerationId) {
                didInvokeError = true
                val errMsg = "AI voice error: ${e.localizedMessage ?: "Playback failed"}"
                withContext(Dispatchers.Main) {
                    onError?.invoke(errMsg)
                }
            }
        } finally {
            abandonSpeechAudioFocus()
            if (!didInvokeError) {
                withContext(Dispatchers.Main) {
                    if (myGenId == speechGenerationId) {
                        onPlaybackCompleted()
                    }
                }
            }
        }
    }

    private fun isAllGeminiTtsCoolingDown(now: Long): Boolean {
        return DEFAULT_GEMINI_TTS_MODELS.all { model ->
            (modelCooldownUntilMs[model] ?: 0L) > now
        }
    }

    /**
     * Synthesizes & plays speech with ElevenLabs (with persistent disk cache). Returns null on success, or error message.
     */
    private suspend fun speakWithElevenLabs(
        cleanText: String,
        cleanKey: String,
        targetVoiceId: String,
        modelId: String,
        genId: Long,
        speechSpeed: Float,
        onStarted: () -> Unit
    ): String? {
        if (cleanKey.isBlank()) return "ElevenLabs API key missing."

        val cacheKey = cacheHash("eleven|$targetVoiceId|${cleanText.lowercase()}")
        val cachedMp3 = File(ttsDiskCacheDir, "$cacheKey.mp3")
        if (cachedMp3.exists() && cachedMp3.length() > 200L) {
            val copyForPlay = File(context.cacheDir, "myraa_play_${System.currentTimeMillis()}.mp3")
            cachedMp3.copyTo(copyForPlay, overwrite = true)
            playAudioFileSuspend(copyForPlay, genId, speechSpeed, onStarted, {})
            return null
        }

        if (System.currentTimeMillis() < elevenLabsCooldownUntilMs) {
            return "ElevenLabs rate limit (429) cooldown active — retrying shortly."
        }

        return try {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$targetVoiceId?optimize_streaming_latency=4"
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

            var httpError: String? = null
            var hitRateLimit = false
            val audioBytes = NetworkRetryHelper.withExponentialBackoff(maxAttempts = 2) {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        response.body!!.bytes()
                    } else {
                        val code = response.code
                        if (code == 429) {
                            hitRateLimit = true
                            elevenLabsCooldownUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                            httpError = "ElevenLabs rate limit (HTTP 429) — thoda ruk kar bolein ya Gemini Voice chunein."
                            null
                        } else if (code >= 500) {
                            throw java.io.IOException("Transient ElevenLabs HTTP $code")
                        } else {
                            httpError = "ElevenLabs error (HTTP $code)"
                            null
                        }
                    }
                }
            }

            if (audioBytes != null && audioBytes.size > 200 && genId == speechGenerationId) {
                if (cleanText.length <= 160) {
                    saveBytesToDiskCache(cachedMp3, audioBytes)
                }
                val playFile = File(context.cacheDir, "myraa_speech_${System.currentTimeMillis()}.mp3")
                FileOutputStream(playFile).use { it.write(audioBytes) }
                playAudioFileSuspend(playFile, genId, speechSpeed, onStarted, {})
                null
            } else {
                if (hitRateLimit) httpError else (httpError ?: "ElevenLabs returned empty audio.")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            "ElevenLabs unavailable: ${e.localizedMessage ?: "Network error"}"
        }
    }

    /**
     * Synthesizes & plays speech with Gemini Neural Voice using persistent disk + RAM cache
     * and single-call sequential playback (zero parallel burst 429s).
     */
    private suspend fun speakWithGeminiTts(
        cleanText: String,
        geminiKey: String,
        genId: Long,
        speechSpeed: Float,
        allowRateLimitRetry: Boolean,
        onStarted: () -> Unit
    ): String? {
        if (geminiKey.isBlank()) return "Gemini API key missing."
        val chunks = splitIntoFastSpeechChunks(cleanText, maxChunkChars = 600)

        var playedAnyChunk = false
        var lastErrorReason: String? = null

        suspend fun fetchChunk(chunk: String): GeminiTtsOutcome {
            loadCachedGeminiPcm(chunk)?.let { cached ->
                return GeminiTtsOutcome.Success(
                    audioBytes = cached.pcmBytes,
                    fileExtension = "pcm",
                    modelUsed = "cache",
                    isRawPcm = true,
                    sampleRate = cached.sampleRate
                )
            }
            return synthesizeWithGeminiTts(chunk, geminiKey, allowRateLimitRetry).also { res ->
                if (res is GeminiTtsOutcome.Success && res.isRawPcm && chunk.length <= MAX_CACHE_PHRASE_CHARS) {
                    saveCachedGeminiPcm(chunk, res.audioBytes, res.sampleRate)
                }
            }
        }

        for (i in chunks.indices) {
            if (genId != speechGenerationId) break

            val outcome = fetchChunk(chunks[i])
            if (genId != speechGenerationId) break

            when (outcome) {
                is GeminiTtsOutcome.Success -> {
                    playedAnyChunk = true
                    if (outcome.isRawPcm) {
                        playPcmDirectSuspend(
                            pcmData = outcome.audioBytes,
                            sampleRate = outcome.sampleRate,
                            genId = genId,
                            speechSpeed = speechSpeed,
                            onStart = onStarted,
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
                            genId = genId,
                            speechSpeed = speechSpeed,
                            onStart = onStarted,
                            onEnd = {}
                        )
                    }
                }
                is GeminiTtsOutcome.Failure -> {
                    lastErrorReason = outcome.reason
                    Log.w("ElevenLabsClient", "Gemini TTS chunk $i failed: ${outcome.reason}")
                    if (outcome.reason.contains("429") || outcome.reason.contains("busy", ignoreCase = true)) break
                }
            }
        }

        return if (playedAnyChunk || genId != speechGenerationId) {
            null
        } else {
            lastErrorReason ?: "Gemini AI voice temporarily busy."
        }
    }

    /**
     * Keeps typical replies in a single fast request (<= 140 chars) while splitting longer multi-sentence
     * answers into clean sentence groups for sequential prefetch pipelining.
     */
    internal fun splitIntoFastSpeechChunks(text: String, maxChunkChars: Int = 140): List<String> {
        val clean = text.trim()
        if (clean.length <= maxChunkChars) return listOf(clean)

        val rawSentences = clean.split(Regex("(?<=[.!?।])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawSentences.size <= 1) return listOf(clean)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in rawSentences) {
            if (current.isEmpty()) {
                current.append(sentence)
            } else if (current.length + 1 + sentence.length <= maxChunkChars) {
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
        geminiApiKey: String,
        allowRateLimitRetry: Boolean = true
    ): GeminiTtsOutcome = withContext(Dispatchers.IO) {
        val cleanKey = geminiApiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext GeminiTtsOutcome.Failure("Gemini API key is missing.")
        }

        val now = System.currentTimeMillis()
        val queue = ArrayDeque<String>()
        val attempted = mutableSetOf<String>()
        verifiedTtsModelByKey[cleanKey]?.let { if (!zeroQuotaModels.contains(it)) queue.add(it) }
        discoveredTtsModelsCache[cleanKey]?.forEach {
            if (!zeroQuotaModels.contains(it) && !queue.contains(it)) queue.add(it)
        }
        DEFAULT_GEMINI_TTS_MODELS.forEach {
            if (!zeroQuotaModels.contains(it) && !queue.contains(it)) queue.add(it)
        }
        if (queue.isEmpty()) {
            // Re-seed primary flash TTS model if cooldown cache emptied everything
            queue.add(DEFAULT_GEMINI_TTS_MODELS.first())
        }

        var didDiscovery = !discoveredTtsModelsCache[cleanKey].isNullOrEmpty()
        var lastError = "Gemini AI Voice temporarily busy."
        var sawRateLimit = false
        val spokenDirectiveText = text.trim()

        while (queue.isNotEmpty()) {
            val model = queue.removeFirst()
            if (!attempted.add(model)) continue

            val cooldownUntil = modelCooldownUntilMs[model] ?: 0L
            val remainingCooldown = cooldownUntil - System.currentTimeMillis()
            if (remainingCooldown > 0L) {
                if (remainingCooldown <= 2_600L && queue.isEmpty()) {
                    // Brief wait for primary flash TTS cooldown instead of silently aborting
                    delay(remainingCooldown.coerceAtMost(2_200L))
                } else {
                    sawRateLimit = true
                    continue
                }
            }

            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", spokenDirectiveText))
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

                var code = 0
                var respBody = ""
                var retryAfterMs = 1400L
                val maxCalls = if (allowRateLimitRetry) 2 else 1

                for (callAttempt in 1..maxCalls) {
                    val outcome = NetworkRetryHelper.withExponentialBackoff(maxAttempts = 2) {
                        client.newCall(request).execute().use { resp ->
                            val ra = resp.header("Retry-After")?.toLongOrNull()?.times(1000L)
                            Triple(resp.code, resp.body?.string() ?: "", ra)
                        }
                    }
                    code = outcome.first
                    respBody = outcome.second
                    val lowerErr = respBody.lowercase()
                    val isZeroQuota = lowerErr.contains("limit: 0") || lowerErr.contains("limit:0")
                    val bodyRetryMs = Regex("retry in\\s+([0-9]+(?:\\.[0-9]+)?)s", RegexOption.IGNORE_CASE)
                        .find(respBody)?.groupValues?.get(1)?.toDoubleOrNull()?.times(1000.0)?.toLong()
                    val resolvedDelay = bodyRetryMs ?: outcome.third
                    if (resolvedDelay != null) {
                        retryAfterMs = resolvedDelay.coerceIn(700L, 2400L)
                    }
                    if (isZeroQuota) {
                        zeroQuotaModels.add(model)
                    }
                    // On primary Flash TTS transient 429, recover smoothly once before falling through
                    if (code != 429 || isZeroQuota || callAttempt == maxCalls) break
                    delay(retryAfterMs)
                }

                if (code in 200..299 && respBody.isNotBlank()) {
                    val extracted = extractAudioFromGeminiResponse(respBody)
                    if (extracted != null) {
                        verifiedTtsModelByKey[cleanKey] = model
                        modelCooldownUntilMs.remove(model)
                        zeroQuotaModels.remove(model)
                        return@withContext extracted.copy(modelUsed = model)
                    } else {
                        lastError = "Gemini TTS ($model) returned no audio bytes."
                    }
                } else if (code == 429) {
                    sawRateLimit = true
                    val isZeroQuota = respBody.lowercase().let { it.contains("limit: 0") || it.contains("limit:0") }
                    val cooldownDuration = if (isZeroQuota) ZERO_QUOTA_COOLDOWN_MS else RATE_LIMIT_COOLDOWN_MS
                    modelCooldownUntilMs[model] = System.currentTimeMillis() + cooldownDuration
                    lastError = "Gemini Voice abhi busy hai (rate limit) — 2 second baad dobara bolein."
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

        if (sawRateLimit) {
            GeminiTtsOutcome.Failure("Gemini Voice abhi busy hai (429) — 2-3 second baad phir try karein.")
        } else {
            GeminiTtsOutcome.Failure(lastError)
        }
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
                return GeminiTtsOutcome.Success(rawBytes, "pcm", "", isRawPcm = true, sampleRate = sampleRate)
            }
            null
        } catch (e: Exception) {
            Log.w("ElevenLabsClient", "Failed to parse Gemini TTS audio response", e)
            null
        }
    }

    private fun loadCachedGeminiPcm(text: String): CachedPcmAudio? {
        val memKey = "${GEMINI_VOICE_MAHI}:${text.lowercase()}"
        phrasePcmCache[memKey]?.let { return it }

        val hash = cacheHash("gemini|$GEMINI_VOICE_MAHI|${text.lowercase()}")
        val diskFile = ttsDiskCacheDir.listFiles { _, name -> name.startsWith("${hash}_") && name.endsWith(".pcm") }
            ?.firstOrNull() ?: return null
        return try {
            val rateStr = diskFile.name.substringAfterLast('_').substringBefore(".pcm")
            val rate = rateStr.toIntOrNull() ?: 24000
            val bytes = diskFile.readBytes()
            if (bytes.size > 64) {
                diskFile.setLastModified(System.currentTimeMillis())
                CachedPcmAudio(bytes, rate).also {
                    if (phrasePcmCache.size > 64) phrasePcmCache.clear()
                    phrasePcmCache[memKey] = it
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCachedGeminiPcm(text: String, pcmBytes: ByteArray, sampleRate: Int) {
        if (pcmBytes.size < 64) return
        val memKey = "${GEMINI_VOICE_MAHI}:${text.lowercase()}"
        if (phrasePcmCache.size > 64) phrasePcmCache.clear()
        phrasePcmCache[memKey] = CachedPcmAudio(pcmBytes, sampleRate)

        val hash = cacheHash("gemini|$GEMINI_VOICE_MAHI|${text.lowercase()}")
        val outFile = File(ttsDiskCacheDir, "${hash}_${sampleRate}.pcm")
        saveBytesToDiskCache(outFile, pcmBytes)
    }

    private fun saveBytesToDiskCache(target: File, data: ByteArray) {
        try {
            FileOutputStream(target).use { it.write(data) }
            val all = ttsDiskCacheDir.listFiles() ?: return
            if (all.size > MAX_DISK_CACHE_FILES) {
                all.sortedBy { it.lastModified() }
                    .take(all.size - MAX_DISK_CACHE_FILES)
                    .forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    private fun cacheHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun requestSpeechAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Exception) {}
    }

    private fun abandonSpeechAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    private suspend fun playPcmDirectSuspend(
        pcmData: ByteArray,
        sampleRate: Int,
        genId: Long,
        speechSpeed: Float,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val validLength = pcmData.size and -2
        if (validLength <= 0 || genId != speechGenerationId) {
            onEnd()
            return@withContext
        }

        var track: AudioTrack? = null
        var fallbackToWav = false
        val safeSpeed = speechSpeed.coerceIn(0.85f, 1.4f)

        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                fallbackToWav = true
            } else {
                val streamBufSize = maxOf(minBuf * 2, 16384)
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
                    fallbackToWav = genId == speechGenerationId
                } else {
                    activeAudioTrack = track
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && safeSpeed != 1.0f) {
                        try {
                            track.playbackParams = PlaybackParams().setSpeed(safeSpeed)
                        } catch (_: Exception) {}
                    }
                    val initialWrite = minOf(streamBufSize, validLength)
                    var offset = 0
                    if (initialWrite > 0) {
                        val preWritten = track.write(pcmData, 0, initialWrite)
                        if (preWritten > 0) {
                            offset = preWritten
                        }
                    }

                    val startedAt = System.currentTimeMillis()
                    onStart()
                    track.setVolume(1.0f)
                    track.play()

                    val writeChunk = 8192
                    while (offset < validLength && isActive && genId == speechGenerationId) {
                        val toWrite = minOf(writeChunk, validLength - offset)
                        val written = track.write(pcmData, offset, toWrite)
                        if (written <= 0) {
                            if (offset == 0) fallbackToWav = true
                            break
                        }
                        offset += written
                    }

                    if (!fallbackToWav && genId == speechGenerationId && isActive) {
                        val totalAudioMs = ((validLength * 1000L) / (sampleRate * 2L) / safeSpeed).toLong()
                        val elapsed = System.currentTimeMillis() - startedAt
                        val remaining = (totalAudioMs - elapsed).coerceAtLeast(0L)
                        delay(remaining + 180L)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e("ElevenLabsClient", "AudioTrack playback error, trying WAV fallback", e)
            fallbackToWav = genId == speechGenerationId
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
        }

        if (fallbackToWav && genId == speechGenerationId) {
            val wavFile = File(context.cacheDir, "myraa_pcm_fallback_${System.currentTimeMillis()}.wav")
            try {
                writePcm16AsWavFile(pcmData, validLength, sampleRate, wavFile)
                playAudioFileSuspend(wavFile, genId, speechSpeed, onStart, onEnd)
                return@withContext
            } catch (ce: CancellationException) {
                try { wavFile.delete() } catch (_: Exception) {}
                throw ce
            } catch (e: Exception) {
                Log.e("ElevenLabsClient", "WAV fallback playback failed", e)
                try { wavFile.delete() } catch (_: Exception) {}
            }
        }

        onEnd()
    }

    private fun writePcm16AsWavFile(pcmData: ByteArray, dataLength: Int, sampleRate: Int, outFile: File) {
        val byteRate = sampleRate * 1 * 16 / 8
        val chunkSize = 36 + dataLength
        FileOutputStream(outFile).use { out ->
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
            writeIntLE(16)
            writeShortLE(1)
            writeShortLE(1)
            writeIntLE(sampleRate)
            writeIntLE(byteRate)
            writeShortLE(2)
            writeShortLE(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(dataLength)
            out.write(pcmData, 0, dataLength)
        }
    }

    private suspend fun playAudioFileSuspend(
        file: File,
        genId: Long,
        speechSpeed: Float,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) = suspendCancellableCoroutine<Unit> { cont ->
        cont.invokeOnCancellation {
            mainHandler.post {
                releaseMediaPlayerOnly()
                try { file.delete() } catch (_: Exception) {}
            }
        }
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
                isMediaPlayerPrepared = false
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
                    if (genId != speechGenerationId || activeMediaPlayer !== preparedMp) {
                        isMediaPlayerPrepared = false
                        try {
                            preparedMp.setOnPreparedListener(null)
                            preparedMp.setOnCompletionListener(null)
                            preparedMp.setOnErrorListener(null)
                            preparedMp.release()
                        } catch (_: Exception) {}
                        if (activeMediaPlayer == preparedMp) activeMediaPlayer = null
                        safeFinish()
                        return@setOnPreparedListener
                    }
                    isMediaPlayerPrepared = true
                    onStart()
                    preparedMp.start()

                    val safeSpeed = speechSpeed.coerceIn(0.85f, 1.4f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && safeSpeed != 1.0f) {
                        try {
                            preparedMp.playbackParams = preparedMp.playbackParams.setSpeed(safeSpeed)
                        } catch (_: Exception) {}
                    }

                    val duration = try { preparedMp.duration } catch (_: Exception) { 0 }
                    val timeout = if (duration > 0) (duration / safeSpeed).toLong() + 1500L else 12000L
                    mainHandler.postDelayed({ safeFinish() }, timeout)
                }
                mp.setOnCompletionListener { completedMp ->
                    isMediaPlayerPrepared = false
                    try {
                        completedMp.setOnPreparedListener(null)
                        completedMp.setOnCompletionListener(null)
                        completedMp.setOnErrorListener(null)
                        completedMp.release()
                    } catch (_: Exception) {}
                    if (activeMediaPlayer == completedMp) activeMediaPlayer = null
                    safeFinish()
                }
                mp.setOnErrorListener { errorMp, what, extra ->
                    isMediaPlayerPrepared = false
                    Log.e("ElevenLabsClient", "MediaPlayer error: what=$what, extra=$extra")
                    try {
                        errorMp.setOnPreparedListener(null)
                        errorMp.setOnCompletionListener(null)
                        errorMp.setOnErrorListener(null)
                        errorMp.release()
                    } catch (_: Exception) {}
                    if (activeMediaPlayer == errorMp) activeMediaPlayer = null
                    safeFinish()
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                isMediaPlayerPrepared = false
                Log.e("ElevenLabsClient", "Failed to play audio file", e)
                safeFinish()
            }
        }
    }

    private fun releaseMediaPlayerOnly() {
        val wasPrepared = isMediaPlayerPrepared
        isMediaPlayerPrepared = false
        try {
            activeMediaPlayer?.let { mp ->
                mp.setOnPreparedListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                if (wasPrepared) {
                    try {
                        if (mp.isPlaying) mp.stop()
                    } catch (_: Exception) {}
                }
                mp.release()
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
        abandonSpeechAudioFocus()
    }

    fun shutdown() {
        stopSpeaking()
        clientScope.cancel()
    }
}
