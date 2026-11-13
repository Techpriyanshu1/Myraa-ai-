package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.example.automation.DeviceActionExecutor
import com.example.data.api.ElevenLabsClient
import com.example.data.api.GeminiClient
import com.example.data.pref.SecurePreferences
import com.example.service.MyraaOverlayService
import com.example.service.RealScreenCaptureService
import com.example.ui.components.AuroraWaveGlow
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val prefs = remember { SecurePreferences(context) }
    val geminiClient = remember { GeminiClient() }
    val elevenLabsClient = remember { ElevenLabsClient(context) }
    val actionExecutor = remember { DeviceActionExecutor(context) }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val isRealScreenSharing by RealScreenCaptureService.isSharingActive.collectAsState()

    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            RealScreenCaptureService.start(context, result.resultCode, result.data!!)
        }
    }

    var isSessionActive by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var currentSpeechTranscript by remember { mutableStateOf("") }
    var currentAiReply by remember {
        mutableStateOf("Namaste Master! Main Myraa hoon, aapki sweet anime AI companion. Aap mujhse Hindi ya English me baat kar sakte hain ya phone control karne ko keh sakte hain!")
    }
    var lastExecutedAction by remember { mutableStateOf<String?>(null) }
    var selectedLanguageMode by remember { mutableStateOf("auto") } // "auto", "hi-IN", "en-US"

    lateinit var voiceManagerRef: VoiceRecognitionManager

    // Fallback system speech dialog launcher (guarantees voice input works on all devices and emulators)
    val systemVoiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = matches?.firstOrNull()?.trim()
            if (!recognizedText.isNullOrBlank()) {
                currentSpeechTranscript = recognizedText
                // Trigger command processing
                voiceManagerRef.setCompanionState(CompanionState.THINKING)
                coroutineScope.launch {
                    try {
                        val apiKey = prefs.getGeminiApiKey()
                        val parsed = geminiClient.interpretCommand(recognizedText, apiKey)
                        currentAiReply = parsed.spokenResponse
                        lastExecutedAction = parsed.actionType

                        actionExecutor.executeCommand(parsed)

                        voiceManagerRef.setCompanionState(CompanionState.TALKING)
                        elevenLabsClient.speakText(
                            text = parsed.spokenResponse,
                            apiKey = prefs.getVoiceApiKey(),
                            voiceId = prefs.getSelectedVoiceId(),
                            modelId = prefs.getSelectedVoiceModel(),
                            allowNativeFallback = prefs.isNativeTtsFallbackEnabled(),
                            speechSpeed = prefs.getSpeechSpeed(),
                            onPlaybackStarted = {
                                voiceManagerRef.setCompanionState(CompanionState.TALKING)
                            },
                            onPlaybackCompleted = {
                                if (isSessionActive) {
                                    voiceManagerRef.resumeListeningAfterSpeech()
                                } else {
                                    voiceManagerRef.setCompanionState(CompanionState.IDLE)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("MainAssistantScreen", "Error in fallback speech processing", e)
                        voiceManagerRef.setCompanionState(CompanionState.IDLE)
                    }
                }
            }
        }
    }

    fun launchSystemVoiceDialog() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye Master! Speak now in Hindi or English...")
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

    fun processCommand(commandText: String) {
        val trimmed = commandText.trim()
        if (trimmed.isBlank()) return
        currentSpeechTranscript = trimmed

        coroutineScope.launch {
            try {
                voiceManagerRef.setCompanionState(CompanionState.THINKING)
                val apiKey = prefs.getGeminiApiKey()
                val parsed = geminiClient.interpretCommand(trimmed, apiKey)
                currentAiReply = parsed.spokenResponse
                lastExecutedAction = parsed.actionType

                // Execute device action or screen action
                actionExecutor.executeCommand(parsed)

                // Speak response via configured Mahi voice
                voiceManagerRef.setCompanionState(CompanionState.TALKING)
                elevenLabsClient.speakText(
                    text = parsed.spokenResponse,
                    apiKey = prefs.getVoiceApiKey(),
                    voiceId = prefs.getSelectedVoiceId(),
                    modelId = prefs.getSelectedVoiceModel(),
                    allowNativeFallback = prefs.isNativeTtsFallbackEnabled(),
                    speechSpeed = prefs.getSpeechSpeed(),
                    onPlaybackStarted = {
                        voiceManagerRef.setCompanionState(CompanionState.TALKING)
                    },
                    onPlaybackCompleted = {
                        // Resilient continuous loop: immediately resume listening after speech!
                        if (isSessionActive) {
                            voiceManagerRef.resumeListeningAfterSpeech()
                        } else {
                            voiceManagerRef.setCompanionState(CompanionState.IDLE)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("MainAssistantScreen", "Error during command processing", e)
                if (isSessionActive) {
                    voiceManagerRef.resumeListeningAfterSpeech()
                } else {
                    voiceManagerRef.setCompanionState(CompanionState.IDLE)
                }
            }
        }
    }

    val voiceManager = remember {
        VoiceRecognitionManager(
            context = context,
            onFallbackIntentNeeded = {
                launchSystemVoiceDialog()
            },
            onCommandReceived = { recognizedText ->
                processCommand(recognizedText)
            }
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
            isSessionActive = true
            voiceManager.startContinuousListening()
            if (prefs.isOverlayEnabled() && Settings.canDrawOverlays(context)) {
                MyraaOverlayService.start(context)
            }
            coroutineScope.launch {
                voiceManager.setCompanionState(CompanionState.TALKING)
                elevenLabsClient.speakText(
                    text = "Namaste Master! Myraa active hai aur poore dhyan se aapki baat sun rahi hai. Boliye main aapki kya madad karu?",
                    apiKey = prefs.getVoiceApiKey(),
                    voiceId = prefs.getSelectedVoiceId(),
                    modelId = prefs.getSelectedVoiceModel(),
                    allowNativeFallback = prefs.isNativeTtsFallbackEnabled(),
                    speechSpeed = prefs.getSpeechSpeed(),
                    onPlaybackCompleted = {
                        if (isSessionActive) voiceManager.resumeListeningAfterSpeech()
                    }
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceManager.destroy()
            elevenLabsClient.shutdown()
            com.example.service.ContinuousVoiceService.stop(context)
            RealScreenCaptureService.stop(context)
        }
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
                                .background(if (isSessionActive) AuroraEmerald else CyanPrimary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Myraa AI",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    // Real MediaProjection Screen Share Button
                    IconButton(
                        onClick = {
                            if (isRealScreenSharing) {
                                RealScreenCaptureService.stop(context)
                            } else {
                                if (!Settings.canDrawOverlays(context)) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } else {
                                    screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                                }
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

                    // Logs Navigation
                    IconButton(
                        onClick = onNavigateToLogs,
                        modifier = Modifier.testTag("nav_to_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = "Logs",
                            tint = TextSecondary
                        )
                    }

                    // Settings Navigation
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("nav_to_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        bottomBar = {
            AuroraWaveGlow(
                isActive = isSessionActive,
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
                // Language Selection Chips
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
                        },
                        label = { Text("🌐 Auto (Hinglish)", fontSize = 11.sp) },
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
                        },
                        label = { Text("🇮🇳 Hindi", fontSize = 11.sp) },
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
                        },
                        label = { Text("🇺🇸 English", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VioletSecondary.copy(alpha = 0.25f),
                            selectedLabelColor = VioletSecondary
                        )
                    )
                }

                // Real Screen Share Active Banner
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
                            Icon(
                                imageVector = Icons.Default.Mouse,
                                contentDescription = null,
                                tint = AuroraPink,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "REAL SCREEN SHARE ACTIVE • AI CONTROLLING PHONE",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Animated Avatar / Orb Display Toggle
                var displayModeOrb by remember { mutableStateOf(false) }

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
                    ) {
                        Text(
                            text = if (displayModeOrb) "Switch to Anime Character" else "Switch to AI Orb",
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
                            .height(280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        com.example.ui.components.AnimatedAiOrb(
                            companionState = companionState,
                            rmsLevel = rmsLevel,
                            size = 240.dp
                        )
                    }
                } else {
                    // BIG ANIMATION CHARACTER DISPLAY WITH NO BORDER
                    VideoAvatarPlayer(
                        companionState = companionState,
                        isScreenShareActive = isRealScreenSharing,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Dialogue Transcript Card
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
                                Text(
                                    text = "You: ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyanPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "\"$displayPrompt\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
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
                                    text = "Myraa (Mahi Voice):",
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

                            // Replay audio button
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        voiceManagerRef.setCompanionState(CompanionState.TALKING)
                                        elevenLabsClient.speakText(
                                            text = currentAiReply,
                                            apiKey = prefs.getVoiceApiKey(),
                                            voiceId = prefs.getSelectedVoiceId(),
                                            modelId = prefs.getSelectedVoiceModel(),
                                            allowNativeFallback = prefs.isNativeTtsFallbackEnabled(),
                                            speechSpeed = prefs.getSpeechSpeed(),
                                            onPlaybackCompleted = {
                                                if (isSessionActive) voiceManagerRef.resumeListeningAfterSpeech()
                                                else voiceManagerRef.setCompanionState(CompanionState.IDLE)
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.size(36.dp)
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
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AuroraEmerald.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "⚡ Action Executed: $lastExecutedAction",
                                    color = AuroraEmerald,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Prompt Suggestion Chips for Fast Testing
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val quickPrompts = listOf(
                        "👋 Namaste Myraa!" to "Namaste Myraa, kaise ho aap?",
                        "😂 Chutkula sunao" to "Mujhe ek funny chutkula sunao",
                        "❤️ I love you" to "I love you Myraa, kya tum meri girlfriend banogi?",
                        "🔦 Torch jalao" to "Torch jalao",
                        "📱 YouTube open karo" to "Open YouTube",
                        "🎵 Gaana gao" to "Koi pyara sa gaana gao",
                        "🖱️ Screen share" to "Screen share chalao",
                        "🔊 Awaaz badhao" to "Awaaz badhao"
                    )

                    quickPrompts.forEach { (label, command) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkSurfaceElevated)
                                .clickable { processCommand(command) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                color = CyanPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Active Sub-Agent Fleet Status
                com.example.ui.components.AgentStatusGrid(
                    isAccessibilityGranted = com.example.service.MyraaAccessibilityService.isRunning(),
                    hasGeminiKey = prefs.getGeminiApiKey().isNotBlank(),
                    isVoiceSessionActive = isSessionActive,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Section: Keyboard Input, Mic Button, and Glowing START/STOP Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                // Keyboard Text Input Field with Mic Button & Send Button
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("command_keyboard_input"),
                    placeholder = { Text("Hindi ya English me likhiye ya boliye...", fontSize = 13.sp) },
                    leadingIcon = {
                        IconButton(
                            onClick = { launchSystemVoiceDialog() },
                            modifier = Modifier.testTag("quick_mic_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice Input",
                                tint = AuroraEmerald
                            )
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
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = CyanPrimary
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

                // Giant Glowing Cyberpunk START / STOP Button
                val pulseTransition = rememberInfiniteTransition(label = "pulse_button")
                val pulseScale by pulseTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isSessionActive) 1.07f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_scale"
                )

                Box(
                    modifier = Modifier
                        .scale(pulseScale)
                        .size(76.dp)
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
                                isSessionActive = false
                                voiceManager.stopContinuousListening()
                                elevenLabsClient.stopSpeaking()
                                com.example.service.ContinuousVoiceService.stop(context)
                                MyraaOverlayService.stop(context)
                            } else {
                                val hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                        android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    isSessionActive = true
                                    voiceManager.startContinuousListening()
                                    com.example.service.ContinuousVoiceService.start(context)
                                    if (prefs.isOverlayEnabled() && Settings.canDrawOverlays(context)) {
                                        MyraaOverlayService.start(context)
                                    }
                                    coroutineScope.launch {
                                        voiceManager.setCompanionState(CompanionState.TALKING)
                                        elevenLabsClient.speakText(
                                            text = "Namaste Master! Myraa active hai aur sun rahi hai. Boliye kya hukum hai?",
                                            apiKey = prefs.getVoiceApiKey(),
                                            voiceId = prefs.getSelectedVoiceId(),
                                            modelId = prefs.getSelectedVoiceModel(),
                                            allowNativeFallback = prefs.isNativeTtsFallbackEnabled(),
                                            speechSpeed = prefs.getSpeechSpeed(),
                                            onPlaybackCompleted = {
                                                if (isSessionActive) voiceManager.resumeListeningAfterSpeech()
                                            }
                                        )
                                    }
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
                        contentDescription = if (isSessionActive) "Stop" else "Start",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isSessionActive) "LISTENING CONTINUOUSLY • TAP TO STOP" else "TAP TO START MYRAA (CONTINUOUS VOICE)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSessionActive) DangerRed else CyanPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
