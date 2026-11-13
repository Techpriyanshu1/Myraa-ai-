package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
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
import com.example.data.pref.SecurePreferences
import com.example.service.RealScreenCaptureService
import com.example.ui.components.ActionConfirmationDialog
import com.example.ui.components.AnimatedAiOrb
import com.example.ui.components.AuroraWaveGlow
import com.example.ui.components.InitializationDiagnosticsDialog
import com.example.ui.components.InstalledAppsDialog
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
import com.example.voice.MyraaSessionCoordinator

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
    val focusManager = LocalFocusManager.current

    val coordinator = remember(context) { MyraaSessionCoordinator.getInstance(context) }
    val prefs = coordinator.prefs

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val isRealScreenSharing by RealScreenCaptureService.isSharingActive.collectAsState()
    val screenShareError by RealScreenCaptureService.lastError.collectAsState()

    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            RealScreenCaptureService.start(context, result.resultCode, result.data!!)
        }
    }

    var textInput by remember { mutableStateOf("") }
    var isAvatarMode by remember { mutableStateOf(prefs.isAvatarModeEnabled()) }

    val isSessionActive by coordinator.isSessionActive.collectAsState()
    val isListeningPaused by coordinator.isListeningPaused.collectAsState()
    val companionState by coordinator.companionState.collectAsState()
    val rmsLevel by coordinator.rmsLevel.collectAsState()
    val liveVoiceStatus by coordinator.liveVoiceStatus.collectAsState()
    val voiceError by coordinator.voiceError.collectAsState()
    val voiceOutputError by coordinator.voiceOutputError.collectAsState()
    val voiceProviderMode by coordinator.voiceProviderMode.collectAsState()
    val effectiveVoiceProvider by coordinator.effectiveVoiceProvider.collectAsState()
    val firstConfiguredVoiceProvider by coordinator.firstConfiguredVoiceProvider.collectAsState()
    val currentSpeechTranscript by coordinator.currentSpeechTranscript.collectAsState()
    val currentAiReply by coordinator.currentAiReply.collectAsState()
    val lastExecutedAction by coordinator.lastExecutedAction.collectAsState()
    val lastActionSuccess by coordinator.lastActionSuccess.collectAsState()
    val showAppsDialog by coordinator.showAppsDialog.collectAsState()
    val launchableApps by coordinator.launchableApps.collectAsState()
    val pendingConfirmation by coordinator.pendingConfirmation.collectAsState()
    val preflightReportDialog by coordinator.preflightReportDialog.collectAsState()

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            coordinator.fleetManager.clearAgentError("voice_recognition")
            coordinator.startSession()
        } else {
            coordinator.fleetManager.refreshFleetStatus()
            coordinator.setErrorMessage(
                "Voice control ke liye Microphone permission zaroori hai. Settings -> Permission Center me jakar enable karein."
            )
        }
    }

    LaunchedEffect(coordinator) {
        coordinator.initializeOnScreenAppear()
        coordinator.navigateToLogsEvent.collect {
            onNavigateToLogs()
        }
    }

    LaunchedEffect(screenShareError) {
        if (!screenShareError.isNullOrBlank()) {
            coordinator.setErrorMessage(screenShareError!!)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coordinator.refreshFleetOnResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Intentionally do NOT destroy voiceManager or stop ContinuousVoiceService here:
            // Voice Mode stays continuously active in background across app switches (YouTube, Screen Share, etc.).
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    pendingConfirmation?.let { pending ->
        ActionConfirmationDialog(
            pending = pending,
            onConfirm = { coordinator.confirmPendingAction() },
            onCancel = { coordinator.cancelPendingAction() }
        )
    }

    preflightReportDialog?.let { report ->
        InitializationDiagnosticsDialog(
            report = report,
            onContinueAnyway = if (report.canStartVoiceSession) {
                { coordinator.continueAnywayAfterPreflight() }
            } else null,
            onDismiss = { coordinator.dismissPreflightDialog() }
        )
    }

    if (showAppsDialog) {
        InstalledAppsDialog(
            apps = launchableApps,
            onLaunchApp = { app -> coordinator.launchInstalledApp(app) },
            onToggleSelectApp = { app -> coordinator.toggleSelectedApp(app.packageName) },
            onDismiss = { coordinator.dismissAppsDialog() }
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
                                    isSessionActive -> "Direct Voice • Background Active"
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
                                coordinator.setErrorMessage("Screen share band kar diya gaya hai.")
                            } else {
                                try {
                                    screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                                } catch (e: Exception) {
                                    coordinator.setErrorMessage(
                                        "Screen share launch nahi ho paaya: ${e.localizedMessage ?: "Unsupported"}"
                                    )
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

                val bannerError = voiceError?.takeIf { it.isNotBlank() } ?: voiceOutputError?.takeIf { it.isNotBlank() }
                if (!bannerError.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = bannerError,
                            color = AuroraPink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Sleek Orb <-> AI Avatar Switch Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(DarkSurfaceElevated)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (!isAvatarMode) CyanPrimary.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable {
                                isAvatarMode = false
                                prefs.setAvatarModeEnabled(false)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("switch_to_orb_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Orb Mode",
                            tint = if (!isAvatarMode) CyanPrimary else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AI Orb",
                            color = if (!isAvatarMode) CyanPrimary else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isAvatarMode) AuroraPink.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable {
                                isAvatarMode = true
                                prefs.setAvatarModeEnabled(true)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("switch_to_avatar_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "AI Avatar Mode",
                            tint = if (isAvatarMode) AuroraPink else TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AI Avatar",
                            color = if (isAvatarMode) AuroraPink else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (isAvatarMode) {
                    VideoAvatarPlayer(
                        companionState = companionState,
                        isScreenShareActive = isRealScreenSharing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isSessionActive && companionState == CompanionState.LISTENING) {
                                coordinator.finishUtteranceNow()
                            }
                            .testTag("video_avatar_player")
                    )
                } else {
                    // Clean Centered Animated AI Orb (Tap while listening to send utterance immediately)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clickable(enabled = isSessionActive && companionState == CompanionState.LISTENING) {
                                coordinator.finishUtteranceNow()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedAiOrb(
                            companionState = companionState,
                            rmsLevel = rmsLevel,
                            size = 220.dp
                        )
                    }
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
                                onClick = { coordinator.replayCurrentReply() },
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
                                    coordinator.processTextCommand(cmd)
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
                                coordinator.processTextCommand(cmd)
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
                                    coordinator.stopSession()
                                } else {
                                    val hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                        PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        coordinator.startSession()
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
                            onClick = { coordinator.finishUtteranceNow() },
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
                                if (isListeningPaused) coordinator.resumeListening() else coordinator.pauseListening()
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

                Spacer(modifier = Modifier.height(10.dp))

                // Sleek Voice Mode API Selector (Auto uses 1st configured API)
                val firstLabel = when (firstConfiguredVoiceProvider) {
                    SecurePreferences.VOICE_PROVIDER_ELEVENLABS -> "1st: 11Labs"
                    SecurePreferences.VOICE_PROVIDER_GEMINI -> "1st: Gemini"
                    else -> "1st API"
                }
                val activeVoiceLabel = if (effectiveVoiceProvider == SecurePreferences.VOICE_PROVIDER_ELEVENLABS) {
                    "ElevenLabs"
                } else {
                    "Gemini Voice"
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(DarkSurfaceElevated)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            Triple(SecurePreferences.VOICE_PROVIDER_AUTO, "Auto ($firstLabel)", "voice_provider_auto"),
                            Triple(SecurePreferences.VOICE_PROVIDER_GEMINI, "Gemini", "voice_provider_gemini"),
                            Triple(SecurePreferences.VOICE_PROVIDER_ELEVENLABS, "ElevenLabs", "voice_provider_elevenlabs")
                        ).forEach { (modeKey, title, tag) ->
                            val selected = voiceProviderMode == modeKey
                            val accent = if (modeKey == SecurePreferences.VOICE_PROVIDER_ELEVENLABS) AuroraPink else CyanPrimary
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) accent.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickable { coordinator.setVoiceProviderMode(modeKey) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag(tag),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    color = if (selected) accent else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Voice Engine: $activeVoiceLabel",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
