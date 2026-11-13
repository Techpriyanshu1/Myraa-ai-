package com.example.data.api

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class VoiceTestResult {
    data class Success(val message: String, val audioBytes: Int) : VoiceTestResult()
    data class Failure(val errorMessage: String, val isConfigError: Boolean = false) : VoiceTestResult()
}

class ElevenLabsClient(private val context: Context) : TextToSpeech.OnInitListener {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var localTts: TextToSpeech? = null
    private var isLocalTtsReady = false
    private var activeMediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val DEFAULT_MAHI_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
        const val MODEL_ELEVEN_MULTILINGUAL_V2 = "eleven_multilingual_v2"
        const val MODEL_ELEVEN_FLASH_V2_5 = "eleven_flash_v2_5"
    }

    init {
        try {
            localTts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("ElevenLabsClient", "Local TTS init error", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTts?.let { tts ->
                tts.setPitch(1.05f)
                tts.setSpeechRate(1.0f)
                isLocalTtsReady = true
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

        if (cleanKey.isBlank()) {
            return@withContext VoiceTestResult.Failure("Voice API key is missing. Please enter your ElevenLabs API key.", true)
        }
        if (cleanVoiceId.isBlank()) {
            return@withContext VoiceTestResult.Failure("Voice ID is empty. Please enter or confirm the Mahi Voice ID.", true)
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
        allowNativeFallback: Boolean = true,
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

        var playedViaElevenLabs = false
        val cleanKey = apiKey.trim()
        val targetVoiceId = if (voiceId.isNotBlank()) voiceId.trim() else DEFAULT_MAHI_VOICE_ID

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

                val response = client.newCall(request).execute()
                if (response.isSuccessful && response.body != null) {
                    val cacheFile = File(context.cacheDir, "myraa_speech_${System.currentTimeMillis()}.mp3")
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (cacheFile.exists() && cacheFile.length() > 200) {
                        withContext(Dispatchers.Main) {
                            playAudioFile(cacheFile, onPlaybackStarted, onPlaybackCompleted)
                        }
                        playedViaElevenLabs = true
                        Log.i("ElevenLabsClient", "Mahi voice synthesized successfully: ${cacheFile.length()} bytes")
                    }
                } else {
                    val err = response.body?.string() ?: ""
                    Log.w("ElevenLabsClient", "ElevenLabs failed HTTP ${response.code}: $err")
                    onError?.invoke("ElevenLabs error (HTTP ${response.code})")
                }
            } catch (e: Exception) {
                Log.w("ElevenLabsClient", "Error calling ElevenLabs API for $targetVoiceId", e)
                onError?.invoke(e.localizedMessage ?: "Voice network error")
            }
        }

        if (!playedViaElevenLabs) {
            if (allowNativeFallback) {
                Log.i("ElevenLabsClient", "Using native Android TTS fallback")
                withContext(Dispatchers.Main) {
                    playFallbackTts(cleanText, speechSpeed, onPlaybackStarted, onPlaybackCompleted)
                }
            } else {
                Log.w("ElevenLabsClient", "ElevenLabs failed and native fallback is disabled by user.")
                withContext(Dispatchers.Main) {
                    onPlaybackCompleted()
                }
            }
        }
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

    private fun playFallbackTts(
        text: String,
        speechSpeed: Float,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) {
        onStart()
        var completedCalled = false
        fun safeOnEnd() {
            if (!completedCalled) {
                completedCalled = true
                onEnd()
            }
        }

        if (localTts != null && isLocalTtsReady) {
            val containsHindi = text.any { it in '\u0900'..'\u097F' } || isLikelyHindiText(text)
            try {
                if (containsHindi) {
                    val hiLocale = Locale("hi", "IN")
                    val avail = localTts?.isLanguageAvailable(hiLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (avail >= TextToSpeech.LANG_AVAILABLE) {
                        localTts?.language = hiLocale
                    } else {
                        localTts?.language = Locale("en", "IN")
                    }
                } else {
                    localTts?.language = Locale("en", "IN")
                }
                localTts?.setPitch(1.05f)
                localTts?.setSpeechRate(speechSpeed)
            } catch (e: Exception) {
                Log.w("ElevenLabsClient", "TTS language setup error", e)
            }

            val utteranceId = "myraa_tts_${System.currentTimeMillis()}"
            localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.post { safeOnEnd() }
                }
                override fun onError(utteranceId: String?) {
                    mainHandler.post { safeOnEnd() }
                }
            })

            val queueResult = localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queueResult != TextToSpeech.SUCCESS) {
                safeOnEnd()
            } else {
                val wordCount = text.split("\\s+".toRegex()).size
                val timeout = (wordCount * 500L + 2500L).coerceIn(3000L, 20000L)
                mainHandler.postDelayed({ safeOnEnd() }, timeout)
            }
        } else {
            safeOnEnd()
        }
    }

    private fun isLikelyHindiText(text: String): Boolean {
        val hindiKeywords = listOf("main", "hoon", "aap", "kaise", "kya", "theek", "khush", "pyaar", "din", "swagat", "shukriya", "ji")
        val lower = text.lowercase()
        return hindiKeywords.any { lower.contains(it) }
    }

    fun stopSpeaking() {
        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            activeMediaPlayer = null
            localTts?.stop()
        } catch (e: Exception) {
            Log.e("ElevenLabsClient", "Error stopping audio", e)
        }
    }

    fun shutdown() {
        stopSpeaking()
        localTts?.shutdown()
        localTts = null
    }
}
