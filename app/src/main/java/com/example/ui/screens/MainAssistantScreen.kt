package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.example.agents.AgentFleetManager
import com.example.automation.ActionSecurityPolicy
import com.example.automation.DeviceActionExecutor
import com.example.automation.ExecutionResult
import com.example.automation.LaunchableAppInfo
import com.example.data.api.ElevenLabsClient
import com.example.data.api.GeminiClient
import com.example.data.api.ParsedCommand
import com.example.data.pref.SecurePreferences
import com.example.service.ContinuousVoiceService
import com.example.service.MyraaOverlayService
import com.example.service.RealScreenCaptureService
import com.example.ui.components.ActionConfirmationDialog
import com.example.ui.components.AgentStatusGrid
import com.example.ui.components.AnimatedAiOrb
import com.example.ui.components.AuroraWaveGlow
import com.example.ui.components.InstalledAppsDialog
import com.example.ui.components.PendingConfirmationAction
import com.example.ui.components.VideoAvatarPlayer
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
    onNavigateToAgentFleet: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val prefs = remember { SecurePreferences(context) }
    val geminiClient = remember { GeminiClient() }
    val elevenLabsClient = remember { ElevenLabsClient(context) }
    val actionExecutor = remember { DeviceActionExecutor(context) }
    val fleetManager = remember { AgentFleetManager.getInstance(context, prefs) }

    val agentReports by fleetManager.agentReports.collectAsState()
    val fleetOverallStatus by fleetManager.fleetOverallStatus.collectAsState()

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val isRealScreenSharing by RealScreenCaptureService.isSharingActive.collectAsState()
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
    var textInput by remember { mutableStateOf("") }
    var currentSpeechTranscript by remember { mutableStateOf("") }
    var currentAiReply by remember {
        mutableStateOf("Namaste! Main MYRAA AI hoon. Start AI dabakar mujhse Hindi, Hinglish ya English me baat karein ya phone actions bolein.")
    }
    var lastExecutedAction by remember { mutableStateOf<String?>(null) }
    var lastActionSuccess by remember { mutableStateOf(true) }
    var selectedLanguageMode by remember { mutableStateOf(prefs.getPreferredLanguage()) }

    var showAppsDialog by remember { mutableStateOf(false) }
    var launchableApps by remember { mutableStateOf<List<LaunchableAppInfo>>(emptyList()) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmationAction?>(null) }

    lateinit var voiceManagerRef: VoiceRecognitionManager

    fun speakMahiResponse(text: String) {
        coroutineScope.launch {
            voiceManagerRef.setCompanionState(CompanionState.TALKING)
            elevenLabsClient.speakText(
                text = text,
                apiKey = prefs.getVoiceApiKey(),
                voiceId = prefs.getSelectedVoiceId(),
                modelId = prefs.getSelectedVoiceModel(),
                allowNativeFallback = prefs.isNativeTtsFallbackEnabled(),
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

    fun startMyraaSession() {
        isSessionActive = true
        isListeningPaused = false
        coroutineScope.launch {
            val summary = fleetManager.initializeAndStartEligibleAgents()
            voiceManagerRef.startContinuousListening()
            ContinuousVoiceService.start(context)
            if (prefs.isOverlayEnabled() && Settings.canDrawOverlays(context)) {
                MyraaOverlayService.start(context)
            }

            val greeting = if (summary.unavailableAgents.isEmpty()) {
                "Namaste! MYRAA AI ke sabhi ${summary.totalAgents} agents active hain. Boliye main aapki kya madad karu?"
            } else {
                "MYRAA AI active hai (${summary.activeAgents.size}/${summary.totalAgents} agents). Boliye main aapki kya madad kar sakti hoon?"
            }
            currentAiReply = if (summary.unavailableAgents.isEmpty()) {
                greeting
            } else {
                "$greeting\n(${summary.buildConciseSummary()})"
            }
            speakMahiResponse(greeting)
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

    // Steps 9–11 of Pipeline: Execute validated action, speak actual result via Mahi, log locally
    fun executeValidatedAndRespond(parsed: ParsedCommand) {
        coroutineScope.launch {
            when (parsed.actionType.uppercase()) {
                "PAUSE_LISTENING" -> {
                    val res = actionExecutor.executeCommand(parsed)
                    lastExecutedAction = res.actionType
                    lastActionSuccess = res.success
                    currentAiReply = parsed.spokenResponse
                    pauseListeningSession()
                    speakMahiResponse(parsed.spokenResponse)
                    return@launch
                }
                "RESUME_LISTENING" -> {
                    val res = actionExecutor.executeCommand(parsed)
                    lastExecutedAction = res.actionType
                    lastActionSuccess = res.success
                    currentAiReply = parsed.spokenResponse
                    resumeListeningSession()
                    speakMahiResponse(parsed.spokenResponse)
                    return@launch
                }
                "STOP_AI" -> {
                    actionExecutor.executeCommand(parsed)
                    lastExecutedAction = "STOP_AI"
                    lastActionSuccess = true
                    currentAiReply = parsed.spokenResponse
                    voiceManagerRef.setCompanionState(CompanionState.TALKING)
                    elevenLabsClient.speakText(
                        text = parsed.spokenResponse,
                        apiKey = prefs.getVoiceApiKey(),
                        voiceId = prefs.getSelectedVoiceId(),
                        modelId = prefs.getSelectedVoiceModel(),
                        allowNativeFallback = prefs.isNativeTtsFallbackEnabled(),
                        speechSpeed = prefs.getSpeechSpeed(),
                        onPlaybackCompleted = { stopMyraaSession() }
                    )
                    return@launch
                }
                "LIST_APPS" -> {
                    val res = actionExecutor.executeCommand(parsed)
                    launchableApps = actionExecutor.getInstalledLaunchableApps()
                    showAppsDialog = true
                    lastExecutedAction = res.actionType
                    lastActionSuccess = res.success
                    currentAiReply = parsed.spokenResponse
                    speakMahiResponse(parsed.spokenResponse)
                    return@launch
                }
                "SHOW_HISTORY" -> {
                    actionExecutor.executeCommand(parsed)
                    lastExecutedAction = "SHOW_HISTORY"
                    lastActionSuccess = true
                    onNavigateToLogs()
                    return@launch
                }
            }

            // Standard Device / App / Settings / Accessibility / Chat execution
            val result: ExecutionResult = actionExecutor.executeCommand(parsed)
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
        }
    }

    // Full 11-Step Intelligent Action Pipeline
    fun processCommand(commandText: String) {
        val trimmed = commandText.trim()
        if (trimmed.isBlank()) return
        currentSpeechTranscript = trimmed

        coroutineScope.launch {
            try {
                // Step 3 & 4: Gemini Structured JSON Parsing
                voiceManagerRef.setCompanionState(CompanionState.THINKING)
                val apiKey = prefs.getGeminiApiKey()
                val model = prefs.getSelectedGeminiModel()
                val parsed = geminiClient.interpretCommand(trimmed, apiKey, model)

                // Step 5, 6 & 7: Allowlist & Risk Validation
                val validation = ActionSecurityPolicy.validate(parsed)
                if (!validation.allowed) {
                    val rejectMsg = validation.rejectionReason ?: "Security policy blocked this action."
                    actionExecutor.logPipelineOutcome(
                        rawPrompt = trimmed,
                        actionType = "BLOCKED",
                        target = parsed.target,
                        status = "BLOCKED",
                        spokenResponse = rejectMsg,
                        details = rejectMsg
                    )
                    lastExecutedAction = "BLOCKED"
                    lastActionSuccess = false
                    currentAiReply = rejectMsg
                    voiceManagerRef.setCompanionState(CompanionState.ERROR)
                    speakMahiResponse("Suraksha kaaran se yeh action block kiya gaya hai.")
                    return@launch
                }

                // Step 8: Ask explicit confirmation for sensitive / HIGH-risk actions
                if (validation.requiresConfirmation) {
                    pendingConfirmation = PendingConfirmationAction(parsed, validation)
                    voiceManagerRef.setCompanionState(CompanionState.IDLE)
                    return@launch
                }

                // Steps 9–11: Execute, speak actual outcome, and persist locally
                executeValidatedAndRespond(parsed)
            } catch (e: Exception) {
                Log.e("MainAssistantScreen", "Error in 11-step command pipeline", e)
                fleetManager.recordAgentError("gemini_intelligence", e.localizedMessage ?: "Pipeline error")
                voiceManagerRef.setCompanionState(CompanionState.ERROR)
                currentAiReply = "Command process karne me error aaya: ${e.localizedMessage}"
                if (isSessionActive && !isListeningPaused) {
                    voiceManagerRef.resumeListeningAfterSpeech()
                }
            }
        }
    }

    val systemVoiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = matches?.firstOrNull()?.trim()
            if (!recognizedText.isNullOrBlank()) {
                processCommand(recognizedText)
            }
        }
    }

    fun launchSystemVoiceDialog() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in Hindi, Hinglish, or English...")
                when (selectedLanguageMode) {
                    "hi-IN" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    "en-US" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    else -> {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US"))
                    }
                }
            }
            systemVoiceLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("MainAssistantScreen", "Cannot launch system speech intent", e)
        }
    }

    val voiceManager = remember {
        VoiceRecognitionManager(
            context = context,
            onFallbackIntentNeeded = { launchSystemVoiceDialog() },
            onCommandReceived = { recognizedText -> processCommand(recognizedText) }
        ).also {
            voiceManagerRef = it
            it.languageMode = selectedLanguageMode
        }
    }

    val companionState by voiceManager.companionState.collectAsState()
    val rmsLevel by voiceManager.rmsLevel.collectAsState()
    val partialText by voiceManager.lastRecognizedText.collectAsState()

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMyraaSession()
        } else {
            fleetManager.refreshFleetStatus()
            currentAiReply = "Voice control ke liye Microphone permission zaroori hai. Permission Center me jakar enable karein."
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceManager.destroy()
            elevenLabsClient.shutdown()
            ContinuousVoiceService.stop(context)
            RealScreenCaptureService.stop(context)
        }
    }

    // Confirmation modal for HIGH-risk / sensitive actions
    pendingConfirmation?.let { pending ->
        ActionConfirmationDialog(
            pending = pending,
            onConfirm = {
                pendingConfirmation = null
                executeValidatedAndRespond(pending.command)
            },
            onCancel = {
                pendingConfirmation = null
                coroutineScope.launch {
                    actionExecutor.logPipelineOutcome(
                        rawPrompt = pending.command.rawPrompt,
                        actionType = pending.command.actionType,
                        target = pending.command.target,
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

    // Installed Apps Launcher Drawer/Dialog
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
                                    isSessionActive && isListeningPaused -> "Listening Paused"
                                    isSessionActive -> "Active • Mahi Voice"
                                    else -> "Standby Ready"
                                },
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            launchableApps = actionExecutor.getInstalledLaunchableApps()
                            showAppsDialog = true
                        },
                        modifier = Modifier.testTag("open_apps_browser_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = "Installed Apps",
                            tint = CyanPrimary
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isRealScreenSharing) {
                                RealScreenCaptureService.stop(context)
                            } else if (!Settings.canDrawOverlays(context)) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            } else {
                                screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        },
                        modifier = Modifier.testTag("screen_share_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isRealScreenSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                            contentDescription = "Screen Share",
                            tint = if (isRealScreenSharing) AuroraPink else CyanPrimary
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
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    FilterChip(
                        selected = selectedLanguageMode == "auto",
                        onClick = {
                            selectedLanguageMode = "auto"
                            voiceManager.languageMode = "auto"
                            prefs.setPreferredLanguage("auto")
                        },
                        label = { Text("Auto (Hinglish)", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanPrimary.copy(alpha = 0.25f),
                            selectedLabelColor = CyanPrimary
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = selectedLanguageMode == "hi-IN",
                        onClick = {
                            selectedLanguageMode = "hi-IN"
                            voiceManager.languageMode = "hi-IN"
                            prefs.setPreferredLanguage("hi-IN")
                        },
                        label = { Text("Hindi", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AuroraEmerald.copy(alpha = 0.25f),
                            selectedLabelColor = AuroraEmerald
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = selectedLanguageMode == "en-US",
                        onClick = {
                            selectedLanguageMode = "en-US"
                            voiceManager.languageMode = "en-US"
                            prefs.setPreferredLanguage("en-US")
                        },
                        label = { Text("English", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VioletSecondary.copy(alpha = 0.25f),
                            selectedLabelColor = VioletSecondary
                        )
                    )
                }

                if (isRealScreenSharing) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
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
                    Spacer(modifier = Modifier.height(8.dp))
                }

                var displayModeOrb by remember { mutableStateOf(true) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceElevated)
                            .clickable { displayModeOrb = !displayModeOrb }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("toggle_orb_avatar_button")
                    ) {
                        Text(
                            text = if (displayModeOrb) "Switch to Avatar" else "Switch to AI Orb",
                            color = CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (displayModeOrb) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedAiOrb(
                            companionState = companionState,
                            rmsLevel = rmsLevel,
                            size = 220.dp
                        )
                    }
                } else {
                    VideoAvatarPlayer(
                        companionState = companionState,
                        isScreenShareActive = isRealScreenSharing,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        val displayPrompt = when {
                            partialText.isNotBlank() -> partialText
                            currentSpeechTranscript.isNotBlank() -> currentSpeechTranscript
                            else -> null
                        }
                        if (displayPrompt != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("You: ", style = MaterialTheme.typography.bodyMedium, color = CyanPrimary, fontWeight = FontWeight.Bold)
                                Text("\"$displayPrompt\"", style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MYRAA (Mahi Voice):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AuroraPink,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = currentAiReply,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            IconButton(
                                onClick = { coroutineScope.launch { speakMahiResponse(currentAiReply) } },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("replay_voice_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Replay Voice",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (!lastExecutedAction.isNullOrBlank() && lastExecutedAction != "CHAT") {
                            Spacer(modifier = Modifier.height(6.dp))
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

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val quickPrompts = listOf(
                        "Open YouTube" to "Hey Myraa, open YouTube",
                        "Open Calculator" to "Myraa, open calculator",
                        "Bluetooth Settings" to "Open Bluetooth settings",
                        "Launch Camera" to "Launch camera",
                        "Open Clock" to "Open clock",
                        "Apps List" to "Show apps",
                        "Torch ON" to "Torch jalao",
                        "Volume UP" to "Awaaz badhao"
                    )
                    quickPrompts.forEach { (label, command) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkSurfaceElevated)
                                .clickable { processCommand(command) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(text = label, color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                AgentStatusGrid(
                    agentReports = agentReports,
                    overallStatus = fleetOverallStatus,
                    onOpenFleet = onNavigateToAgentFleet,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                    placeholder = { Text("Hindi, Hinglish ya English me command likhein...", fontSize = 13.sp) },
                    leadingIcon = {
                        IconButton(
                            onClick = { launchSystemVoiceDialog() },
                            modifier = Modifier.testTag("quick_mic_button")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Voice Input", tint = AuroraEmerald)
                        }
                    },
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

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
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
                            contentDescription = if (isSessionActive) "Stop AI" else "Start AI",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
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
                                text = if (isListeningPaused) "RESUME LISTENING" else "PAUSE LISTENING",
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
                        isSessionActive && isListeningPaused -> "LISTENING PAUSED • TAP RESUME OR STOP AI"
                        isSessionActive -> "AI ACTIVE & LISTENING • TAP RED BUTTON TO STOP AI"
                        else -> "TAP TO START AI (INITIALIZE AGENTS & VOICE)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isSessionActive && isListeningPaused -> AuroraPink
                        isSessionActive -> DangerRed
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
