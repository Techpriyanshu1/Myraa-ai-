package com.example.ui.screens

import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agents.AgentFleetManager
import com.example.data.api.ConnectionTestResult
import com.example.data.api.ElevenLabsClient
import com.example.data.api.GeminiClient
import com.example.data.api.VoiceTestResult
import com.example.data.pref.SecurePreferences
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
import com.example.voice.MyraaSessionCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMemory: () -> Unit = {},
    onNavigateToPermissions: () -> Unit,
    onNavigateToAgentFleet: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onRerunSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { SecurePreferences(context) }
    val geminiClient = remember { GeminiClient() }
    val elevenLabsClient = remember { ElevenLabsClient(context) }
    val fleetManager = remember { AgentFleetManager.getInstance(context, prefs) }

    var geminiKey by remember { mutableStateOf(prefs.getGeminiApiKey()) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var selectedGeminiModel by remember { mutableStateOf(prefs.getSelectedGeminiModel()) }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }
    var isTestingGemini by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<ConnectionTestResult?>(null) }

    var voiceKey by remember { mutableStateOf(prefs.getVoiceApiKey()) }
    var showVoiceKey by remember { mutableStateOf(false) }
    var selectedVoiceId by remember { mutableStateOf(prefs.getSelectedVoiceId()) }
    var selectedVoiceProviderMode by remember { mutableStateOf(prefs.getVoiceProviderMode()) }
    var firstVoiceProvider by remember { mutableStateOf(prefs.getFirstConfiguredVoiceProvider()) }
    var isTestingVoice by remember { mutableStateOf(false) }
    var voiceTestResult by remember { mutableStateOf<VoiceTestResult?>(null) }

    var isOverlayEnabled by remember { mutableStateOf(prefs.isOverlayEnabled()) }
    var isContinuousListening by remember { mutableStateOf(prefs.isContinuousListeningEnabled()) }

    var savedMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            elevenLabsClient.shutdown()
        }
    }

    val voices = listOf(
        Pair("Mahi (Default Indian Conversational)", SecurePreferences.VOICE_MAHI)
    )

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Settings & Configuration",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Quick Navigation Hub to Dedicated Screens
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardGlowBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Management & Diagnostic Hubs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )

                    SettingsNavRow(
                        icon = Icons.Default.Psychology,
                        title = "Memory Core & Preferences",
                        subtitle = "Manage custom memories (family name, habits) & view locked core bond",
                        onClick = onNavigateToMemory
                    )

                    SettingsNavRow(
                        icon = Icons.Default.Security,
                        title = "Permission Center",
                        subtitle = "Review and grant microphone, overlay & accessibility access",
                        onClick = onNavigateToPermissions
                    )

                    SettingsNavRow(
                        icon = Icons.Default.Widgets,
                        title = "Sub-Agent Fleet Diagnostics",
                        subtitle = "Inspect health, state & lifecycle of all 11 AI sub-agents",
                        onClick = onNavigateToAgentFleet
                    )

                    SettingsNavRow(
                        icon = Icons.Default.Lock,
                        title = "Security & Keystore Privacy",
                        subtitle = "Hardware-grade AES-256-GCM encryption & local data retention",
                        onClick = onNavigateToSecurity
                    )

                    SettingsNavRow(
                        icon = Icons.Default.Info,
                        title = "About MYRAA AI",
                        subtitle = "Product identity, architecture specs & version details",
                        onClick = onNavigateToAbout
                    )
                }
            }

            // Gemini API Configuration & Live Test Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = VioletSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Gemini API Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
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
                            .testTag("settings_gemini_key"),
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("Paste Gemini Key from Google AI Studio") },
                        visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(
                                onClick = { showGeminiKey = !showGeminiKey },
                                modifier = Modifier.testTag("settings_toggle_gemini_key")
                            ) {
                                Icon(
                                    imageVector = if (showGeminiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showGeminiKey) "Hide API Key" else "Show API Key",
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

                    ExposedDropdownMenuBox(
                        expanded = isModelDropdownExpanded,
                        onExpandedChange = { isModelDropdownExpanded = !isModelDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedGeminiModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Gemini Reasoning Model") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = isModelDropdownExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .testTag("settings_gemini_model"),
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
                                        prefs.setSelectedGeminiModel(model)
                                        isModelDropdownExpanded = false
                                        geminiTestResult = null
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            if (isTestingGemini) return@OutlinedButton
                            isTestingGemini = true
                            geminiTestResult = null
                            val cleanKey = geminiKey.trim()
                            prefs.setGeminiApiKey(cleanKey)
                            prefs.setSelectedGeminiModel(selectedGeminiModel)
                            firstVoiceProvider = prefs.getFirstConfiguredVoiceProvider()
                            MyraaSessionCoordinator.getInstance(context).refreshVoiceProviderState()
                            coroutineScope.launch {
                                val res = geminiClient.testConnection(cleanKey, selectedGeminiModel)
                                geminiTestResult = res
                                if (res is ConnectionTestResult.Success) {
                                    selectedGeminiModel = res.modelUsed
                                    prefs.setSelectedGeminiModel(res.modelUsed)
                                    prefs.setOfflineModeAccepted(false)
                                    fleetManager.clearAgentError("gemini_intelligence")
                                    fleetManager.refreshFleetStatus(clearErrors = true)
                                }
                                isTestingGemini = false
                            }
                        },
                        enabled = !isTestingGemini,
                        border = androidx.compose.foundation.BorderStroke(1.dp, VioletSecondary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_test_gemini_button")
                    ) {
                        if (isTestingGemini) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CyanPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Gemini Connection...", color = TextPrimary, fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = VioletSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save & Test Gemini Connection", color = VioletSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    geminiTestResult?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
                        val isSuccess = result is ConnectionTestResult.Success
                        val statusColor = if (isSuccess) AuroraEmerald else DangerRed
                        val msg = when (result) {
                            is ConnectionTestResult.Success -> result.message
                            is ConnectionTestResult.Failure -> result.errorMessage
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
                            Text(text = msg, color = statusColor, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Voice Configuration
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = AuroraPink,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Neural Voice Persona",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = voiceKey,
                        onValueChange = {
                            voiceKey = it
                            voiceTestResult = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_voice_key"),
                        label = { Text("ElevenLabs API Key (Optional)") },
                        placeholder = { Text("sk_... (leave blank for Gemini Neural AI Voice)") },
                        visualTransformation = if (showVoiceKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showVoiceKey = !showVoiceKey }) {
                                Icon(
                                    imageVector = if (showVoiceKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showVoiceKey) "Hide ElevenLabs Key" else "Show ElevenLabs Key",
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

                    voices.forEach { (name, id) ->
                        val isSelected = selectedVoiceId == id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) CyanPrimary.copy(alpha = 0.15f) else DarkSurfaceElevated)
                                .border(
                                    1.dp,
                                    if (isSelected) CyanPrimary else DarkSurfaceBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedVoiceId = id }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) CyanPrimary else TextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Voice API Engine Priority",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val firstApiText = when (firstVoiceProvider) {
                        SecurePreferences.VOICE_PROVIDER_ELEVENLABS -> "First API given: ElevenLabs"
                        SecurePreferences.VOICE_PROVIDER_GEMINI -> "First API given: Gemini"
                        else -> "Auto uses whichever API key you provide first"
                    }
                    Text(
                        text = firstApiText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Triple(SecurePreferences.VOICE_PROVIDER_AUTO, "Auto (1st)", "settings_voice_provider_auto"),
                            Triple(SecurePreferences.VOICE_PROVIDER_GEMINI, "Gemini", "settings_voice_provider_gemini"),
                            Triple(SecurePreferences.VOICE_PROVIDER_ELEVENLABS, "ElevenLabs", "settings_voice_provider_elevenlabs")
                        ).forEach { (modeKey, label, tag) ->
                            val selected = selectedVoiceProviderMode == modeKey
                            val accent = if (modeKey == SecurePreferences.VOICE_PROVIDER_ELEVENLABS) AuroraPink else CyanPrimary
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) accent.copy(alpha = 0.2f) else DarkSurfaceElevated)
                                    .border(
                                        1.dp,
                                        if (selected) accent else DarkSurfaceBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        selectedVoiceProviderMode = modeKey
                                        prefs.setVoiceProviderMode(modeKey)
                                        MyraaSessionCoordinator.getInstance(context).refreshVoiceProviderState()
                                        voiceTestResult = null
                                    }
                                    .padding(vertical = 10.dp)
                                    .testTag(tag),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) accent else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            if (isTestingVoice) return@OutlinedButton
                            isTestingVoice = true
                            voiceTestResult = null
                            val cleanVoiceKey = voiceKey.trim()
                            prefs.setVoiceApiKey(cleanVoiceKey)
                            prefs.setSelectedVoiceId(selectedVoiceId)
                            prefs.setVoiceProviderMode(selectedVoiceProviderMode)
                            firstVoiceProvider = prefs.getFirstConfiguredVoiceProvider()
                            MyraaSessionCoordinator.getInstance(context).refreshVoiceProviderState()
                            coroutineScope.launch {
                                val res = elevenLabsClient.testVoiceConnection(cleanVoiceKey, selectedVoiceId)
                                voiceTestResult = res
                                if (res is VoiceTestResult.Success) {
                                    fleetManager.clearAgentError("mahi_voice_output")
                                    fleetManager.refreshFleetStatus()
                                }
                                isTestingVoice = false
                            }
                        },
                        enabled = !isTestingVoice,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AuroraPink),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_test_voice_button")
                    ) {
                        if (isTestingVoice) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AuroraPink)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Mahi Voice...", color = TextPrimary, fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AuroraPink, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test AI Mahi Voice", color = AuroraPink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    voiceTestResult?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
                        val isSuccess = result is VoiceTestResult.Success
                        val statusColor = if (isSuccess) AuroraEmerald else DangerRed
                        val msg = when (result) {
                            is VoiceTestResult.Success -> result.message
                            is VoiceTestResult.Failure -> result.errorMessage
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
                            Text(text = msg, color = statusColor, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Behavior & Overlay
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Behavior & Ambient Display",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Floating Aurora Light Overlay",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Display ambient glowing orb when using other apps",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = isOverlayEnabled,
                            onCheckedChange = { isOverlayEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyanPrimary,
                                checkedTrackColor = CyanPrimary.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = DarkSurfaceBorder
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Continuous Listening Mode",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Keep listening continuously until START button is toggled off",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = isContinuousListening,
                            onCheckedChange = { isContinuousListening = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyanPrimary,
                                checkedTrackColor = CyanPrimary.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = DarkSurfaceBorder
                            )
                        )
                    }
                }
            }

            // Save Settings Button
            Button(
                onClick = {
                    val cleanGemini = geminiKey.trim()
                    prefs.setGeminiApiKey(cleanGemini)
                    prefs.setSelectedGeminiModel(selectedGeminiModel)
                    if (cleanGemini.isNotBlank()) {
                        prefs.setOfflineModeAccepted(false)
                    }
                    prefs.setVoiceApiKey(voiceKey.trim())
                    prefs.setSelectedVoiceId(selectedVoiceId)
                    prefs.setVoiceProviderMode(selectedVoiceProviderMode)
                    firstVoiceProvider = prefs.getFirstConfiguredVoiceProvider()
                    MyraaSessionCoordinator.getInstance(context).refreshVoiceProviderState()
                    prefs.setOverlayEnabled(isOverlayEnabled)
                    prefs.setContinuousListeningEnabled(isContinuousListening)
                    fleetManager.refreshFleetStatus(clearErrors = true)
                    savedMessage = "Preferences saved securely in Keystore!"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_settings_button"),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "Save Preferences Securely",
                    color = Color(0xFF04141E),
                    fontWeight = FontWeight.Bold
                )
            }

            if (savedMessage != null) {
                Text(
                    text = savedMessage!!,
                    color = AuroraEmerald,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            // Rerun Setup button
            OutlinedButton(
                onClick = onRerunSetup,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rerun_setup_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Re-open Setup Wizard", fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(CyanPrimary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CyanPrimary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}
