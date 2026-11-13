package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.CardGlowBorder
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

data class AgentInfo(
    val name: String,
    val role: String,
    val icon: ImageVector,
    val isActive: Boolean,
    val detail: String
)

@Composable
fun AgentStatusGrid(
    isAccessibilityGranted: Boolean,
    hasGeminiKey: Boolean,
    isVoiceSessionActive: Boolean,
    modifier: Modifier = Modifier
) {
    val agents = listOf(
        AgentInfo(
            name = "Voice Agent",
            role = "Speech Recognition",
            icon = Icons.Default.Mic,
            isActive = true,
            detail = if (isVoiceSessionActive) "Listening loop live" else "Standby ready"
        ),
        AgentInfo(
            name = "Neural Voice Agent",
            role = "ElevenLabs Sweet TTS",
            icon = Icons.Default.RecordVoiceOver,
            isActive = true,
            detail = "API Key encrypted"
        ),
        AgentInfo(
            name = "Gemini Intelligence",
            role = "Intent Parsing",
            icon = Icons.Default.Psychology,
            isActive = true,
            detail = if (hasGeminiKey) "Gemini 2.5 Active" else "Local Reflex Engine"
        ),
        AgentInfo(
            name = "Automation Agent",
            role = "Accessibility & Gestures",
            icon = Icons.Default.TouchApp,
            isActive = isAccessibilityGranted,
            detail = if (isAccessibilityGranted) "Service Connected" else "Needs Permission"
        ),
        AgentInfo(
            name = "System Manager",
            role = "App Launcher & Hardware",
            icon = Icons.Default.Widgets,
            isActive = true,
            detail = "Camera/Audio/Intents"
        ),
        AgentInfo(
            name = "Audit Agent",
            role = "Local Privacy & Logs",
            icon = Icons.Default.Security,
            isActive = true,
            detail = "Room DB Encryption"
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
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
                        .background(AuroraEmerald)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Active Sub-Agent Fleet",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "All Systems Go",
                style = MaterialTheme.typography.labelSmall,
                color = AuroraEmerald,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Grid of agents (2 columns)
        for (i in agents.indices step 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgentBadge(agent = agents[i], modifier = Modifier.weight(1f))
                if (i + 1 < agents.size) {
                    AgentBadge(agent = agents[i + 1], modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AgentBadge(agent: AgentInfo, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceElevated)
            .border(
                1.dp,
                if (agent.isActive) CardGlowBorder else DarkSurfaceBorder,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = agent.icon,
            contentDescription = agent.name,
            tint = if (agent.isActive) CyanPrimary else TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agent.name,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = agent.detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (agent.isActive) AuroraEmerald else TextSecondary,
                maxLines = 1
            )
        }
    }
}
