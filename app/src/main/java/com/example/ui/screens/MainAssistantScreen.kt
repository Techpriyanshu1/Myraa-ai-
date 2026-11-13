package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.example.service.MyraaOverlayService
import com.example.service.RealScreenCaptureService
import com.example.ui.components.ActionConfirmationDialog
import com.example.ui.components.AnimatedAiOrb
import com.example.ui.components.AuroraWaveGlow
import com.example.ui.components.InitializationDiagnosticsDialog
import com.example.ui.components.InstalledAppsDialog
import com.example.ui.components.PendingConfirmationAction
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.AuroraPink
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import com.example.voice.CompanionState
import com.example.voice.VoiceRecognitionManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAssistantScreen(
    onNavigateToLogs: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemory: () -> Unit = {},
    onNavigateToAgentFleet: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val prefs = remember { SecurePreferences(context) }
    val geminiClient = remember { GeminiClient() }
    val elevenLabsClient = remember { ElevenLabsClient(context) }
    val actionExecutor = remember { DeviceActionExecutor(context) }
    val fleetManager = remember { AgentFleetManager.getInstance(context, prefs) }
    val initVerifier = remember { InitializationVerifier(context, prefs, geminiClient, fleetManager) }
    val memoryManager = remember { MemoryCoreManager(AppDatabase.getInstance(context)) }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val isRealScreenSharing by RealScreenCaptureService.isSharingActive.collectAsState()
    val screenShareError by RealScreenCaptureService.lastError.collectAsState()
    val servicePaused by ContinuousVoiceService.isPaused.collectAsState()

    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            RealScreenCaptureService.start(context, result.resultCode, result.data!!)
        }
    }

    var isSessionActive by remember { mutableStateOf(false) }
    var isListeningPaused by remember { mutableStateOf(false) }
    var isStartingSession by remember { mutableStateOf(false) }
    var isProcessingCommand by remember { mutableStateOf(false) }
    var lastProcessedCommandKey by remember { mutableStateOf("") }
    var lastProcessedCommandAt by remember { mutableStateOf(0L) }
    var textInput by remember { mutableStateOf("") }
    var currentSpeechTranscript by remember { mutableStateOf("") }
    var currentAiReply by remember {
        mutableStateOf("Namaste! Main MYRAA AI hoon. Mic button dabakar seedhe apni awaaz me baat karein.")
    }
    var lastExecutedAction by remember { mutableStateOf<String?>(null) }
    var lastActionSuccess by remember { mutableStateOf(true) }

    var showAppsDialog by remember { mutableStateOf(false) }
    var launchableApps by remember { mutableStateOf<List<LaunchableAppInfo>>(emptyList()) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmationAction?>(null) }
    var preflightReportDialog by remember { mutableStateOf<InitializationPreflightReport?>(null) }

    lateinit var voiceManagerRef: VoiceRecognitionManager

    fun speakMahiResponse(text: String) {
        coroutineScope.launch {
            voiceManagerRef.setCompanionState(CompanionState.TALKING)
            elevenLabsClient.speakText(
                text = text,
                apiKey = prefs.getVoiceApiKey(),
                voiceId = prefs.getSelectedVoiceId(),
                modelId = prefs.getSelectedVoiceModel(),
                speechSpeed = prefs.getSpeechSpeed(),
                onPlaybackStarted = {
                    voiceManagerRef.setCompanionState(CompanionState.TALKING)
                },
                onPlaybackCompleted = {
                    if (isSessionActive && !isListeningPaused) {
                        voiceManagerRef.resumeListeningAfterSpeech()
                    } else {
                        voiceManagerRef.setCompanionState(CompanionState.IDLE)
                    }
                },
                onError = { err ->
                    fleetManager.recordAgentError("mahi_voice_output", err)
                    if (isSessionActive && !isListeningPaused) {
                        voiceManagerRef.resumeListeningAfterSpeech()
                    } else {
                        voiceManagerRef.setCompanionState(CompanionState.IDLE)
                    }
                }
            )
        }
    }

    fun activateSessionAfterVerification() {
        isSessionActive = true
        isListeningPaused = false
        coroutineScope.launch {
            try {
                val summary = fleetManager.initializeAndStartEligibleAgents()
                voiceManagerRef.prepareSessionForGreeting()
                ContinuousVoiceService.start(context)
                if (prefs.isOverlayEnabled() && Settings.canDrawOverlays(context)) {
                    MyraaOverlayService.start(context)
                }

                val mandatoryUnavailable = summary.unavailableAgents.filter { !it.isOptional }
                val greeting = "Namaste! Main MYRAA AI hoon, aapki Mahi voice ready hai. Boliye main aapki kya madad karu?"
                currentAiReply = if (mandatoryUnavailable.isEmpty()) {
                    greeting
                } else {
                    val coreReasons = mandatoryUnavailable.joinToString("; ") { "${it.name}: ${it.errorReason ?: it.detailMessage}" }
                    "$greeting\n($coreReasons)"
                }
                speakMahiResponse(greeting)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("MainAssistantScreen", "Unable to start listening", e)
                isSessionActive = false
                voiceManagerRef.setCompanionState(CompanionState.ERROR)
                currentAiReply = "Unable to start listening: ${e.localizedMessage ?: "Service failure"}"
            }
        }
    }

    fun startMyraaSession() {
        if (isStartingSession || isSessionActive) return
        isStartingSession = true
        coroutineScope.launch {
            try {
                voiceManagerRef.setCompanionState(CompanionState.THINKING)
                val report = initVerifier.verifyAll(checkLiveConnectivity = true)
                if (!report.canStartVoiceSession) {
                    voiceManagerRef.setCompanionState(CompanionState.ERROR)
                    currentAiReply = report.buildSummaryMessage()
                    preflightReportDialog = report
                    return@launch
                }
                if (report.warningMessages.isNotEmpty()) {
                    currentAiReply = report.warningMessages.joinToString("\n")
                }
                activateSessionAfterVerification()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                voiceManagerRef.setCompanionState(CompanionState.ERROR)
                currentAiReply = "Initialization check failed: ${e.localizedMessage}"
            } finally {
                isStartingSession = false
            }
        }
    }

    fun stopMyraaSession() {
        isSessionActive = false
        isListeningPaused = false
        voiceManagerRef.stopContinuousListening()
        elevenLabsClient.stopSpeaking()
        ContinuousVoiceService.stop(context)
        MyraaOverlayService.stop(context)
        coroutineScope.launch {
            fleetManager.stopAllAgents()
        }
    }

    fun pauseListeningSession() {
        if (!isSessionActive) return
        isListeningPaused = true
        voiceManagerRef.pauseListening()
        ContinuousVoiceService.pause(context)
    }

    fun resumeListeningSession() {
        if (!isSessionActive) {
            startMyraaSession()
            return
        }
        isListeningPaused = false
        voiceManagerRef.resumeListening()
        ContinuousVoiceService.resume(context)
    }

    LaunchedEffect(servicePaused, isSessionActive) {
        if (isSessionActive && servicePaused != isListeningPaused) {
            isListeningPaused = servicePaused
            if (servicePaused) voiceManagerRef.pauseListening() else voiceManagerRef.resumeListening()
        }
    }

    fun executeValidatedAndRespond(
        parsed: ParsedCommand,
        confirmationStatus: String = "AUTO_APPROVED"
    ) {
        coroutineScope.launch {
            try {
                when (parsed.actionType.uppercase()) {
                    "PAUSE_LISTENING" -> {
                        val res = actionExecutor.executeCommand(parsed, confirmationStatus)
                        lastExecutedAction = res.actionType
                        lastActionSuccess = res.success
                        currentAiReply = parsed.spokenResponse
                        pauseListeningSession()
                        speakMahiResponse(parsed.spokenResponse)
                        return@launch
                    }
                    "RESUME_LISTENING" -> {
                        val res = actionExecutor.executeCommand(parsed, confirmationStatus)
                        lastExecutedAction = res.actionType
                        lastActionSuccess = res.success
                        currentAiReply = parsed.spokenResponse
                        resumeListeningSession()
                        speakMahiResponse(parsed.spokenResponse)
                        return@launch
                    }
                    "STOP_AI" -> {
                        actionExecutor.executeCommand(parsed, confirmationStatus)
                        lastExecutedAction = "STOP_AI"
                        lastActionSuccess = true
                        currentAiReply = parsed.spokenResponse
                        voiceManagerRef.setCompanionState(CompanionState.TALKING)
                        elevenLabsClient.speakText(
                            text = parsed.spokenResponse,
                            apiKey = prefs.getVoiceApiKey(),
                            voiceId = prefs.getSelectedVoiceId(),
                            modelId = prefs.getSelectedVoiceModel(),
                            speechSpeed = prefs.getSpeechSpeed(),
                            onPlaybackCompleted = { stopMyraaSession() }
                        )
                        return@launch
                    }
                    "LIST_APPS" -> {
                        val res = actionExecutor.executeCommand(parsed, confirmationStatus)
                        launchableApps = actionExecutor.getInstalledLaunchableApps()
                        showAppsDialog = true
                        lastExecutedAction = res.actionType
                        lastActionSuccess = res.success
                        currentAiReply = parsed.spokenResponse
                        speakMahiResponse(parsed.spokenResponse)
                        return@launch
                    }
                    "SHOW_HISTORY" -> {
                        actionExecutor.executeCommand(parsed, confirmationStatus)
                        lastExecutedAction = "SHOW_HISTORY"
                        lastActionSuccess = true
                        onNavigateToLogs()
                        return@launch
                    }
                }

                val result: ExecutionResult = actionExecutor.executeCommand(parsed, confirmationStatus)
                lastExecutedAction = result.actionType
                lastActionSuccess = result.success

                val finalSpeech = when {
                    !result.success -> result.spokenFeedback ?: result.details
                    !result.spokenFeedback.isNullOrBlank() && parsed.actionType != "CHAT" -> result.spokenFeedback
                    else -> parsed.spokenResponse
                }
                currentAiReply = finalSpeech
                if (!result.success) {
                    voiceManagerRef.setCompanionState(CompanionState.ERROR)
                }
                speakMahiResponse(finalSpeech)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("MainAssistantScreen", "Execution pipeline failed", e)
                fleetManager.recordAgentError("device_action", e.localizedMessage ?: "Execution error")
                lastExecutedAction = parsed.actionType
                lastActionSuccess = false
                currentAiReply = "Action execute karte waqt error aaya: ${e.localizedMessage}"
                voiceManagerRef.setCompanionState(CompanionState.ERROR)
                speakMahiResponse("Maaf kijiye, action execute nahi ho paaya.")
            }
        }
    }

    fun handleParsedPipelineResult(parsed: ParsedCommand, rawLabel: String) {
        val validation = ActionSecurityPolicy.validate(parsed)
        if (!validation.allowed) {
            val rejectMsg = validation.rejectionReason ?: "Security policy blocked this action."
            coroutineScope.launch {
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
            lastExecutedAction = "BLOCKED"
            lastActionSuccess = false
            currentAiReply = rejectMsg
            voiceManagerRef.setCompanionState(CompanionState.ERROR)
            speakMahiResponse("Suraksha kaaran se yeh action block kiya gaya hai.")
            return
        }

        if (validation.requiresConfirmation) {
            pendingConfirmation = PendingConfirmationAction(parsed, validation)
            voiceManagerRef.setCompanionState(CompanionState.IDLE)
            return
        }

        executeValidatedAndRespond(parsed, "AUTO_APPROVED")
    }

    // Text Command Pipeline (with Memory Core context)
    fun processCommand(commandText: String) {
        val trimmed = commandText.trim()
        if (trimmed.isBlank()) return

        val now = System.currentTimeMillis()
        val dedupeKey = trimmed.lowercase()
        if (isProcessingCommand) return
        if (dedupeKey == lastProcessedCommandKey && (now - lastProcessedCommandAt) < 1800L) return

        isProcessingCommand = true
        lastProcessedCommandKey = dedupeKey
        lastProcessedCommandAt = now
        currentSpeechTranscript = trimmed

        coroutineScope.launch {
            try {
                voiceManagerRef.setCompanionState(CompanionState.THINKING)
                val apiKey = prefs.getGeminiApiKey()
                val model = prefs.getSelectedGeminiModel()
                val screenSnapshot = if (isRealScreenSharing) {
                    RealScreenCaptureService.latestScreenFrameBase64.value
                } else {
                    null
                }
                val memoryContext = memoryManager.buildPromptMemoryContext()
                val parsed = geminiClient.interpretCommand(
                    prompt = trimmed,
                    apiKey = apiKey,
                    preferredModel = model,
                    screenImageBase64 = screenSnapshot,
                    memoryContext = memoryContext,
                    onModelAutoUpdated = { workingModel ->
                        if (workingModel != model) {
                            prefs.setSelectedGeminiModel(workingModel)
                        }
                    }
                )
                if (apiKey.isNotBlank() && !parsed.spokenResponse.contains("connect nahi ho paaya", ignoreCase = true)) {
                    fleetManager.clearAgentError("gemini_intelligence")
                }

                handleParsedPipelineResult(parsed, trimmed)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("MainAssistantScreen", "Error in command pipeline", e)
                fleetManager.recordAgentError("gemini_intelligence", e.localizedMessage ?: "Pipeline error")
                lastExecutedAction = "ERROR"
                lastActionSuccess = false
                voiceManagerRef.setCompanionState(CompanionState.ERROR)
                currentAiReply = "Command process karne me error aaya: ${e.localizedMessage}"
                if (isSessionActive && !isListeningPaused) {
                    voiceManagerRef.resumeListeningAfterSpeech()
                }
            } finally {
                isProcessingCommand = false
            }
        }
    }

    // Direct Voice-to-Gemini Audio Pipeline (NO Android Speech-to-Text!)
    fun processVoiceAudio(audioWavBytes: ByteArray) {
        if (isProcessingCommand || audioWavBytes.size < 100) {
            if (isSessionActive && !isListeningPaused) {
                voiceManagerRef.resumeListeningAfterSpeech()
            }
            return
        }

        isProcessingCommand = true
        currentSpeechTranscript = "Voice message captured..."

        coroutineScope.launch {
            try {
                voiceManagerRef.setCompanionState(CompanionState.THINKING)
                val apiKey = prefs.getGeminiApiKey()
                val model = prefs.getSelectedGeminiModel()
                val screenSnapshot = if (isRealScreenSharing) {
                    RealScreenCaptureService.latestScreenFrameBase64.value
                } else {
                    null
                }
                val memoryContext = memoryManager.buildPromptMemoryContext()
                val parsed = geminiClient.interpretAudioCommand(
                    audioWavBytes = audioWavBytes,
                    apiKey = apiKey,
                    preferredModel = model,
                    screenImageBase64 = screenSnapshot,
                    memoryContext = memoryContext,
                    onModelAutoUpdated = { workingModel ->
                        if (workingModel != model) {
                            prefs.setSelectedGeminiModel(workingModel)
                        }
                    }
                )
                currentSpeechTranscript = parsed.heardSummary.ifBlank { "Voice command" }
                if (apiKey.isNotBlank() && !parsed.spokenResponse.contains("connect nahi ho paaya", ignoreCase = true)) {
                    fleetManager.clearAgentError("gemini_intelligence")
                }

                handleParsedPipelineResult(parsed, currentSpeechTranscript)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("MainAssistantScreen", "Direct voice audio pipeline error", e)
                fleetManager.recordAgentError("gemini_intelligence", e.localizedMessage ?: "Audio pipeline error")
                lastExecutedAction = "ERROR"
                lastActionSuccess = false
                voiceManagerRef.setCompanionState(CompanionState.ERROR)
                currentAiReply = "Voice samajhne me error aaya: ${e.localizedMessage}"
                if (isSessionActive && !isListeningPaused) {
                    voiceManagerRef.resumeListeningAfterSpeech()
                }
            } finally {
                isProcessingCommand = false
            }
        }
    }

    val voiceManager = remember {
        VoiceRecognitionManager(
            context = context,
            onAudioCaptured = { wavBytes ->
                fleetManager.clearAgentError("voice_recognition")
                processVoiceAudio(wavBytes)
            }
        ).also {
            voiceManagerRef = it
        }
    }

    val companionState by voiceManager.companionState.collectAsState()
    val rmsLevel by voiceManager.rmsLevel.collectAsState()
    val liveVoiceStatus by voiceManager.lastRecognizedText.collectAsState()
    val voiceError by voiceManager.errorMessage.collectAsState()

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            fleetManager.clearAgentError("voice_recognition")
            startMyraaSession()
        } else {
            fleetManager.refreshFleetStatus()
            currentAiReply = "Voice control ke liye Microphone permission zaroori hai. Settings -> Permission Center me jakar enable karein."
        }
    }

    LaunchedEffect(Unit) {
        memoryManager.ensurePermanentMemorySeeded()
        fleetManager.refreshFleetStatus(clearErrors = true)
    }

    LaunchedEffect(screenShareError) {
        if (!screenShareError.isNullOrBlank()) {
            currentAiReply = screenShareError!!
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fleetManager.refreshFleetStatus(clearErrors = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voiceManager.destroy()
            elevenLabsClient.shutdown()
            ContinuousVoiceService.stop(context)
        }
    }

    pendingConfirmation?.let { pending ->
        ActionConfirmationDialog(
            pending = pending,
            onConfirm = {
                pendingConfirmation = null
                executeValidatedAndRespond(pending.command, "USER_CONFIRMED")
            },
            onCancel = {
                pendingConfirmation = null
                coroutineScope.launch {
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
                currentAiReply = cancelMsg
                speakMahiResponse(cancelMsg)
            }
        )
    }

    preflightReportDialog?.let { report ->
        InitializationDiagnosticsDialog(
            report = report,
            onContinueAnyway = if (report.canStartVoiceSession) {
                {
                    preflightReportDialog = null
                    activateSessionAfterVerification()
                }
            } else null,
            onDismiss = { preflightReportDialog = null }
        )
    }

    if (showAppsDialog) {
        InstalledAppsDialog(
            apps = launchableApps,
            onLaunchApp = { app ->
                showAppsDialog = false
                coroutineScope.launch {
                    val result = actionExecutor.launchSpecificPackage(app.packageName, app.label)
                    lastExecutedAction = result.actionType
                    lastActionSuccess = result.success
                    currentAiReply = result.spokenFeedback ?: result.details
                    speakMahiResponse(currentAiReply)
                }
            },
            onToggleSelectApp = { app ->
                prefs.toggleSelectedApp(app.packageName)
                launchableApps = actionExecutor.getInstalledLaunchableApps()
            },
            onDismiss = { showAppsDialog = false }
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSessionActive && !isListeningPaused -> AuroraEmerald
                                        isSessionActive && isListeningPaused -> AuroraPink
                                        else -> CyanPrimary
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "MYRAA AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Text(
                                text = when {
                                    isSessionActive && isListeningPaused -> "Voice Paused"
                                    isSessionActive -> "Direct Voice • AI Audio"
                                    else -> "Ready"
                                },
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToMemory,
                        modifier = Modifier.testTag("nav_to_memory_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "Memory Core",
                            tint = CyanPrimary
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isRealScreenSharing) {
                                RealScreenCaptureService.stop(context)
                                currentAiReply = "Screen share band kar diya gaya hai."
                            } else {
                                try {
                                    screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                                } catch (e: Exception) {
                                    currentAiReply = "Screen share launch nahi ho paaya: ${e.localizedMessage ?: "Unsupported"}"
                                }
                            }
                        },
                        modifier = Modifier.testTag("screen_share_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isRealScreenSharing) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                            contentDescription = "Screen Share",
                            tint = if (isRealScreenSharing) AuroraPink else TextSecondary
                        )
                    }
                    IconButton(
                        onClick = onNavigateToLogs,
                        modifier = Modifier.testTag("nav_to_logs_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Activity History", tint = TextSecondary)
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("nav_to_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        bottomBar = {
            AuroraWaveGlow(
                isActive = isSessionActive && !isListeningPaused,
                rmsLevel = rmsLevel,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!prefs.hasStoredGeminiKey()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VioletSecondary.copy(alpha = 0.22f))
                            .clickable { onNavigateToSettings() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("missing_gemini_key_banner")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Gemini API Key missing • Tap here to configure",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (isRealScreenSharing) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuroraPink.copy(alpha = 0.2f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Mouse, contentDescription = null, tint = AuroraPink, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SCREEN SHARE ACTIVE",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (!voiceError.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = voiceError ?: "",
                            color = AuroraPink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Clean Centered Animated AI Orb (Tap while listening to send utterance immediately)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clickable(enabled = isSessionActive && companionState == CompanionState.LISTENING) {
                            voiceManager.finishUtteranceNow()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedAiOrb(
                        companionState = companionState,
                        rmsLevel = rmsLevel,
                        size = 230.dp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Clean Conversation Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, DarkSurfaceBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val displayPrompt = when {
                            liveVoiceStatus.isNotBlank() && companionState == CompanionState.LISTENING -> liveVoiceStatus
                            currentSpeechTranscript.isNotBlank() -> currentSpeechTranscript
                            else -> null
                        }
                        if (displayPrompt != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("You: ", style = MaterialTheme.typography.bodyMedium, color = CyanPrimary, fontWeight = FontWeight.Bold)
                                Text(displayPrompt, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MYRAA (AI Voice):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AuroraPink,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = currentAiReply,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            IconButton(
                                onClick = { speakMahiResponse(currentAiReply) },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("replay_voice_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Replay AI Voice",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        if (!lastExecutedAction.isNullOrBlank() && lastExecutedAction != "CHAT") {
                            Spacer(modifier = Modifier.height(8.dp))
                            val badgeColor = if (lastActionSuccess) AuroraEmerald else DangerRed
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(badgeColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (lastActionSuccess) "Executed: $lastExecutedAction" else "Action Blocked/Failed: $lastExecutedAction",
                                    color = badgeColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Clean Input & Upgraded Direct Voice Controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("command_keyboard_input"),
                    placeholder = { Text("Ask MYRAA anything or type a command...", fontSize = 13.sp) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    val cmd = textInput
                                    textInput = ""
                                    focusManager.clearFocus()
                                    processCommand(cmd)
                                }
                            },
                            modifier = Modifier.testTag("send_command_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = CyanPrimary)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (textInput.isNotBlank()) {
                                val cmd = textInput
                                textInput = ""
                                focusManager.clearFocus()
                                processCommand(cmd)
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DarkSurfaceElevated,
                        unfocusedContainerColor = DarkSurfaceElevated
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val pulseTransition = rememberInfiniteTransition(label = "pulse_button")
                    val pulseScale by pulseTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isSessionActive && !isListeningPaused) 1.07f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse_scale"
                    )

                    Box(
                        modifier = Modifier
                            .scale(pulseScale)
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = if (isSessionActive) {
                                        listOf(DangerRed, DangerRed.copy(alpha = 0.6f))
                                    } else {
                                        listOf(CyanPrimary, VioletSecondary)
                                    }
                                )
                            )
                            .clickable {
                                if (isSessionActive) {
                                    stopMyraaSession()
                                } else {
                                    val hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                        PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        startMyraaSession()
                                    } else {
                                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                            .testTag("main_start_stop_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSessionActive) Icons.Default.PowerSettingsNew else Icons.Default.Mic,
                            contentDescription = if (isSessionActive) "Stop Voice AI" else "Start Direct Voice AI",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    if (isSessionActive && companionState == CompanionState.LISTENING) {
                        OutlinedButton(
                            onClick = { voiceManager.finishUtteranceNow() },
                            border = BorderStroke(1.dp, CyanPrimary),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = DarkSurfaceElevated
                            ),
                            modifier = Modifier.testTag("send_voice_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Send Voice Now",
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SEND VOICE",
                                color = CyanPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isSessionActive) {
                        OutlinedButton(
                            onClick = {
                                if (isListeningPaused) resumeListeningSession() else pauseListeningSession()
                            },
                            border = BorderStroke(1.dp, if (isListeningPaused) AuroraEmerald else AuroraPink),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = DarkSurfaceElevated
                            ),
                            modifier = Modifier.testTag("pause_resume_listening_button")
                        ) {
                            Icon(
                                imageVector = if (isListeningPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isListeningPaused) "Resume Listening" else "Pause Listening",
                                tint = if (isListeningPaused) AuroraEmerald else AuroraPink,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isListeningPaused) "RESUME" else "PAUSE",
                                color = if (isListeningPaused) AuroraEmerald else AuroraPink,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = when {
                        isSessionActive && isListeningPaused -> "VOICE PAUSED • TAP RESUME OR STOP"
                        isSessionActive && companionState == CompanionState.THINKING -> "UNDERSTANDING YOUR VOICE..."
                        isSessionActive && companionState == CompanionState.TALKING -> "SPEAKING IN AI VOICE..."
                        isSessionActive -> "LISTENING DIRECTLY • SPEAK NATURALLY"
                        else -> "TAP MIC FOR DIRECT VOICE-TO-VOICE AI"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isSessionActive && isListeningPaused -> AuroraPink
                        isSessionActive -> CyanPrimary
                        else -> CyanPrimary
                    },
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.7.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
