package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agents.AgentHealthReport
import com.example.agents.AgentStatus
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.AuroraPink
import com.example.ui.theme.CardGlowBorder
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AgentStatusGrid(
    agentReports: List<AgentHealthReport>,
    overallStatus: String,
    onOpenFleet: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val activeCount = agentReports.count { it.status == AgentStatus.ACTIVE }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
            .then(if (onOpenFleet != null) Modifier.clickable { onOpenFleet() } else Modifier)
            .padding(14.dp)
            .testTag("agent_status_grid")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (activeCount > 0) AuroraEmerald else CyanPrimary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Modular Agent Fleet (${agentReports.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = overallStatus,
                style = MaterialTheme.typography.labelSmall,
                color = if (activeCount > 0) AuroraEmerald else CyanPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Grid of live agents (2 columns)
        for (i in agentReports.indices step 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiveAgentBadge(report = agentReports[i], modifier = Modifier.weight(1f))
                if (i + 1 < agentReports.size) {
                    LiveAgentBadge(report = agentReports[i + 1], modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LiveAgentBadge(report: AgentHealthReport, modifier: Modifier = Modifier) {
    val statusColor = when (report.status) {
        AgentStatus.ACTIVE -> AuroraEmerald
        AgentStatus.READY -> CyanPrimary
        AgentStatus.INITIALIZING -> CyanPrimary
        AgentStatus.PERMISSION_REQUIRED, AgentStatus.ERROR -> DangerRed
        AgentStatus.NOT_CONFIGURED -> AuroraPink
        AgentStatus.DISABLED -> TextSecondary
    }

    val icon: ImageVector = when (report.agentId) {
        "gemini_intelligence" -> Icons.Default.Psychology
        "voice_recognition" -> Icons.Default.Mic
        "mahi_voice_output" -> Icons.Default.RecordVoiceOver
        "app_launcher" -> Icons.Default.Apps
        "device_action" -> Icons.Default.Build
        "permission_manager" -> Icons.Default.Security
        "accessibility_agent" -> Icons.Default.AccessibilityNew
        "shizuku_integration" -> Icons.Default.Terminal
        "floating_assistant" -> Icons.Default.Layers
        "activity_logger" -> Icons.Default.History
        else -> Icons.Default.HealthAndSafety
    }

    val isOperational = report.status == AgentStatus.ACTIVE || report.status == AgentStatus.READY

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceElevated)
            .border(
                1.dp,
                if (isOperational) CardGlowBorder else DarkSurfaceBorder,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = report.name,
            tint = statusColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = report.name.removeSuffix(" Agent"),
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = report.status.name,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = statusColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
