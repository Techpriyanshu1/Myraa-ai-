package com.example.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import android.util.Log
import com.example.agents.AgentFleetManager
import com.example.agents.InitializationPreflightReport
import com.example.agents.InitializationVerifier
import com.example.automation.ActionSecurityPolicy
import com.example.automation.DeviceActionExecutor
import com.example.automation.ExecutionResult
import com.example.automation.LaunchableAppInfo
import com.example.data.api.ElevenLabsClient
import com.example.data.api.GeminiClient
import com.example.data.api.ParsedCommand
import com.example.data.db.AppDatabase
import com.example.data.pref.SecurePreferences
import com.example.memory.MemoryCoreManager
import com.example.service.ContinuousVoiceService
import com.example.service.DigitalMouseService
import com.example.service.MyraaAccessibilityService
import com.example.service.MyraaOverlayService
import com.example.service.RealScreenCaptureService
import com.example.ui.components.PendingConfirmationAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Persistent application-level orchestrator for MYRAA AI's voice, Gemini reasoning,
 * AI TTS playback, and device automation pipeline.
 *
 * Lives beyond Activity/Composable lifecycles so Voice Mode, Continuous Listening,
 * and Screen Share automation remain active when the user switches to YouTube or other apps.
 */
class MyraaSessionCoordinator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs = SecurePreferences(appContext)
    val geminiClient = GeminiClient()
    val elevenLabsClient = ElevenLabsClient(appContext)
    val actionExecutor = DeviceActionExecutor(appContext)
    val fleetManager = AgentFleetManager.getInstance(appContext, prefs)
    val initVerifier = InitializationVerifier(appContext, prefs, geminiClient, fleetManager)
    val memoryManager = MemoryCoreManager(AppDatabase.getInstance(appContext))

    val voiceManager: VoiceRecognitionManager = VoiceRecognitionManager(
        context = appContext,
        onAudioCaptured = { wavBytes ->
            fleetManager.clearAgentError("voice_recognition")
            processVoiceAudio(wavBytes)
        }
    )

    val companionState: StateFlow<CompanionState> = voiceManager.companionState
    val rmsLevel: StateFlow<Float> = voiceManager.rmsLevel
    val liveVoiceStatus: StateFlow<String> = voiceManager.lastRecognizedText
    val voiceError: StateFlow<String?> = voiceManager.errorMessage

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _isListeningPaused = MutableStateFlow(false)
    val isListeningPaused: StateFlow<Boolean> = _isListeningPaused.asStateFlow()

    private val _isStartingSession = MutableStateFlow(false)
    val isStartingSession: StateFlow<Boolean> = _isStartingSession.asStateFlow()

    private val _currentSpeechTranscript = MutableStateFlow("")
    val currentSpeechTranscript: StateFlow<String> = _currentSpeechTranscript.asStateFlow()

    private val _currentAiReply = MutableStateFlow(
        "Namaste! Main MYRAA AI hoon. Mic button dabakar seedhe apni awaaz me baat karein."
    )
    val currentAiReply: StateFlow<String> = _currentAiReply.asStateFlow()

    private val _lastExecutedAction = MutableStateFlow<String?>(null)
    val lastExecutedAction: StateFlow<String?> = _lastExecutedAction.asStateFlow()

    private val _lastActionSuccess = MutableStateFlow(true)
    val lastActionSuccess: StateFlow<Boolean> = _lastActionSuccess.asStateFlow()

    private val _showAppsDialog = MutableStateFlow(false)
    val showAppsDialog: StateFlow<Boolean> = _showAppsDialog.asStateFlow()

    private val _launchableApps = MutableStateFlow<List<LaunchableAppInfo>>(emptyList())
    val launchableApps: StateFlow<List<LaunchableAppInfo>> = _launchableApps.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmationAction?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmationAction?> = _pendingConfirmation.asStateFlow()

    private val _preflightReportDialog = MutableStateFlow<InitializationPreflightReport?>(null)
    val preflightReportDialog: StateFlow<InitializationPreflightReport?> = _preflightReportDialog.asStateFlow()

    private val _navigateToLogsEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToLogsEvent: SharedFlow<Unit> = _navigateToLogsEvent.asSharedFlow()

    private val _navigateToWebBuilderEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToWebBuilderEvent: SharedFlow<String> = _navigateToWebBuilderEvent.asSharedFlow()

    private val _speakerVerificationState = MutableStateFlow<SpeakerVerificationResult?>(null)
    val speakerVerificationState: StateFlow<SpeakerVerificationResult?> = _speakerVerificationState.asStateFlow()

    private val _isBossVoiceEnrolled = MutableStateFlow(prefs.hasBossVoiceprint())
    val isBossVoiceEnrolled: StateFlow<Boolean> = _isBossVoiceEnrolled.asStateFlow()

    private val _isBossVoiceGuardEnabled = MutableStateFlow(prefs.isBossVoiceGuardEnabled())
    val isBossVoiceGuardEnabled: StateFlow<Boolean> = _isBossVoiceGuardEnabled.asStateFlow()

    private val _isEnrollingBossVoice = MutableStateFlow(false)
    val isEnrollingBossVoice: StateFlow<Boolean> = _isEnrollingBossVoice.asStateFlow()

    private val _bossEnrollmentStatus = MutableStateFlow<String?>(null)
    val bossEnrollmentStatus: StateFlow<String?> = _bossEnrollmentStatus.asStateFlow()

    private val _voiceProviderMode = MutableStateFlow(prefs.getVoiceProviderMode())
    val voiceProviderMode: StateFlow<String> = _voiceProviderMode.asStateFlow()

    private val _effectiveVoiceProvider = MutableStateFlow(prefs.getEffectiveVoiceProvider())
    val effectiveVoiceProvider: StateFlow<String> = _effectiveVoiceProvider.asStateFlow()

    private val _firstConfiguredVoiceProvider = MutableStateFlow(prefs.getFirstConfiguredVoiceProvider())
    val firstConfiguredVoiceProvider: StateFlow<String> = _firstConfiguredVoiceProvider.asStateFlow()

    private val _voiceOutputError = MutableStateFlow<String?>(null)
    val voiceOutputError: StateFlow<String?> = _voiceOutputError.asStateFlow()

    private var activeSpeakJob: Job? = null

    @Volatile
    private var isProcessingCommand = false

    @Volatile
    private var lastProcessedCommandKey = ""

    @Volatile
    private var lastProcessedCommandAt = 0L

    companion object {
        const val GREETING_TEXT =
            "Namaste! Main MYRAA AI hoon, aapki Mahi voice ready hai. Boliye main aapki kya madad karu?"

        internal val RAPID_GESTURE_TARGETS = setOf(
            "scroll_down",
            "scroll_up",
            "swipe_up",
            "swipe_down",
            "next_short",
            "next_video",
            "previous_short",
            "previous_video",
            "home",
            "back"
        )

        @Volatile
        private var INSTANCE: MyraaSessionCoordinator? = null

        fun getInstance(context: Context): MyraaSessionCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MyraaSessionCoordinator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun initializeOnScreenAppear() {
        coordinatorScope.launch {
            memoryManager.ensurePermanentMemorySeeded()
        }
        refreshVoiceProviderState()
        refreshVoiceGuardState()
        fleetManager.refreshFleetStatus(clearErrors = true)
    }

    fun setVoiceProviderMode(mode: String) {
        prefs.setVoiceProviderMode(mode)
        refreshVoiceProviderState()
    }

    fun refreshVoiceProviderState() {
        _voiceProviderMode.value = prefs.getVoiceProviderMode()
        _effectiveVoiceProvider.value = prefs.getEffectiveVoiceProvider()
        _firstConfiguredVoiceProvider.value = prefs.getFirstConfiguredVoiceProvider()
    }

    fun refreshVoiceGuardState() {
        _isBossVoiceEnrolled.value = prefs.hasBossVoiceprint()
        _isBossVoiceGuardEnabled.value = prefs.isBossVoiceGuardEnabled()
    }

    fun setBossVoiceGuardEnabled(enabled: Boolean) {
        prefs.setBossVoiceGuardEnabled(enabled)
        _isBossVoiceGuardEnabled.value = enabled
        if (!enabled) {
            _speakerVerificationState.value = null
        }
    }

    fun clearBossVoiceprint() {
        prefs.clearBossVoiceprint()
        _isBossVoiceEnrolled.value = false
        _speakerVerificationState.value = null
        _bossEnrollmentStatus.value = "Boss Voice ID cleared."
    }

    fun enrollBossVoiceSample(bossName: String = prefs.getBossDisplayName()) {
        if (_isEnrollingBossVoice.value) return
        prefs.setBossDisplayName(bossName)
        _isEnrollingBossVoice.value = true
        _bossEnrollmentStatus.value = "Listening 2.6s — boliye: 'Hey MYRAA, main Priyanshu hoon'..."

        coordinatorScope.launch {
            val wasSessionActive = _isSessionActive.value
            val wasPaused = _isListeningPaused.value
            if (wasSessionActive && !wasPaused) {
                voiceManager.pauseListening()
            }
            try {
                val result = VoiceBiometricManager.captureEnrollmentSample(
                    context = appContext,
                    bossName = bossName,
                    durationMs = 2600L
                )
                result.onSuccess { freshProfile ->
                    val existing = VoiceprintProfile.fromJsonString(prefs.getBossVoiceprintJson())
                    val finalProfile = if (existing != null) {
                        VoiceBiometricManager.mergeProfiles(listOf(existing, freshProfile), bossName) ?: freshProfile
                    } else {
                        freshProfile
                    }
                    prefs.saveBossVoiceprintJson(finalProfile.toJsonString())
                    refreshVoiceGuardState()
                    _speakerVerificationState.value = SpeakerVerificationResult(
                        isEnrolled = true,
                        isBossMatch = true,
                        similarityPercent = 100,
                        measuredPitchHz = finalProfile.meanPitchHz,
                        enrolledPitchHz = finalProfile.meanPitchHz,
                        statusLabel = "${finalProfile.bossName} Enrolled (${finalProfile.sampleCount}x • ${finalProfile.meanPitchHz.toInt()} Hz)"
                    )
                    _bossEnrollmentStatus.value =
                        "Boss Voice ID saved (${finalProfile.sampleCount} sample(s), pitch ${finalProfile.meanPitchHz.toInt()} Hz). Doosri awaaz aate hi warning milegi!"
                    playReadyChime()
                }.onFailure { err ->
                    _bossEnrollmentStatus.value = err.localizedMessage ?: "Voice sample capture failed."
                }
            } finally {
                _isEnrollingBossVoice.value = false
                if (wasSessionActive && !wasPaused) {
                    voiceManager.resumeListening()
                }
            }
        }
    }

    fun prewarmVoiceGreeting() {
        // Prewarm is intentionally skipped at launch to preserve free-tier TTS RPM quota.
    }

    private fun playReadyChime() {
        coordinatorScope.launch(Dispatchers.IO) {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
                kotlinx.coroutines.delay(130L)
                tone.release()
            } catch (_: Exception) {}
        }
    }

    fun setErrorMessage(msg: String) {
        _currentAiReply.value = msg
    }

    fun refreshFleetOnResume() {
        refreshVoiceProviderState()
        refreshVoiceGuardState()
        fleetManager.refreshFleetStatus(clearErrors = true)
    }

    fun finishUtteranceNow() {
        voiceManager.finishUtteranceNow()
    }

    fun replayCurrentReply() {
        speakMahiResponse(_currentAiReply.value)
    }

    fun speakMahiResponse(text: String) {
        activeSpeakJob?.cancel()
        activeSpeakJob = coordinatorScope.launch {
            _voiceOutputError.value = null
            voiceManager.suspendListeningForProcessing(CompanionState.TALKING)
            elevenLabsClient.speakText(
                text = text,
                apiKey = prefs.getVoiceApiKey(),
                voiceId = prefs.getSelectedVoiceId(),
                modelId = prefs.getSelectedVoiceModel(),
                speechSpeed = prefs.getSpeechSpeed(),
                onPlaybackStarted = {
                    _voiceOutputError.value = null
                    fleetManager.clearAgentError("mahi_voice_output")
                    voiceManager.suspendListeningForProcessing(CompanionState.TALKING)
                },
                onPlaybackCompleted = {
                    if (_isSessionActive.value && !_isListeningPaused.value) {
                        voiceManager.resumeListeningAfterSpeech()
                    } else {
                        voiceManager.setCompanionState(CompanionState.IDLE)
                    }
                },
                onError = { err ->
                    _voiceOutputError.value = err
                    fleetManager.recordAgentError("mahi_voice_output", err)
                    if (_isSessionActive.value && !_isListeningPaused.value) {
                        voiceManager.resumeListeningAfterSpeech()
                    } else {
                        voiceManager.setCompanionState(CompanionState.IDLE)
                    }
                }
            )
        }
    }

    fun startSession() {
        if (_isStartingSession.value || _isSessionActive.value) return
        _isStartingSession.value = true
        coordinatorScope.launch {
            try {
                val report = initVerifier.verifyAll(checkLiveConnectivity = false)
                if (!report.canStartVoiceSession) {
                    voiceManager.setCompanionState(CompanionState.ERROR)
                    _currentAiReply.value = report.buildSummaryMessage()
                    _preflightReportDialog.value = report
                    return@launch
                }
                if (report.warningMessages.isNotEmpty()) {
                    _currentAiReply.value = report.warningMessages.joinToString("\n")
                }
                activateSessionAfterVerification()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                voiceManager.setCompanionState(CompanionState.ERROR)
                _currentAiReply.value = "Initialization check failed: ${e.localizedMessage}"
            } finally {
                _isStartingSession.value = false
            }
        }
    }

    fun activateSessionAfterVerification() {
        _isSessionActive.value = true
        _isListeningPaused.value = false
        coordinatorScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    fleetManager.initializeAndStartEligibleAgents()
                }
                voiceManager.startContinuousListening()
                playReadyChime()
                try {
                    ContinuousVoiceService.start(appContext)
                } catch (e: Exception) {
                    Log.w("MyraaCoordinator", "Could not start ContinuousVoiceService", e)
                }
                try {
                    if (prefs.isOverlayEnabled() && Settings.canDrawOverlays(appContext)) {
                        MyraaOverlayService.start(appContext)
                    }
                } catch (e: Exception) {
                    Log.w("MyraaCoordinator", "Could not start MyraaOverlayService", e)
                }
                try {
                    if (Settings.canDrawOverlays(appContext)) {
                        DigitalMouseService.start(appContext)
                    }
                } catch (e: Exception) {
                    Log.w("MyraaCoordinator", "Could not start DigitalMouseService", e)
                }

                val mandatoryUnavailable = summary.unavailableAgents.filter { !it.isOptional }
                _currentAiReply.value = if (mandatoryUnavailable.isEmpty()) {
                    "Main sun rahi hoon! Boliye kya kaam hai?"
                } else {
                    val coreReasons = mandatoryUnavailable.joinToString("; ") {
                        "${it.name}: ${it.errorReason ?: it.detailMessage}"
                    }
                    "Main sun rahi hoon!\n($coreReasons)"
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("MyraaCoordinator", "Unable to start listening", e)
                _isSessionActive.value = false
                voiceManager.setCompanionState(CompanionState.ERROR)
                _currentAiReply.value = "Unable to start listening: ${e.localizedMessage ?: "Service failure"}"
            }
        }
    }

    fun stopSession(notifyService: Boolean = true) {
        _isSessionActive.value = false
        _isListeningPaused.value = false
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        voiceManager.stopContinuousListening()
        elevenLabsClient.stopSpeaking()
        if (notifyService) {
            try { ContinuousVoiceService.stop(appContext) } catch (_: Exception) {}
        }
        try { MyraaOverlayService.stop(appContext) } catch (_: Exception) {}
        try { DigitalMouseService.stop(appContext) } catch (_: Exception) {}
        coordinatorScope.launch {
            fleetManager.stopAllAgents()
        }
    }

    fun pauseListening(notifyService: Boolean = true) {
        if (!_isSessionActive.value) return
        _isListeningPaused.value = true
        voiceManager.pauseListening()
        if (notifyService) {
            try { ContinuousVoiceService.pause(appContext) } catch (_: Exception) {}
        }
    }

    fun resumeListening(notifyService: Boolean = true) {
        if (!_isSessionActive.value) {
            startSession()
            return
        }
        _isListeningPaused.value = false
        voiceManager.resumeListening()
        if (notifyService) {
            try { ContinuousVoiceService.resume(appContext) } catch (_: Exception) {}
        }
    }

    fun pauseListeningFromService() {
        pauseListening(notifyService = false)
    }

    fun resumeListeningFromService() {
        if (_isSessionActive.value) {
            resumeListening(notifyService = false)
        }
    }

    fun stopSessionFromService() {
        stopSession(notifyService = false)
    }

    fun dismissPreflightDialog() {
        _preflightReportDialog.value = null
    }

    fun continueAnywayAfterPreflight() {
        _preflightReportDialog.value = null
        activateSessionAfterVerification()
    }

    fun dismissAppsDialog() {
        _showAppsDialog.value = false
    }

    fun launchInstalledApp(app: LaunchableAppInfo) {
        _showAppsDialog.value = false
        coordinatorScope.launch {
            val result = actionExecutor.launchSpecificPackage(app.packageName, app.label)
            _lastExecutedAction.value = result.actionType
            _lastActionSuccess.value = result.success
            val feedback = result.spokenFeedback ?: result.details
            _currentAiReply.value = feedback
            speakMahiResponse(feedback)
        }
    }

    fun toggleSelectedApp(packageName: String) {
        prefs.toggleSelectedApp(packageName)
        _launchableApps.value = actionExecutor.getInstalledLaunchableApps()
    }

    fun confirmPendingAction() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        executeValidatedAndRespond(pending.command, "USER_CONFIRMED")
    }

    fun cancelPendingAction() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        coordinatorScope.launch {
            actionExecutor.logPipelineOutcome(
                rawPrompt = pending.command.rawPrompt,
                recognizedIntent = pending.command.intent,
                actionType = pending.command.actionType,
                target = pending.command.target,
                agentInvolved = "Permission Manager Agent",
                confirmationStatus = "USER_CANCELLED",
                status = "CANCELLED",
                spokenResponse = "Action cancelled by user.",
                details = "User declined confirmation for ${pending.command.actionType}"
            )
        }
        val cancelMsg = "Theek hai, yeh action cancel kar diya hai."
        _currentAiReply.value = cancelMsg
        speakMahiResponse(cancelMsg)
    }

    fun processTextCommand(commandText: String) {
        val trimmed = commandText.trim()
        if (trimmed.isBlank()) return

        val now = System.currentTimeMillis()
        val dedupeKey = trimmed.lowercase()
        if (isProcessingCommand) return
        if (dedupeKey == lastProcessedCommandKey && (now - lastProcessedCommandAt) < 1800L) return

        isProcessingCommand = true
        lastProcessedCommandKey = dedupeKey
        lastProcessedCommandAt = now
        _currentSpeechTranscript.value = trimmed

        coordinatorScope.launch {
            try {
                activeSpeakJob?.cancel()
                elevenLabsClient.stopSpeaking()
                voiceManager.suspendListeningForProcessing(CompanionState.THINKING)
                val apiKey = prefs.getGeminiApiKey()
                val model = prefs.getSelectedGeminiModel()
                val screenSnapshot = if (RealScreenCaptureService.isSharingActive.value) {
                    RealScreenCaptureService.latestScreenFrameBase64.value
                } else {
                    null
                }
                val screenUiSummary = try {
                    MyraaAccessibilityService.instance?.getCompactScreenContextSummary().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val memoryContext = withContext(Dispatchers.IO) {
                    memoryManager.buildPromptMemoryContext()
                }
                val parsed = withTimeout(20_000L) {
                    geminiClient.interpretCommand(
                        prompt = trimmed,
                        apiKey = apiKey,
                        preferredModel = model,
                        screenImageBase64 = screenSnapshot,
                        memoryContext = memoryContext,
                        screenContextSummary = screenUiSummary,
                        onModelAutoUpdated = { workingModel ->
                            if (workingModel != model) {
                                prefs.setSelectedGeminiModel(workingModel)
                            }
                        }
                    )
                }
                if (apiKey.isNotBlank() && !parsed.spokenResponse.contains("connect nahi ho paaya", ignoreCase = true)) {
                    fleetManager.clearAgentError("gemini_intelligence")
                }

                handleParsedPipelineResult(parsed, trimmed)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("MyraaCoordinator", "Error in command pipeline", e)
                fleetManager.recordAgentError("gemini_intelligence", e.localizedMessage ?: "Pipeline error")
                _lastExecutedAction.value = "ERROR"
                _lastActionSuccess.value = false
                voiceManager.setCompanionState(CompanionState.ERROR)
                _currentAiReply.value = "Command process karne me error aaya: ${e.localizedMessage}"
                if (_isSessionActive.value && !_isListeningPaused.value) {
                    voiceManager.resumeListeningAfterSpeech()
                }
            } finally {
                isProcessingCommand = false
            }
        }
    }

    fun processVoiceAudio(audioWavBytes: ByteArray) {
        if (isProcessingCommand || audioWavBytes.size < 100) {
            if (_isSessionActive.value && !_isListeningPaused.value) {
                voiceManager.resumeListeningAfterSpeech()
            }
            return
        }

        isProcessingCommand = true
        _currentSpeechTranscript.value = "Voice message captured..."

        coordinatorScope.launch {
            try {
                activeSpeakJob?.cancel()
                elevenLabsClient.stopSpeaking()
                voiceManager.suspendListeningForProcessing(CompanionState.THINKING)
                val apiKey = prefs.getGeminiApiKey()
                val model = prefs.getSelectedGeminiModel()
                val screenSnapshot = if (RealScreenCaptureService.isSharingActive.value) {
                    RealScreenCaptureService.latestScreenFrameBase64.value
                } else {
                    null
                }
                val screenUiSummary = try {
                    MyraaAccessibilityService.instance?.getCompactScreenContextSummary().orEmpty()
                } catch (_: Exception) {
                    ""
                }

                // Ultra-fast (< 3ms) On-Device Boss/Jaan Speaker Verification
                val bossGuardDirective = withContext(Dispatchers.Default) {
                    if (prefs.isBossVoiceGuardEnabled() && prefs.hasBossVoiceprint()) {
                        val enrolled = VoiceprintProfile.fromJsonString(prefs.getBossVoiceprintJson())
                        val verification = VoiceBiometricManager.verifySpeaker(enrolled, audioWavBytes)
                        _speakerVerificationState.value = verification
                        if (!verification.isBossMatch) {
                            launch(Dispatchers.IO) { elevenLabsClient.playSecurityAlertCue() }
                            "[SECURITY ALERT — DIFFERENT / UNKNOWN VOICE DETECTED! Match=${verification.similarityPercent}%, speaker pitch=${verification.measuredPitchHz.toInt()} Hz vs Boss=${verification.enrolledPitchHz.toInt()} Hz. Yeh ${prefs.getBossDisplayName()} ki awaaz NAHI hai! Apne spokenResponse me sabse pehle is anjaan speaker ko clearly warn karo ki aap Boss/Jaan ki awaaz nahi ho aur sensitive actions allow nahi hain.]\n"
                        } else {
                            ""
                        }
                    } else {
                        _speakerVerificationState.value = null
                        ""
                    }
                }

                val memoryContext = withContext(Dispatchers.IO) {
                    bossGuardDirective + memoryManager.buildPromptMemoryContext()
                }
                val parsed = withTimeout(20_000L) {
                    geminiClient.interpretAudioCommand(
                        audioWavBytes = audioWavBytes,
                        apiKey = apiKey,
                        preferredModel = model,
                        screenImageBase64 = screenSnapshot,
                        memoryContext = memoryContext,
                        screenContextSummary = screenUiSummary,
                        onModelAutoUpdated = { workingModel ->
                            if (workingModel != model) {
                                prefs.setSelectedGeminiModel(workingModel)
                            }
                        }
                    )
                }
                val heard = parsed.heardSummary.ifBlank { "Voice command" }
                _currentSpeechTranscript.value = heard
                if (apiKey.isNotBlank() && !parsed.spokenResponse.contains("connect nahi ho paaya", ignoreCase = true)) {
                    fleetManager.clearAgentError("gemini_intelligence")
                }

                handleParsedPipelineResult(parsed, heard)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("MyraaCoordinator", "Direct voice audio pipeline error", e)
                fleetManager.recordAgentError("gemini_intelligence", e.localizedMessage ?: "Audio pipeline error")
                _lastExecutedAction.value = "ERROR"
                _lastActionSuccess.value = false
                voiceManager.setCompanionState(CompanionState.ERROR)
                _currentAiReply.value = "Voice samajhne me error aaya: ${e.localizedMessage}"
                if (_isSessionActive.value && !_isListeningPaused.value) {
                    voiceManager.resumeListeningAfterSpeech()
                }
            } finally {
                isProcessingCommand = false
            }
        }
    }

    private fun handleParsedPipelineResult(parsed: ParsedCommand, rawLabel: String) {
        val validation = ActionSecurityPolicy.validate(parsed)
        if (!validation.allowed) {
            val rejectMsg = validation.rejectionReason ?: "Security policy blocked this action."
            coordinatorScope.launch {
                actionExecutor.logPipelineOutcome(
                    rawPrompt = rawLabel,
                    recognizedIntent = parsed.intent,
                    actionType = "BLOCKED",
                    target = parsed.target,
                    agentInvolved = "Permission Manager Agent",
                    permissionStatus = "BLOCKED_BY_POLICY",
                    confirmationStatus = "REJECTED",
                    status = "BLOCKED",
                    spokenResponse = rejectMsg,
                    details = rejectMsg,
                    errorInfo = rejectMsg
                )
            }
            _lastExecutedAction.value = "BLOCKED"
            _lastActionSuccess.value = false
            _currentAiReply.value = rejectMsg
            voiceManager.setCompanionState(CompanionState.ERROR)
            speakMahiResponse("Suraksha kaaran se yeh action block kiya gaya hai.")
            return
        }

        if (validation.requiresConfirmation) {
            _pendingConfirmation.value = PendingConfirmationAction(parsed, validation)
            voiceManager.setCompanionState(CompanionState.IDLE)
            return
        }

        executeValidatedAndRespond(parsed, "AUTO_APPROVED")
    }

    private fun executeValidatedAndRespond(
        parsed: ParsedCommand,
        confirmationStatus: String = "AUTO_APPROVED"
    ) {
        coordinatorScope.launch {
            try {
                when (parsed.actionType.uppercase()) {
                    "PAUSE_LISTENING" -> {
                        val res = actionExecutor.executeCommand(parsed, confirmationStatus)
                        _lastExecutedAction.value = res.actionType
                        _lastActionSuccess.value = res.success
                        _currentAiReply.value = parsed.spokenResponse
                        pauseListening()
                        speakMahiResponse(parsed.spokenResponse)
                        return@launch
                    }
                    "RESUME_LISTENING" -> {
                        val res = actionExecutor.executeCommand(parsed, confirmationStatus)
                        _lastExecutedAction.value = res.actionType
                        _lastActionSuccess.value = res.success
                        _currentAiReply.value = parsed.spokenResponse
                        resumeListening()
                        speakMahiResponse(parsed.spokenResponse)
                        return@launch
                    }
                    "STOP_AI" -> {
                        actionExecutor.executeCommand(parsed, confirmationStatus)
                        _lastExecutedAction.value = "STOP_AI"
                        _lastActionSuccess.value = true
                        _currentAiReply.value = parsed.spokenResponse
                        voiceManager.suspendListeningForProcessing(CompanionState.TALKING)
                        elevenLabsClient.speakText(
                            text = parsed.spokenResponse,
                            apiKey = prefs.getVoiceApiKey(),
                            voiceId = prefs.getSelectedVoiceId(),
                            modelId = prefs.getSelectedVoiceModel(),
                            speechSpeed = prefs.getSpeechSpeed(),
                            onPlaybackCompleted = { stopSession() },
                            onError = { stopSession() }
                        )
                        return@launch
                    }
                    "LIST_APPS" -> {
                        val res = actionExecutor.executeCommand(parsed, confirmationStatus)
                        _launchableApps.value = actionExecutor.getInstalledLaunchableApps()
                        _showAppsDialog.value = true
                        _lastExecutedAction.value = res.actionType
                        _lastActionSuccess.value = res.success
                        _currentAiReply.value = parsed.spokenResponse
                        speakMahiResponse(parsed.spokenResponse)
                        return@launch
                    }
                    "SHOW_HISTORY" -> {
                        actionExecutor.executeCommand(parsed, confirmationStatus)
                        _lastExecutedAction.value = "SHOW_HISTORY"
                        _lastActionSuccess.value = true
                        _navigateToLogsEvent.tryEmit(Unit)
                        return@launch
                    }
                    "BUILD_WEBSITE" -> {
                        val res = actionExecutor.executeCommand(parsed, confirmationStatus)
                        _lastExecutedAction.value = res.actionType
                        _lastActionSuccess.value = res.success
                        _currentAiReply.value = parsed.spokenResponse
                        _navigateToWebBuilderEvent.tryEmit(parsed.target.ifBlank { parsed.rawPrompt })
                        speakMahiResponse(parsed.spokenResponse)
                        return@launch
                    }
                }

                val result: ExecutionResult = actionExecutor.executeCommand(parsed, confirmationStatus)
                _lastExecutedAction.value = result.actionType
                _lastActionSuccess.value = result.success

                val finalSpeech = when {
                    !result.success -> result.spokenFeedback ?: result.details
                    !result.spokenFeedback.isNullOrBlank() && parsed.actionType != "CHAT" -> result.spokenFeedback
                    else -> parsed.spokenResponse
                }
                val isRapidNavigationGesture = result.success && (
                    parsed.actionType.equals("VOLUME", ignoreCase = true) ||
                        (parsed.actionType.equals("ACCESSIBILITY", ignoreCase = true) &&
                            parsed.target.trim().lowercase() in RAPID_GESTURE_TARGETS)
                    )

                _currentAiReply.value = finalSpeech
                if (!result.success) {
                    voiceManager.setCompanionState(CompanionState.ERROR)
                }

                if (isRapidNavigationGesture) {
                    coordinatorScope.launch { elevenLabsClient.playQuickAckCue() }
                    if (_isSessionActive.value && !_isListeningPaused.value) {
                        voiceManager.resumeListeningAfterSpeech()
                    } else {
                        voiceManager.setCompanionState(CompanionState.IDLE)
                    }
                } else {
                    speakMahiResponse(finalSpeech)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("MyraaCoordinator", "Execution pipeline failed", e)
                fleetManager.recordAgentError("device_action", e.localizedMessage ?: "Execution error")
                _lastExecutedAction.value = parsed.actionType
                _lastActionSuccess.value = false
                _currentAiReply.value = "Action execute karte waqt error aaya: ${e.localizedMessage}"
                voiceManager.setCompanionState(CompanionState.ERROR)
                speakMahiResponse("Maaf kijiye, action execute nahi ho paaya.")
            }
        }
    }
}
