package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.ConnectionTestResult
import com.example.data.api.ElevenLabsClient
import com.example.data.api.GeminiClient
import com.example.data.api.VoiceTestResult
import com.example.data.pref.SecurePreferences
import com.example.service.MyraaAccessibilityService
import com.example.shizuku.ShizukuBridge
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onSetupCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { SecurePreferences(context) }
    val geminiClient = remember { GeminiClient() }
    val elevenLabsClient = remember { ElevenLabsClient(context) }

    // State: Gemini
    var geminiKey by remember { mutableStateOf(prefs.getGeminiApiKey()) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var selectedGeminiModel by remember { mutableStateOf(prefs.getSelectedGeminiModel()) }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }
    var isTestingGemini by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<ConnectionTestResult?>(null) }

    // State: ElevenLabs & Mahi Voice
    var voiceKey by remember { mutableStateOf(prefs.getVoiceApiKey()) }
    var showVoiceKey by remember { mutableStateOf(false) }
    var mahiVoiceId by remember { mutableStateOf(prefs.getSelectedVoiceId()) }
    var voiceModel by remember { mutableStateOf(prefs.getSelectedVoiceModel()) }
    var isTestingVoice by remember { mutableStateOf(false) }
    var voiceTestResult by remember { mutableStateOf<VoiceTestResult?>(null) }
    var nativeTtsFallback by remember { mutableStateOf(prefs.isNativeTtsFallbackEnabled()) }

    // Permissions State
    var isMicPermissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
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

    // Refresh permission statuses on resume
    LaunchedEffect(Unit) {
        isAccessibilityGranted = MyraaAccessibilityService.isRunning()
        isOverlayPermissionGranted = Settings.canDrawOverlays(context)
    }

    Scaffold(
        containerColor = DarkBackground,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Header with glowing badge
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
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "MYRAA AI INITIALIZATION",
                    color = CyanPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Welcome to Myraa AI",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = "Your private voice-controlled assistant with Mahi's warm Indian voice. Configure your credentials securely.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // Step 1: Gemini API Key Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CardGlowBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
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
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(VioletSecondary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = VioletSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "1. Gemini Intelligence",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Encrypted in Android Keystore",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraEmerald,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Test Gemini Connection Button
                        OutlinedButton(
                            onClick = {
                                if (!isTestingGemini) {
                                    isTestingGemini = true
                                    geminiTestResult = null
                                    coroutineScope.launch {
                                        geminiTestResult = geminiClient.testConnection(
                                            apiKey = geminiKey,
                                            selectedModel = selectedGeminiModel
                                        )
                                        isTestingGemini = false
                                    }
                                }
                            },
                            modifier = Modifier.testTag("test_gemini_connection_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            if (isTestingGemini) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = CyanPrimary
                                )
                            } else {
                                Text("Test Connection", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_key_input"),
                        label = { Text("Gemini API Key (Google AI Studio)") },
                        placeholder = { Text("AIzaSy...") },
                        visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                Icon(
                                    imageVector = if (showGeminiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Visibility",
                                    tint = TextSecondary
                                )
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
                            label = { Text("Gemini Model Version") },
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
                            onDismissRequest = { isModelDropdownExpanded = false }
                        ) {
                            GeminiClient.AVAILABLE_MODELS.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        selectedGeminiModel = model
                                        isModelDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Test Result Feedback Banner
                    geminiTestResult?.let { res ->
                        Spacer(modifier = Modifier.height(10.dp))
                        when (res) {
                            is ConnectionTestResult.Success -> {
                                Text(
                                    text = "✓ ${res.message}",
                                    color = AuroraEmerald,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            is ConnectionTestResult.Failure -> {
                                Text(
                                    text = "✕ ${res.errorMessage}",
                                    color = DangerRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Step 2: Voice API & Mahi Voice Preset Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CardGlowBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
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
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AuroraPink.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = AuroraPink,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "2. ElevenLabs Voice (Mahi)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Default Indian Conversational Voice",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraPink,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Test Voice Button
                        OutlinedButton(
                            onClick = {
                                if (!isTestingVoice) {
                                    isTestingVoice = true
                                    voiceTestResult = null
                                    coroutineScope.launch {
                                        voiceTestResult = elevenLabsClient.testVoiceConnection(
                                            apiKey = voiceKey,
                                            voiceId = mahiVoiceId,
                                            modelId = voiceModel
                                        )
                                        isTestingVoice = false
                                    }
                                }
                            },
                            modifier = Modifier.testTag("test_voice_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AuroraPink),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AuroraPink),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            if (isTestingVoice) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = AuroraPink
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test Mahi", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = voiceKey,
                        onValueChange = { voiceKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_key_input"),
                        label = { Text("ElevenLabs API Key") },
                        placeholder = { Text("Enter your ElevenLabs API Key") },
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

                    Spacer(modifier = Modifier.height(10.dp))

                    // Mahi Voice ID Configuration Field
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

                    Spacer(modifier = Modifier.height(10.dp))

                    // Optional Native TTS Fallback Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Native Android TTS Fallback",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Use local TTS only if ElevenLabs is unconfigured or offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = nativeTtsFallback,
                            onCheckedChange = { nativeTtsFallback = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AuroraPink,
                                checkedTrackColor = AuroraPink.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = DarkSurfaceBorder
                            )
                        )
                    }

                    // Voice Test Result Feedback
                    voiceTestResult?.let { res ->
                        Spacer(modifier = Modifier.height(10.dp))
                        when (res) {
                            is VoiceTestResult.Success -> {
                                Text(
                                    text = "✓ ${res.message}",
                                    color = AuroraEmerald,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            is VoiceTestResult.Failure -> {
                                Text(
                                    text = "✕ ${res.errorMessage}",
                                    color = DangerRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Step 3: Permissions Card (Accessibility + Draw Over Other Apps + Mic)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CardGlowBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "3. Required Device Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Enables full device control and floating aurora overlay",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Accessibility Permission Row
                    PermissionItemRow(
                        title = "Accessibility Service",
                        desc = "Navigate apps, scroll, tap buttons, home & back",
                        icon = Icons.Default.TouchApp,
                        isGranted = isAccessibilityGranted,
                        actionLabel = if (isAccessibilityGranted) "Active" else "Enable",
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Draw Over Other Apps Permission Row
                    PermissionItemRow(
                        title = "Draw Over Other Apps",
                        desc = "Floating Aurora light when app is minimized",
                        icon = Icons.Default.Layers,
                        isGranted = isOverlayPermissionGranted,
                        actionLabel = if (isOverlayPermissionGranted) "Active" else "Enable",
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Microphone Permission Row
                    PermissionItemRow(
                        title = "Microphone Permission",
                        desc = "Continuous voice listening and speech input",
                        icon = Icons.Default.Mic,
                        isGranted = isMicPermissionGranted,
                        actionLabel = if (isMicPermissionGranted) "Active" else "Allow",
                        onClick = {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Complete Setup & Save Button
            Button(
                onClick = {
                    prefs.setGeminiApiKey(geminiKey)
                    prefs.setSelectedGeminiModel(selectedGeminiModel)
                    prefs.setVoiceApiKey(voiceKey)
                    prefs.setSelectedVoiceId(mahiVoiceId)
                    prefs.setSelectedVoiceModel(voiceModel)
                    prefs.setNativeTtsFallbackEnabled(nativeTtsFallback)
                    prefs.setSetupCompleted(true)
                    onSetupCompleted()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("activate_agents_button"),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "SAVE CREDENTIALS & START MYRAA",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF04141E),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PermissionItemRow(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            .padding(12.dp),
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
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
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
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ACTIVE",
                        color = AuroraEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = VioletSecondary),
                shape = RoundedCornerShape(20.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 12.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
