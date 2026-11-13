package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class CompanionState {
    IDLE,
    LISTENING,
    THINKING,
    TALKING
}

class VoiceRecognitionManager(
    private val context: Context,
    private val onFallbackIntentNeeded: (() -> Unit)? = null,
    private val onCommandReceived: (String) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    var isSessionActive = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _companionState = MutableStateFlow(CompanionState.IDLE)
    val companionState: StateFlow<CompanionState> = _companionState.asStateFlow()

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Supported language mode: "auto" (Hindi + English), "hi-IN" (Hindi), "en-US" (English)
    var languageMode: String = "auto"

    fun startContinuousListening() {
        isSessionActive = true
        _errorMessage.value = null
        restartListeningInternal()
    }

    fun stopContinuousListening() {
        isSessionActive = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("VoiceRecognition", "Error stopping recognizer", e)
        }
        speechRecognizer = null
        _companionState.value = CompanionState.IDLE
        _rmsLevel.value = 0f
    }

    fun setCompanionState(state: CompanionState) {
        _companionState.value = state
        if (state == CompanionState.IDLE) {
            _rmsLevel.value = 0f
        }
    }

    // Called as soon as Myraa completes speaking to immediately resume listening for next command!
    fun resumeListeningAfterSpeech() {
        if (!isSessionActive) return
        mainHandler.postDelayed({
            if (isSessionActive) {
                _companionState.value = CompanionState.LISTENING
                restartListeningInternal()
            }
        }, 150)
    }

    fun restartListeningInternal() {
        if (!isSessionActive) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("VoiceRecognition", "Speech recognition service not available on device, triggering fallback")
            _errorMessage.value = "Speech service missing - opening voice dialog"
            onFallbackIntentNeeded?.invoke()
            return
        }

        mainHandler.post {
            try {
                // Cancel pending recognition session to avoid ERROR_RECOGNIZER_BUSY (code 8)
                try {
                    speechRecognizer?.cancel()
                } catch (ignored: Exception) {}

                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(this@VoiceRecognitionManager)
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                    when (languageMode) {
                        "hi-IN" -> {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                        }
                        "en-US" -> {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                        }
                        else -> {
                            // Default Auto: Multilingual Hindi + English (Hinglish)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US"))
                        }
                    }

                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                }

                speechRecognizer?.startListening(intent)
                _companionState.value = CompanionState.LISTENING
            } catch (e: Exception) {
                Log.e("VoiceRecognition", "Failed to start speech listener", e)
                _errorMessage.value = "Speech listener error: ${e.message}"

                try {
                    speechRecognizer?.destroy()
                } catch (ignored: Exception) {}
                speechRecognizer = null

                // If continuous mode is on, attempt retry or trigger system prompt
                mainHandler.postDelayed({
                    if (isSessionActive && _companionState.value != CompanionState.TALKING) {
                        restartListeningInternal()
                    }
                }, 700)
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (isSessionActive && _companionState.value != CompanionState.TALKING) {
            _companionState.value = CompanionState.LISTENING
        }
    }

    override fun onBeginningOfSpeech() {
        if (isSessionActive && _companionState.value != CompanionState.TALKING) {
            _companionState.value = CompanionState.LISTENING
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        val normalized = (rmsdB + 2f) / 12f
        _rmsLevel.value = normalized.coerceIn(0f, 1f)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        if (isSessionActive && _companionState.value != CompanionState.TALKING) {
            _companionState.value = CompanionState.THINKING
        }
    }

    override fun onError(error: Int) {
        Log.d("VoiceRecognition", "onError code: $error")
        // If recognizer is busy or client failed, recreate it
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || 
            error == SpeechRecognizer.ERROR_CLIENT || 
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (ignored: Exception) {}
            speechRecognizer = null
        }

        // On no-match or timeout, seamlessly continue listening without bothering user
        if (isSessionActive) {
            mainHandler.postDelayed({
                if (isSessionActive && _companionState.value != CompanionState.TALKING) {
                    restartListeningInternal()
                }
            }, 350)
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim() ?: ""
        if (text.isNotBlank()) {
            _lastRecognizedText.value = text
            _companionState.value = CompanionState.THINKING
            onCommandReceived(text)
        } else {
            if (isSessionActive && _companionState.value != CompanionState.TALKING) {
                restartListeningInternal()
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim() ?: ""
        if (text.isNotBlank()) {
            _lastRecognizedText.value = text
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    fun destroy() {
        stopContinuousListening()
    }
}
