package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

enum class CompanionState {
    IDLE,
    LISTENING,
    THINKING,
    TALKING,
    ERROR
}

/**
 * Direct Audio Voice Capture Manager (NO Android SpeechRecognizer / Speech-to-Text).
 * Captures 16kHz 16-bit mono PCM directly from the microphone with Voice Activity Detection (VAD),
 * wraps detected speech into a clean WAV payload, and sends raw audio straight to Gemini Multimodal AI.
 */
class VoiceRecognitionManager(
    private val context: Context,
    private val onAudioCaptured: (ByteArray) -> Unit
) {
    var isSessionActive = false
        private set
    var isPaused = false
        private set

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    @Volatile
    private var activeAudioRecord: AudioRecord? = null

    @Volatile
    private var forceFinishUtterance = false

    private val _companionState = MutableStateFlow(CompanionState.IDLE)
    val companionState: StateFlow<CompanionState> = _companionState.asStateFlow()

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    companion object {
        private const val SAMPLE_RATE_HZ = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val CHUNK_SHORTS = 800 // 50ms per chunk at 16kHz for snappy VAD
        private const val PRE_ROLL_CHUNKS = 6 // ~300ms pre-roll before speech trigger
        private const val SPEECH_RMS_THRESHOLD = 0.018
        private const val SILENCE_END_MS = 380L
        private const val MIN_SPEECH_MS = 200L
        private const val MAX_UTTERANCE_MS = 12000L
        private const val IDLE_WINDOW_MS = 20000L
        private const val MIN_FORCED_BYTES = 4800 // ~150ms
        private const val TAIL_SILENCE_KEEP_BYTES = 640 * 2 // ~40ms tail
    }

    fun prepareSessionForGreeting() {
        isSessionActive = true
        isPaused = false
        forceFinishUtterance = false
        _errorMessage.value = null
        _lastRecognizedText.value = ""
        _companionState.value = CompanionState.TALKING
    }

    fun startContinuousListening() {
        if (!hasMicPermission()) {
            handlePermissionRevoked()
            return
        }
        isSessionActive = true
        isPaused = false
        forceFinishUtterance = false
        _errorMessage.value = null
        launchCaptureLoop()
    }

    fun pauseListening() {
        isPaused = true
        forceFinishUtterance = false
        stopCaptureLoop()
        _companionState.value = CompanionState.IDLE
        _rmsLevel.value = 0f
        _lastRecognizedText.value = ""
    }

    fun resumeListening() {
        if (!isSessionActive) {
            startContinuousListening()
            return
        }
        if (!hasMicPermission()) {
            handlePermissionRevoked()
            return
        }
        isPaused = false
        forceFinishUtterance = false
        _errorMessage.value = null
        launchCaptureLoop()
    }

    fun stopContinuousListening() {
        isSessionActive = false
        isPaused = false
        forceFinishUtterance = false
        stopCaptureLoop()
        _companionState.value = CompanionState.IDLE
        _rmsLevel.value = 0f
        _lastRecognizedText.value = ""
    }

    fun setCompanionState(state: CompanionState) {
        if (state == CompanionState.THINKING || state == CompanionState.TALKING) {
            stopCaptureLoop()
        }
        _companionState.value = state
        if (state == CompanionState.IDLE || state == CompanionState.ERROR || state == CompanionState.THINKING || state == CompanionState.TALKING) {
            _rmsLevel.value = 0f
        }
    }

    fun suspendListeningForProcessing(state: CompanionState) {
        forceFinishUtterance = false
        stopCaptureLoop()
        _rmsLevel.value = 0f
        _companionState.value = state
    }

    fun resumeListeningAfterSpeech() {
        if (!isSessionActive || isPaused) {
            _companionState.value = CompanionState.IDLE
            return
        }
        if (!hasMicPermission()) {
            handlePermissionRevoked()
            return
        }
        _companionState.value = CompanionState.LISTENING
        launchCaptureLoop()
    }

    fun finishUtteranceNow() {
        if (isSessionActive && !isPaused && _companionState.value == CompanionState.LISTENING) {
            forceFinishUtterance = true
        }
    }

    fun handlePermissionRevoked() {
        isSessionActive = false
        isPaused = false
        forceFinishUtterance = false
        stopCaptureLoop()
        _rmsLevel.value = 0f
        _lastRecognizedText.value = ""
        _errorMessage.value = "Microphone permission is required for direct voice listening."
        _companionState.value = CompanionState.ERROR
    }

    private fun hasMicPermission(): Boolean {
        return try {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun launchCaptureLoop() {
        stopCaptureLoop()
        if (!isSessionActive || isPaused) return

        captureJob = managerScope.launch {
            recordSingleUtteranceOrContinue()
        }
    }

    private fun stopCaptureLoop() {
        captureJob?.cancel()
        captureJob = null
        releaseAudioRecordSafely(activeAudioRecord)
        activeAudioRecord = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordSingleUtteranceOrContinue() {
        if (!isSessionActive || isPaused || !hasMicPermission()) {
            if (!hasMicPermission() && isSessionActive) handlePermissionRevoked()
            return
        }

        val minBuf = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Exception) {
            -1
        }
        if (minBuf <= 0) {
            _errorMessage.value = "Microphone hardware buffer unavailable on this device."
            _companionState.value = CompanionState.ERROR
            return
        }

        val bufferBytes = maxOf(minBuf, CHUNK_SHORTS * 2 * 2)
        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                releaseAudioRecordSafely(recorder)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                releaseAudioRecordSafely(recorder)
                _errorMessage.value = "Unable to initialize direct microphone stream."
                _companionState.value = CompanionState.ERROR
                return
            }

            activeAudioRecord = recorder
            recorder.startRecording()
            _companionState.value = CompanionState.LISTENING
            _errorMessage.value = null
            _lastRecognizedText.value = "Listening directly (speak naturally)..."

            val shortBuf = ShortArray(CHUNK_SHORTS)
            val preRoll = ArrayDeque<ByteArray>()
            val speechStream = ByteArrayOutputStream()

            val windowStartedAt = System.currentTimeMillis()
            var speechStartedAt = 0L
            var lastVoiceActivityAt = 0L
            var lastVoicedByteIndex = 0
            var hasSpeechStarted = false
            var capturedWav: ByteArray? = null

            while (managerScope.isActive && isSessionActive && !isPaused) {
                val readCount = recorder.read(shortBuf, 0, shortBuf.size)
                if (readCount <= 0) continue

                val chunkBytes = shortsToLittleEndianBytes(shortBuf, readCount)
                val rms = calculateNormalizedRms(shortBuf, readCount)
                _rmsLevel.value = (rms * 8.0).toFloat().coerceIn(0f, 1f)

                val now = System.currentTimeMillis()

                if (!hasSpeechStarted) {
                    if (preRoll.size >= PRE_ROLL_CHUNKS) preRoll.removeFirst()
                    preRoll.addLast(chunkBytes)

                    if (rms >= SPEECH_RMS_THRESHOLD) {
                        hasSpeechStarted = true
                        speechStartedAt = now
                        lastVoiceActivityAt = now
                        _lastRecognizedText.value = "Hearing your voice..."
                        while (preRoll.isNotEmpty()) {
                            speechStream.write(preRoll.removeFirst())
                        }
                        lastVoicedByteIndex = speechStream.size()
                    } else if (forceFinishUtterance) {
                        preRoll.forEach { speechStream.write(it) }
                        if (speechStream.size() >= MIN_FORCED_BYTES) {
                            capturedWav = wrapPcm16AsWav(speechStream.toByteArray())
                        }
                        forceFinishUtterance = false
                        break
                    } else if (now - windowStartedAt >= IDLE_WINDOW_MS) {
                        // Silence timeout: restart clean listening window silently
                        break
                    }
                } else {
                    speechStream.write(chunkBytes)
                    if (rms >= SPEECH_RMS_THRESHOLD * 0.8) {
                        lastVoiceActivityAt = now
                        lastVoicedByteIndex = speechStream.size()
                    }

                    val speechElapsed = now - speechStartedAt
                    val silenceElapsed = now - lastVoiceActivityAt
                    val shouldFinish = (silenceElapsed >= SILENCE_END_MS && speechElapsed >= MIN_SPEECH_MS) ||
                        speechElapsed >= MAX_UTTERANCE_MS ||
                        (forceFinishUtterance && speechStream.size() >= MIN_FORCED_BYTES)

                    if (shouldFinish) {
                        val rawPcm = speechStream.toByteArray()
                        val trimmedLen = if (lastVoicedByteIndex > 0) {
                            minOf(rawPcm.size, lastVoicedByteIndex + TAIL_SILENCE_KEEP_BYTES) and -2
                        } else {
                            rawPcm.size
                        }
                        val payloadPcm = if (trimmedLen in MIN_FORCED_BYTES until rawPcm.size) {
                            rawPcm.copyOf(trimmedLen)
                        } else {
                            rawPcm
                        }
                        capturedWav = wrapPcm16AsWav(payloadPcm)
                        break
                    }
                }
            }

            forceFinishUtterance = false
            releaseAudioRecordSafely(recorder)
            if (activeAudioRecord == recorder) activeAudioRecord = null

            val wavReady = capturedWav
            if (wavReady != null && isSessionActive && !isPaused) {
                _rmsLevel.value = 0f
                _companionState.value = CompanionState.THINKING
                _lastRecognizedText.value = "Sending voice directly to MYRAA..."
                withContext(Dispatchers.Main) {
                    onAudioCaptured(wavReady)
                }
            } else if (isSessionActive && !isPaused && _companionState.value == CompanionState.LISTENING) {
                // Silent window expired without speech — seamlessly start next listening window
                _rmsLevel.value = 0f
                launchCaptureLoop()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            releaseAudioRecordSafely(recorder)
            throw ce
        } catch (e: Exception) {
            Log.e("VoiceRecognition", "Direct AudioRecord error", e)
            releaseAudioRecordSafely(recorder)
            if (activeAudioRecord == recorder) activeAudioRecord = null
            _rmsLevel.value = 0f
            _errorMessage.value = "Direct voice capture error: ${e.localizedMessage ?: "Mic unavailable"}"
            _companionState.value = CompanionState.ERROR
        }
    }

    private fun releaseAudioRecordSafely(recorder: AudioRecord?) {
        if (recorder == null) return
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: Exception) {}
        try {
            recorder.release()
        } catch (_: Exception) {}
    }

    private fun calculateNormalizedRms(samples: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / count) / 32768.0
    }

    private fun shortsToLittleEndianBytes(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun wrapPcm16AsWav(pcmData: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE_HZ * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
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
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(CHANNELS)
        writeIntLE(SAMPLE_RATE_HZ)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(BITS_PER_SAMPLE)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(dataSize)
        out.write(pcmData)
        return out.toByteArray()
    }

    fun destroy() {
        stopContinuousListening()
        managerScope.cancel()
    }
}
