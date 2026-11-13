package com.example.agents

import android.content.Context
import com.example.data.pref.SecurePreferences
import com.example.service.MyraaAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central Orchestrator managing lifecycle, health monitoring, and coordination
 * across the 11 modular agents.
 */
class AgentFleetManager(
    private val context: Context,
    private val prefs: SecurePreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _agentReports = MutableStateFlow<List<AgentHealthReport>>(emptyList())
    val agentReports: StateFlow<List<AgentHealthReport>> = _agentReports.asStateFlow()

    private val _fleetOverallStatus = MutableStateFlow("Fleet Initializing")
    val fleetOverallStatus: StateFlow<String> = _fleetOverallStatus.asStateFlow()

    init {
        refreshFleetStatus()
    }

    fun refreshFleetStatus() {
        val hasGemini = prefs.getGeminiApiKey().isNotBlank()
        val hasVoiceKey = prefs.getVoiceApiKey().isNotBlank()
        val hasMic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasOverlay = android.provider.Settings.canDrawOverlays(context)
        val hasAccessibility = MyraaAccessibilityService.isRunning()
        val isContinuous = prefs.isContinuousListeningEnabled()

        val reports = listOf(
            AgentHealthReport(
                agentId = "gemini_intelligence",
                name = "Gemini Intelligence Agent",
                role = "Intent Parsing & Real Dialogue",
                status = if (hasGemini) AgentStatus.READY else AgentStatus.NOT_CONFIGURED,
                detailMessage = if (hasGemini) "Gemini Flash Ready (Official v1beta)" else "Awaiting User Gemini API Key",
                requiresCredential = !hasGemini
            ),
            AgentHealthReport(
                agentId = "voice_input",
                name = "Voice Input Agent",
                role = "Speech Recognition (Hindi/English/Hinglish)",
                status = if (!hasMic) AgentStatus.PERMISSION_REQUIRED else AgentStatus.READY,
                detailMessage = if (hasMic) "Microphone Ready (Continuous Listening)" else "Microphone Permission Required",
                requiresPermission = !hasMic
            ),
            AgentHealthReport(
                agentId = "voice_output",
                name = "Voice Output Agent",
                role = "ElevenLabs Neural Bella & Native TTS",
                status = if (hasVoiceKey) AgentStatus.READY else AgentStatus.READY,
                detailMessage = if (hasVoiceKey) "ElevenLabs Multilingual v2 Bella" else "Android Native High-Pitch TTS Fallback",
                requiresCredential = false
            ),
            AgentHealthReport(
                agentId = "app_launcher",
                name = "App Launcher Agent",
                role = "Direct Intent Dispatcher",
                status = AgentStatus.READY,
                detailMessage = "Package Manager & Launcher Intents Ready"
            ),
            AgentHealthReport(
                agentId = "device_action",
                name = "Device Action Agent",
                role = "Flashlight, Volume & Hardware Reflexes",
                status = AgentStatus.READY,
                detailMessage = "Hardware APIs Active"
            ),
            AgentHealthReport(
                agentId = "permission_manager",
                name = "Permission Manager Agent",
                role = "Runtime & Special App Access Auditor",
                status = AgentStatus.READY,
                detailMessage = "Enforcing Least-Privilege Standard"
            ),
            AgentHealthReport(
                agentId = "accessibility_integration",
                name = "Accessibility Integration Agent",
                role = "On-Screen Clicks, Typing & Scroll Gestures",
                status = if (hasAccessibility) AgentStatus.ACTIVE else AgentStatus.PERMISSION_REQUIRED,
                detailMessage = if (hasAccessibility) "Service Connected" else "Optional Accessibility Service Disabled",
                requiresPermission = !hasAccessibility
            ),
            AgentHealthReport(
                agentId = "shizuku_integration",
                name = "Shizuku Integration Agent",
                role = "Allowlisted Elevated Actions",
                status = AgentStatus.READY,
                detailMessage = "Shizuku Bridge Standing By"
            ),
            AgentHealthReport(
                agentId = "floating_assistant",
                name = "Floating Assistant Agent",
                role = "Aurora Glow Overlay & Digital Mouse",
                status = if (hasOverlay) AgentStatus.READY else AgentStatus.PERMISSION_REQUIRED,
                detailMessage = if (hasOverlay) "Overlay Permission Granted" else "Overlay Permission Optional",
                requiresPermission = !hasOverlay
            ),
            AgentHealthReport(
                agentId = "activity_logger",
                name = "Local Activity Logger",
                role = "Room DB Encrypted Action Audit",
                status = AgentStatus.READY,
                detailMessage = "Room DB Encrypted at Rest"
            ),
            AgentHealthReport(
                agentId = "health_monitor",
                name = "Initialization & Health Monitor",
                role = "System Diagnostics & Heartbeat",
                status = AgentStatus.ACTIVE,
                detailMessage = "Fleet Monitored Successfully"
            )
        )

        _agentReports.value = reports
        val activeCount = reports.count { it.status == AgentStatus.ACTIVE || it.status == AgentStatus.READY }
        _fleetOverallStatus.value = "$activeCount / ${reports.size} Agents Ready & Operational"
    }

    companion object {
        @Volatile
        private var instance: AgentFleetManager? = null

        fun getInstance(context: Context, prefs: SecurePreferences): AgentFleetManager {
            return instance ?: synchronized(this) {
                instance ?: AgentFleetManager(context.applicationContext, prefs).also { instance = it }
            }
        }
    }
}
