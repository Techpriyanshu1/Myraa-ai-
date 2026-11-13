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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agents.AgentFleetManager
import com.example.agents.AgentHealthReport
import com.example.agents.AgentStatus
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentFleetScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { SecurePreferences(context) }
    val fleetManager = remember { AgentFleetManager.getInstance(context, prefs) }

    val reports by fleetManager.agentReports.collectAsState()
    val overallStatus by fleetManager.fleetOverallStatus.collectAsState()
    val unavailableReasons by fleetManager.unavailableReasons.collectAsState()

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI Agent Fleet (${reports.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("agent_fleet_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = CyanPrimary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { fleetManager.refreshFleetStatus() },
                        modifier = Modifier.testTag("agent_fleet_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Fleet",
                            tint = CyanPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                    border = BorderStroke(1.dp, CardGlowBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(AuroraEmerald)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Fleet Health & Dependency Monitor",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = overallStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraEmerald,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (unavailableReasons.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Unavailable / Optional Details:",
                                color = AuroraPink,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            unavailableReasons.forEach { reason ->
                                Text(
                                    text = "• $reason",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { fleetManager.initializeAllAsync() },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("start_all_agents_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Initialize & Start All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { fleetManager.stopAllAsync() },
                                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stop_all_agents_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop All", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            items(reports, key = { it.agentId }) { report ->
                AgentReportCard(
                    report = report,
                    onStart = { fleetManager.startAgentAsync(report.agentId) },
                    onStop = { fleetManager.stopAgentAsync(report.agentId) }
                )
            }
        }
    }
}

@Composable
private fun AgentReportCard(
    report: AgentHealthReport,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val statusColor = when (report.status) {
        AgentStatus.ACTIVE -> AuroraEmerald
        AgentStatus.READY, AgentStatus.INITIALIZING -> CyanPrimary
        AgentStatus.PERMISSION_REQUIRED, AgentStatus.ERROR -> DangerRed
        AgentStatus.NOT_CONFIGURED -> AuroraPink
        AgentStatus.DISABLED -> TextSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_card_${report.agentId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(
            1.dp,
            when (report.status) {
                AgentStatus.ACTIVE -> AuroraEmerald.copy(alpha = 0.45f)
                AgentStatus.READY -> CardGlowBorder
                AgentStatus.PERMISSION_REQUIRED, AgentStatus.ERROR -> DangerRed.copy(alpha = 0.45f)
                else -> DarkSurfaceBorder
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = report.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        if (report.isOptional) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "OPTIONAL",
                                color = TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = report.role,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanPrimary,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(statusColor.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = report.status.name,
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = report.errorReason ?: report.detailMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (report.errorReason != null) DangerRed.copy(alpha = 0.9f) else TextSecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (report.status == AgentStatus.ACTIVE) {
                    OutlinedButton(
                        onClick = onStop,
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("stop_agent_${report.agentId}")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", color = DangerRed, fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = onStart,
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("start_agent_${report.agentId}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start / Validate", color = CyanPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
