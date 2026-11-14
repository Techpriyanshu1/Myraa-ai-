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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.voice.CompanionState
import com.example.voice.MyraaSessionCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAssistantScreen(
    onNavigateToLogs: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemory: () -> Unit = {},
    onNavigateToAgentFleet: () -> Unit = {},
    onNavigateToWebBuilder: (String?) -> Unit = {},
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
    var showBossVoiceDialog by remember { mutableStateOf(false) }

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

    val speakerVerification by coordinator.speakerVerificationState.collectAsState()
    val isBossVoiceEnrolled by coordinator.isBossVoiceEnrolled.collectAsState()
    val isBossVoiceGuardEnabled by coordinator.isBossVoiceGuardEnabled.collectAsState()
    val isEnrollingBossVoice by coordinator.isEnrollingBossVoice.collectAsState()
    val bossEnrollmentStatus by coordinator.bossEnrollmentStatus.collectAsState()

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            coordinator.fleetManager.clearAgentError("voice_recognition")
            coordinator.startSession()
        } else {
            coordinator.fleetManager.refreshFleetStatus()
            coordinator.setErrorMessage(
                "Voice control ke liye Microphone permission zaroori hai. Settings -> Permission Center me enable karein."
            )
        }
    }

    val enrollMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            coordinator.enrollBossVoiceSample()
        }
    }

    LaunchedEffect(coordinator) {
        coordinator.initializeOnScreenAppear()
        launch {
            coordinator.navigateToLogsEvent.collect { onNavigateToLogs() }
        }
        launch {
            coordinator.navigateToWebBuilderEvent.collect { webPrompt ->
                onNavigateToWebBuilder(webPrompt)
            }
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    if (showBossVoiceDialog) {
        BossVoiceGuardDialog(
            isEnrolled = isBossVoiceEnrolled,
            isGuardEnabled = isBossVoiceGuardEnabled,
            isCapturing = isEnrollingBossVoice,
            statusMessage = bossEnrollmentStatus,
            onCaptureSample = {
                val hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    coordinator.enrollBossVoiceSample()
                } else {
                    enrollMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onToggleGuard = { coordinator.setBossVoiceGuardEnabled(it) },
            onClearVoiceprint = { coordinator.clearBossVoiceprint() },
            onDismiss = { showBossVoiceDialog = false }
        )
    }

    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
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
                                        isSessionActive && !isListeningPaused -> colors.primary
                                        isSessionActive && isListeningPaused -> colors.tertiary
                                        else -> colors.secondary
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "MYRAA AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = when {
                                    isSessionActive && isListeningPaused -> "Voice Paused"
                                    isSessionActive -> "Direct Voice Active"
                                    else -> "Material You • Ready"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onNavigateToWebBuilder(null) },
                        modifier = Modifier.testTag("nav_to_web_builder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Web Studio",
                            tint = colors.primary
                        )
                    }
                    IconButton(
                        onClick = onNavigateToMemory,
                        modifier = Modifier.testTag("nav_to_memory_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "Memory Core",
                            tint = colors.secondary
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
                            imageVector = if (isRealScreenSharing) {
                                Icons.AutoMirrored.Filled.StopScreenShare
                            } else {
                                Icons.AutoMirrored.Filled.ScreenShare
                            },
                            contentDescription = "Screen Share",
                            tint = if (isRealScreenSharing) colors.tertiary else colors.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNavigateToLogs,
                        modifier = Modifier.testTag("nav_to_logs_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = "Activity History",
                            tint = colors.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("nav_to_settings_button")
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = colors.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
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
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
            ) {
                if (!prefs.hasStoredGeminiKey()) {
                    Surface(
                        color = colors.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clickable { onNavigateToSettings() }
                            .testTag("missing_gemini_key_banner")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                tint = colors.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Gemini API Key missing • Tap to configure",
                                color = colors.onSecondaryContainer,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Unknown / Different Voice Biometric Alert Banner
                val activeVerification = speakerVerification
                if (activeVerification != null && activeVerification.isEnrolled && !activeVerification.isBossMatch) {
                    Surface(
                        color = colors.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .testTag("unknown_voice_warning_banner")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Different voice alert",
                                tint = colors.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Warning: Different Voice Detected (${activeVerification.similarityPercent}%)",
                                    color = colors.onErrorContainer,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "Yeh Boss/Jaan ki awaaz nahi hai (${activeVerification.measuredPitchHz.toInt()} Hz vs ${activeVerification.enrolledPitchHz.toInt()} Hz).",
                                    color = colors.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (isRealScreenSharing) {
                    Surface(
                        color = colors.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Mouse,
                                contentDescription = null,
                                tint = colors.onTertiaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SCREEN SHARE ACTIVE • LIVE CURSOR READY",
                                color = colors.onTertiaryContainer,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                val bannerError = voiceError?.takeIf { it.isNotBlank() } ?: voiceOutputError?.takeIf { it.isNotBlank() }
                if (!bannerError.isNullOrBlank()) {
                    Surface(
                        color = colors.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = bannerError,
                            color = colors.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // Material You Top Pill Row: Orb <-> AI Avatar Switcher + Boss Voice ID Guard
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = colors.surfaceContainerHigh,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (!isAvatarMode) colors.primaryContainer else Color.Transparent)
                                    .clickable {
                                        isAvatarMode = false
                                        prefs.setAvatarModeEnabled(false)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .testTag("switch_to_orb_button"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "AI Orb Mode",
                                    tint = if (!isAvatarMode) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Orb",
                                    color = if (!isAvatarMode) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isAvatarMode) colors.tertiaryContainer else Color.Transparent)
                                    .clickable {
                                        isAvatarMode = true
                                        prefs.setAvatarModeEnabled(true)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .testTag("switch_to_avatar_button"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Face,
                                    contentDescription = "AI Avatar Mode",
                                    tint = if (isAvatarMode) colors.onTertiaryContainer else colors.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Avatar",
                                    color = if (isAvatarMode) colors.onTertiaryContainer else colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Boss/Jaan Voice ID Quick Chip
                    val bossChipColor = when {
                        activeVerification != null && activeVerification.isEnrolled && !activeVerification.isBossMatch -> colors.errorContainer
                        isBossVoiceEnrolled && isBossVoiceGuardEnabled -> colors.primaryContainer
                        else -> colors.surfaceContainerHigh
                    }
                    val bossChipContent = when {
                        activeVerification != null && activeVerification.isEnrolled && !activeVerification.isBossMatch -> colors.onErrorContainer
                        isBossVoiceEnrolled && isBossVoiceGuardEnabled -> colors.onPrimaryContainer
                        else -> colors.onSurfaceVariant
                    }
                    Surface(
                        color = bossChipColor,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showBossVoiceDialog = true }
                            .testTag("boss_voice_guard_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isBossVoiceEnrolled) Icons.Default.VerifiedUser else Icons.Default.Security,
                                contentDescription = "Boss Voice Recognition",
                                tint = bossChipContent,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = when {
                                    activeVerification != null && !activeVerification.isBossMatch -> "Stranger!"
                                    isBossVoiceEnrolled && isBossVoiceGuardEnabled -> "Boss ID"
                                    else -> "Set Voice ID"
                                },
                                color = bossChipContent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(235.dp)
                            .clickable(enabled = isSessionActive && companionState == CompanionState.LISTENING) {
                                coordinator.finishUtteranceNow()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedAiOrb(
                            companionState = companionState,
                            rmsLevel = rmsLevel,
                            size = 210.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Material You Conversation Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val displayPrompt = when {
                            liveVoiceStatus.isNotBlank() && companionState == CompanionState.LISTENING -> liveVoiceStatus
                            currentSpeechTranscript.isNotBlank() -> currentSpeechTranscript
                            else -> null
                        }
                        if (displayPrompt != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "You: ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    displayPrompt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
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
                                    text = "MYRAA • AI Neural Voice",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.tertiary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = currentAiReply,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            IconButton(
                                onClick = { coordinator.replayCurrentReply() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("replay_voice_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Replay AI Voice",
                                    tint = colors.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        if (!lastExecutedAction.isNullOrBlank() && lastExecutedAction != "CHAT") {
                            Spacer(modifier = Modifier.height(8.dp))
                            val containerBg = if (lastActionSuccess) colors.primaryContainer else colors.errorContainer
                            val containerFg = if (lastActionSuccess) colors.onPrimaryContainer else colors.onErrorContainer
                            Surface(
                                color = containerBg,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (lastActionSuccess) "Executed: $lastExecutedAction" else "Action Failed: $lastExecutedAction",
                                    color = containerFg,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Clean Material You Controls & Direct Voice Bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("command_keyboard_input"),
                    placeholder = { Text("Ask MYRAA or say 'build a website for...'") },
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
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = colors.primary
                            )
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
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant,
                        focusedContainerColor = colors.surfaceContainerHigh,
                        unfocusedContainerColor = colors.surfaceContainerLow
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp)
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
                            animation = tween(1100, easing = FastOutSlowInEasing),
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
                                Brush.linearGradient(
                                    colors = if (isSessionActive) {
                                        listOf(colors.error, colors.tertiary)
                                    } else {
                                        listOf(colors.primary, colors.secondary)
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
                            tint = if (isSessionActive) colors.onError else colors.onPrimary,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    if (isSessionActive && companionState == CompanionState.LISTENING) {
                        FilledTonalButton(
                            onClick = { coordinator.finishUtteranceNow() },
                            modifier = Modifier.testTag("send_voice_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Send Voice Now",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SEND", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isSessionActive) {
                        OutlinedButton(
                            onClick = {
                                if (isListeningPaused) coordinator.resumeListening() else coordinator.pauseListening()
                            },
                            border = BorderStroke(1.dp, colors.outline),
                            modifier = Modifier.testTag("pause_resume_listening_button")
                        ) {
                            Icon(
                                imageVector = if (isListeningPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isListeningPaused) "Resume Listening" else "Pause Listening",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isListeningPaused) "RESUME" else "PAUSE", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = when {
                        isSessionActive && isListeningPaused -> "VOICE PAUSED • TAP RESUME OR STOP"
                        isSessionActive && companionState == CompanionState.THINKING -> "THINKING FAST..."
                        isSessionActive && companionState == CompanionState.TALKING -> "SPEAKING IN PURE AI VOICE..."
                        isSessionActive -> "LISTENING DIRECTLY • SPEAK NATURALLY"
                        else -> "TAP MIC FOR FAST VOICE-TO-VOICE AI"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSessionActive && isListeningPaused) colors.tertiary else colors.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Clean Voice Engine Indicator (Multi-provider switcher shown only if ElevenLabs key is also added)
                val hasElevenLabsKey = prefs.hasStoredVoiceKey()
                if (hasElevenLabsKey) {
                    val firstLabel = when (firstConfiguredVoiceProvider) {
                        SecurePreferences.VOICE_PROVIDER_ELEVENLABS -> "1st: 11Labs"
                        SecurePreferences.VOICE_PROVIDER_GEMINI -> "1st: Gemini"
                        else -> "1st API"
                    }
                    Surface(
                        color = colors.surfaceContainerHigh,
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                Triple(SecurePreferences.VOICE_PROVIDER_AUTO, "Auto ($firstLabel)", "voice_provider_auto"),
                                Triple(SecurePreferences.VOICE_PROVIDER_GEMINI, "Gemini", "voice_provider_gemini"),
                                Triple(SecurePreferences.VOICE_PROVIDER_ELEVENLABS, "ElevenLabs", "voice_provider_elevenlabs")
                            ).forEach { (modeKey, title, tag) ->
                                val selected = voiceProviderMode == modeKey
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selected) colors.primaryContainer else Color.Transparent)
                                        .clickable { coordinator.setVoiceProviderMode(modeKey) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag(tag),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                val activeVoiceLabel = if (hasElevenLabsKey && effectiveVoiceProvider == SecurePreferences.VOICE_PROVIDER_ELEVENLABS) {
                    "ElevenLabs"
                } else {
                    "Gemini Neural (Mahi)"
                }
                Surface(
                    color = colors.surfaceContainerLow,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "AI Voice: $activeVoiceLabel",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BossVoiceGuardDialog(
    isEnrolled: Boolean,
    isGuardEnabled: Boolean,
    isCapturing: Boolean,
    statusMessage: String?,
    onCaptureSample: () -> Unit,
    onToggleGuard: (Boolean) -> Unit,
    onClearVoiceprint: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = colors.primary
            )
        },
        title = {
            Text("Boss / Jaan Voice Recognition")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Apni awaaz (Boss / Priyanshu) record karke save karein. Jab bhi koi doosra insaan bolega, MYRAA turant alert cue bajayegi aur voice warning degi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )

                Surface(
                    color = if (isEnrolled) colors.primaryContainer else colors.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isEnrolled) {
                            "Boss Voice ID: Enrolled & ${if (isGuardEnabled) "Active" else "Paused"}"
                        } else {
                            "Boss Voice ID: Not enrolled yet"
                        },
                        color = if (isEnrolled) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                if (!statusMessage.isNullOrBlank()) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.primary
                    )
                }

                Button(
                    onClick = onCaptureSample,
                    enabled = !isCapturing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("enroll_boss_voice_button")
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Listening 2.6s... Speak Now")
                    } else {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isEnrolled) "Add Another Voice Sample" else "Record Boss Voice (2.6s)")
                    }
                }

                if (isEnrolled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onToggleGuard(!isGuardEnabled) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isGuardEnabled) "Pause Guard" else "Enable Guard")
                        }
                        OutlinedButton(
                            onClick = onClearVoiceprint,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset ID")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
