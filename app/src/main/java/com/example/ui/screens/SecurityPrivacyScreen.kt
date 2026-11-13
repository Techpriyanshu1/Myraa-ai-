package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agents.AgentFleetManager
import com.example.data.db.AppDatabase
import com.example.data.pref.SecurePreferences
import com.example.permissions.PermissionManager
import com.example.permissions.PermissionState
import com.example.service.ContinuousVoiceService
import com.example.service.DigitalMouseService
import com.example.service.MyraaOverlayService
import com.example.service.RealScreenCaptureService
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityPrivacyScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { SecurePreferences(context) }
    val db = remember { AppDatabase.getInstance(context) }
    val permissionManager = remember { PermissionManager(context) }
    val fleetManager = remember { AgentFleetManager.getInstance(context, prefs) }

    val logCount by db.automationLogDao().getLogCount().collectAsState(initial = 0)
    val isVoiceListeningLive by ContinuousVoiceService.isListeningLive.collectAsState()
    val isVoicePaused by ContinuousVoiceService.isPaused.collectAsState()
    val isScreenSharing by RealScreenCaptureService.isSharingActive.collectAsState()

    var refreshTick by remember { mutableIntStateOf(0) }
    var logCommandTextEnabled by remember { mutableStateOf(prefs.isCommandTextLoggingEnabled()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteCredsConfirm by remember { mutableStateOf(false) }

    val hasGeminiKey = remember(refreshTick) { prefs.hasStoredGeminiKey() }
    val hasVoiceKey = remember(refreshTick) { prefs.hasStoredVoiceKey() }
    val mahiVoiceId = remember(refreshTick) { prefs.getSelectedVoiceId() }
    val permissions = remember(refreshTick) { permissionManager.getAllPermissionItems() }
    val micPermissionGranted = remember(refreshTick) { permissionManager.checkMicrophonePermission() }

    if (showDeleteCredsConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteCredsConfirm = false },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Delete All Stored Credentials?") },
            text = {
                Text("This permanently removes both your Gemini API key and ElevenLabs Voice API key from Keystore-backed storage and resets Voice ID to Mahi default.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.deleteAllCredentials()
                        refreshTick++
                        fleetManager.refreshFleetStatus()
                        showDeleteCredsConfirm = false
                        statusMessage = "All stored API credentials deleted."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    modifier = Modifier.testTag("confirm_delete_all_credentials")
                ) {
                    Text("Delete Credentials")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCredsConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Privacy & Security",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("security_privacy_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = CyanPrimary
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
            statusMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AuroraEmerald.copy(alpha = 0.16f))
                        .padding(12.dp)
                ) {
                    Text(text = msg, color = AuroraEmerald, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Emergency Stop All Background Activity Button
            Button(
                onClick = {
                    ContinuousVoiceService.stop(context)
                    MyraaOverlayService.stop(context)
                    DigitalMouseService.stop(context)
                    RealScreenCaptureService.stop(context)
                    fleetManager.stopAllAsync()
                    refreshTick++
                    statusMessage = "All background services and active agents stopped."
                },
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stop_all_background_activity_button")
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop All Background Activity", fontWeight = FontWeight.Bold)
            }

            // 1. Active Microphone Status
            PrivacyStatusCard(
                icon = Icons.Default.Mic,
                title = "Active Microphone Status",
                badgeText = when {
                    !micPermissionGranted -> "NO PERMISSION"
                    isVoiceListeningLive && !isVoicePaused -> "MIC ACTIVE"
                    isVoiceListeningLive && isVoicePaused -> "PAUSED"
                    else -> "IDLE"
                },
                badgeColor = when {
                    isVoiceListeningLive && !isVoicePaused -> AuroraEmerald
                    isVoiceListeningLive && isVoicePaused -> AuroraPink
                    else -> CyanPrimary
                },
                description = "Microphone access is active only while you explicitly run a voice session. Raw microphone audio is NEVER saved to local storage."
            )

            // 2. Active Foreground Service Status
            PrivacyStatusCard(
                icon = Icons.Default.NotificationsActive,
                title = "Active Foreground Services",
                badgeText = if (isVoiceListeningLive || isScreenSharing) "RUNNING" else "NONE",
                badgeColor = if (isVoiceListeningLive || isScreenSharing) AuroraEmerald else TextSecondary,
                description = "Voice Service: ${if (isVoiceListeningLive) "Running" else "Stopped"} • Screen Share Service: ${if (isScreenSharing) "Running" else "Stopped"} • Overlay Service: ${if (MyraaOverlayService.isRunning) "Running" else "Stopped"}"
            )

            // 3. Granted Permissions Summary
            val grantedCount = permissions.count { it.state == PermissionState.GRANTED }
            PrivacyStatusCard(
                icon = Icons.Default.Shield,
                title = "Granted Permissions ($grantedCount/${permissions.size})",
                badgeText = if (micPermissionGranted) "CORE OK" else "MIC NEEDED",
                badgeColor = if (micPermissionGranted) AuroraEmerald else DangerRed,
                description = permissions.joinToString("\n") { item ->
                    val stateLabel = if (item.state == PermissionState.GRANTED) "Granted" else "Not Granted"
                    "• ${item.title}: $stateLabel"
                }
            )

            // 4. Stored Credentials Status + Delete Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CardGlowBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Stored Credentials Status", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Android Keystore AES-256-GCM Encrypted", color = AuroraEmerald, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "• Gemini API Key: ${if (hasGeminiKey) "Stored Encrypted" else "Not Configured"}\n" +
                            "• ElevenLabs Voice API Key: ${if (hasVoiceKey) "Stored Encrypted" else "Not Configured"}\n" +
                            "• Mahi Voice ID: ${mahiVoiceId.ifBlank { "Not Configured" }}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                prefs.deleteGeminiCredentials()
                                refreshTick++
                                fleetManager.refreshFleetStatus()
                                statusMessage = "Gemini credentials deleted."
                            },
                            enabled = hasGeminiKey,
                            border = BorderStroke(1.dp, DarkSurfaceBorder),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("delete_gemini_credentials_button")
                        ) {
                            Text("Delete Gemini", fontSize = 11.sp, color = TextPrimary)
                        }
                        OutlinedButton(
                            onClick = {
                                prefs.deleteVoiceCredentials()
                                refreshTick++
                                fleetManager.refreshFleetStatus()
                                statusMessage = "Voice credentials deleted."
                            },
                            enabled = hasVoiceKey,
                            border = BorderStroke(1.dp, DarkSurfaceBorder),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("delete_voice_credentials_button")
                        ) {
                            Text("Delete Voice", fontSize = 11.sp, color = TextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showDeleteCredsConfirm = true },
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_all_credentials_button")
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete All Credentials", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // 5. Local Log Storage Status & Data Deletion Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local Log Storage", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("$logCount records stored locally in Room DB", color = CyanPrimary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Store Command Text in History", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Disable to redact spoken/typed prompts in Room DB.", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = logCommandTextEnabled,
                            onCheckedChange = { enabled ->
                                logCommandTextEnabled = enabled
                                prefs.setCommandTextLoggingEnabled(enabled)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary),
                            modifier = Modifier.testTag("toggle_command_text_logging")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                db.automationLogDao().clearAllLogs()
                                statusMessage = "All local activity history cleared."
                            }
                        },
                        enabled = logCount > 0,
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("clear_all_logs_privacy_button")
                    ) {
                        Text("Clear All Local Activity Logs", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // 6. Honest Security Scope Disclosure
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                border = BorderStroke(1.dp, AuroraPink.copy(alpha = 0.35f))
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AuroraPink, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Security Boundary Disclosure", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "MYRAA AI uses Android Keystore AES-256-GCM to protect API keys at rest and keeps keys out of logs and AI prompts. However, local encryption cannot prevent data extraction on a rooted or fully compromised device.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyStatusCard(
    icon: ImageVector,
    title: String,
    badgeText: String,
    badgeColor: Color,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(badgeColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(text = badgeText, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = description, color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}
