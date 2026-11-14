package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.agents.AgentFleetManager
import com.example.data.api.ConnectionTestResult
import com.example.data.api.ElevenLabsClient
import com.example.data.api.GeminiClient
import com.example.data.api.VoiceTestResult
import com.example.data.pref.SecurePreferences
import com.example.service.MyraaAccessibilityService
import com.example.ui.components.AnimatedAiOrb
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.AuroraPink
import com.example.ui.theme.CardGlowBorder
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onSetupCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val prefs = remember { SecurePreferences(context) }
    val geminiClient = remember { GeminiClient() }
    val elevenLabsClient = remember { ElevenLabsClient(context) }
    val fleetManager = remember { AgentFleetManager.getInstance(context, prefs) }

    // State: Gemini
    var geminiKey by remember { mutableStateOf(prefs.getGeminiApiKey()) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var selectedGeminiModel by remember { mutableStateOf(prefs.getSelectedGeminiModel()) }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }
    var isTestingGemini by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<ConnectionTestResult?>(null) }

    // State: Optional Voice & Permissions Section
    var showAdvancedOptions by remember { mutableStateOf(false) }
    var voiceKey by remember { mutableStateOf(prefs.getVoiceApiKey()) }
    var showVoiceKey by remember { mutableStateOf(false) }
    var mahiVoiceId by remember { mutableStateOf(prefs.getSelectedVoiceId()) }
    val voiceModel by remember { mutableStateOf(prefs.getSelectedVoiceModel()) }
    var isTestingVoice by remember { mutableStateOf(false) }
    var voiceTestResult by remember { mutableStateOf<VoiceTestResult?>(null) }

    // Permissions State
    var isMicPermissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var isOverlayPermissionGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var isAccessibilityGranted by remember {
        mutableStateOf(MyraaAccessibilityService.isRunning())
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isMicPermissionGranted = granted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isMicPermissionGranted =
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                isAccessibilityGranted = MyraaAccessibilityService.isRunning()
                isOverlayPermissionGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            elevenLabsClient.shutdown()
        }
    }

    fun persistSettingsAndProceed(effectiveModel: String, isOfflineOnly: Boolean) {
        if (!isOfflineOnly) {
            prefs.setGeminiApiKey(geminiKey.trim())
            prefs.setSelectedGeminiModel(effectiveModel)
            prefs.setOfflineModeAccepted(false)
        } else {
            prefs.setOfflineModeAccepted(true)
        }
        prefs.setVoiceApiKey(voiceKey.trim())
        prefs.setSelectedVoiceId(mahiVoiceId)
        prefs.setSelectedVoiceModel(voiceModel)
        prefs.setSetupCompleted(true)
        MyraaSessionCoordinator.getInstance(context).refreshVoiceProviderState()
        fleetManager.refreshFleetStatus(clearErrors = true)
        onSetupCompleted()
    }

    Scaffold(
        containerColor = DarkBackground,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Compact Glowing Hero Header
            AnimatedAiOrb(
                companionState = if (isTestingGemini) CompanionState.THINKING else CompanionState.IDLE,
                size = 94.dp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(CyanPrimary.copy(alpha = 0.2f), VioletSecondary.copy(alpha = 0.2f))
                        )
                    )
                    .border(
                        1.dp,
                        Brush.horizontalGradient(listOf(CyanPrimary, VioletSecondary)),
                        RoundedCornerShape(30.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "MYRAA AI • QUICK START",
                    color = CyanPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Activate MYRAA AI",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Paste your Gemini API key below to unlock Hindi, Hinglish & English voice intelligence.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                textAlign = TextAlign.Center
            )

            // Primary Card: Direct Gemini API Key Setup & Verification
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CardGlowBorder, RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(VioletSecondary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = VioletSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Gemini API Key",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Stored locally via Android Keystore",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AuroraEmerald,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        TextButton(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                                    )
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Get Key", color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(3.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Get Gemini API Key",
                                tint = CyanPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = {
                            geminiKey = it
                            geminiTestResult = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_key_input"),
                        label = { Text("Paste Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val pasted = clipboardManager.getText()?.text?.trim().orEmpty()
                                        if (pasted.isNotBlank()) {
                                            geminiKey = pasted
                                            geminiTestResult = null
                                        }
                                    },
                                    modifier = Modifier.testTag("paste_gemini_key_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste API Key",
                                        tint = CyanPrimary
                                    )
                                }
                                IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                    Icon(
                                        imageVector = if (showGeminiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Visibility",
                                        tint = TextSecondary
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = DarkSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DarkSurfaceElevated,
                            unfocusedContainerColor = DarkSurfaceElevated
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Model Selector Dropdown
                    ExposedDropdownMenuBox(
                        expanded = isModelDropdownExpanded,
                        onExpandedChange = { isModelDropdownExpanded = !isModelDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedGeminiModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Gemini Model") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isModelDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = DarkSurfaceElevated,
                                unfocusedContainerColor = DarkSurfaceElevated
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = isModelDropdownExpanded,
                            onDismissRequest = { isModelDropdownExpanded = false },
                            modifier = Modifier.background(DarkSurfaceElevated)
                        ) {
                            GeminiClient.AVAILABLE_MODELS.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model, color = TextPrimary) },
                                    onClick = {
                                        selectedGeminiModel = model
                                        isModelDropdownExpanded = false
                                        geminiTestResult = null
                                    }
                                )
                            }
                        }
                    }

                    // Verification Result Feedback Banner
                    geminiTestResult?.let { res ->
                        Spacer(modifier = Modifier.height(10.dp))
                        val isSuccess = res is ConnectionTestResult.Success
                        val statusColor = if (isSuccess) AuroraEmerald else DangerRed
                        val msg = when (res) {
                            is ConnectionTestResult.Success -> res.message
                            is ConnectionTestResult.Failure -> res.errorMessage
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(statusColor.copy(alpha = 0.14f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = msg,
                                color = statusColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (res is ConnectionTestResult.Failure &&
                            res.errorMessage.contains("Network", ignoreCase = true) &&
                            geminiKey.trim().isNotBlank()
                        ) {
                            TextButton(
                                onClick = { persistSettingsAndProceed(selectedGeminiModel, isOfflineOnly = false) },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    text = "Save Key & Continue Offline →",
                                    color = CyanPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Microphone Quick Grant Strip (one-tap before starting)
            if (!isMicPermissionGranted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkSurface)
                        .border(1.dp, CyanPrimary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Microphone Access Required for Voice",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap Allow so MYRAA can hear you",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(CyanPrimary)
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text("Allow", color = Color(0xFF04141E), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Primary Verify & Activate CTA
            Button(
                onClick = {
                    if (isTestingGemini) return@Button
                    focusManager.clearFocus()
                    val cleanKey = geminiKey.trim()
                    if (cleanKey.isBlank()) {
                        geminiTestResult = ConnectionTestResult.Failure(
                            "Please enter your Gemini API key above, or tap Skip below for offline device controls."
                        )
                        return@Button
                    }
                    isTestingGemini = true
                    geminiTestResult = null
                    coroutineScope.launch {
                        val result = geminiClient.testConnection(cleanKey, selectedGeminiModel)
                        isTestingGemini = false
                        geminiTestResult = result
                        if (result is ConnectionTestResult.Success) {
                            selectedGeminiModel = result.modelUsed
                            persistSettingsAndProceed(result.modelUsed, isOfflineOnly = false)
                        }
                    }
                },
                enabled = !isTestingGemini,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("activate_agents_button"),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isTestingGemini) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF04141E)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "VERIFYING GEMINI KEY...",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF04141E),
                        fontWeight = FontWeight.ExtraBold
                    )
                } else {
                    Text(
                        text = "VERIFY API KEY & START MYRAA",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF04141E),
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            TextButton(
                onClick = {
                    persistSettingsAndProceed(selectedGeminiModel, isOfflineOnly = true)
                },
                modifier = Modifier.testTag("skip_offline_setup_button")
            ) {
                Text(
                    text = "Continue without Gemini Key (Local Device Controls Only)",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Collapsible Optional Voice (ElevenLabs Mahi) & System Permissions Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvancedOptions = !showAdvancedOptions },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mahi Voice & Device Permissions (Optional)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Gemini AI Voice (Mahi) active automatically • Tap for ElevenLabs or Accessibility",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = if (showAdvancedOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle Optional Settings",
                            tint = CyanPrimary
                        )
                    }

                    AnimatedVisibility(visible = showAdvancedOptions) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        tint = AuroraPink,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "ElevenLabs Voice (Mahi)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (!isTestingVoice) {
                                            isTestingVoice = true
                                            voiceTestResult = null
                                            val cleanVoice = voiceKey.trim()
                                            if (cleanVoice.isNotBlank()) {
                                                prefs.setVoiceApiKey(cleanVoice)
                                            }
                                            coroutineScope.launch {
                                                voiceTestResult = elevenLabsClient.testVoiceConnection(
                                                    apiKey = cleanVoice,
                                                    voiceId = mahiVoiceId,
                                                    modelId = voiceModel
                                                )
                                                isTestingVoice = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("test_voice_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AuroraPink),
                                    border = BorderStroke(1.dp, AuroraPink),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    if (isTestingVoice) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = AuroraPink
                                        )
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Test Mahi", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = voiceKey,
                                onValueChange = { voiceKey = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_key_input"),
                                label = { Text("ElevenLabs API Key (Optional)") },
                                placeholder = { Text("sk_... (blank = Gemini Neural AI Voice)") },
                                visualTransformation = if (showVoiceKey) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showVoiceKey = !showVoiceKey }) {
                                        Icon(
                                            imageVector = if (showVoiceKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle Visibility",
                                            tint = TextSecondary
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AuroraPink,
                                    unfocusedBorderColor = DarkSurfaceBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedContainerColor = DarkSurfaceElevated,
                                    unfocusedContainerColor = DarkSurfaceElevated
                                ),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = mahiVoiceId,
                                onValueChange = { mahiVoiceId = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("mahi_voice_id_input"),
                                label = { Text("Configured Mahi Voice ID") },
                                placeholder = { Text("e.g. 21m00Tcm4TlvDq8ikWAM") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AuroraPink,
                                    unfocusedBorderColor = DarkSurfaceBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedContainerColor = DarkSurfaceElevated,
                                    unfocusedContainerColor = DarkSurfaceElevated
                                ),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "AI Voice Only: MYRAA uses Gemini Neural Voice automatically (or ElevenLabs if key provided).",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanPrimary,
                                fontSize = 11.sp
                            )

                            voiceTestResult?.let { res ->
                                Spacer(modifier = Modifier.height(6.dp))
                                when (res) {
                                    is VoiceTestResult.Success -> Text(
                                        text = "✓ ${res.message}",
                                        color = AuroraEmerald,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    is VoiceTestResult.Failure -> Text(
                                        text = "✕ ${res.errorMessage}",
                                        color = DangerRed,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Device Automation Permissions",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            PermissionItemRow(
                                title = "Microphone Permission",
                                desc = "Hands-free speech recognition",
                                icon = Icons.Default.Mic,
                                isGranted = isMicPermissionGranted,
                                actionLabel = if (isMicPermissionGranted) "Active" else "Allow",
                                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            PermissionItemRow(
                                title = "Accessibility Service",
                                desc = "Tap buttons, scroll, home & back",
                                icon = Icons.Default.TouchApp,
                                isGranted = isAccessibilityGranted,
                                actionLabel = if (isAccessibilityGranted) "Active" else "Enable",
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            PermissionItemRow(
                                title = "Draw Over Other Apps",
                                desc = "Floating Aurora light overlay",
                                icon = Icons.Default.Layers,
                                isGranted = isOverlayPermissionGranted,
                                actionLabel = if (isOverlayPermissionGranted) "Active" else "Enable",
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionItemRow(
    title: String,
    desc: String,
    icon: ImageVector,
    isGranted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceElevated)
            .border(
                1.dp,
                if (isGranted) AuroraEmerald.copy(alpha = 0.4f) else DarkSurfaceBorder,
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) AuroraEmerald else CyanPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isGranted) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AuroraEmerald.copy(alpha = 0.2f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AuroraEmerald,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ACTIVE",
                        color = AuroraEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = VioletSecondary),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
