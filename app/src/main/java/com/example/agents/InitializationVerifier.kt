package com.example.agents

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.provider.Settings
import com.example.data.api.ConnectionTestResult
import com.example.data.api.GeminiClient
import com.example.data.pref.SecurePreferences
import com.example.permissions.PermissionManager
import com.example.service.MyraaAccessibilityService
import com.example.shizuku.ShizukuBridge

enum class PreflightSeverity {
    OK,
    WARNING,
    BLOCKING_ERROR
}

data class PreflightCheckItem(
    val id: String,
    val title: String,
    val severity: PreflightSeverity,
    val message: String
)

data class InitializationPreflightReport(
    val checks: List<PreflightCheckItem>,
    val canStartVoiceSession: Boolean,
    val blockingMessages: List<String>,
    val warningMessages: List<String>
) {
    fun buildSummaryMessage(): String {
        return when {
            blockingMessages.isNotEmpty() -> blockingMessages.joinToString("\n")
            warningMessages.isNotEmpty() -> warningMessages.take(3).joinToString(" • ")
            else -> "All 11 initialization checks passed."
        }
    }
}

/**
 * Verifies all 11 initialization prerequisites before activating MYRAA AI:
 * 1. Gemini configuration
 * 2. Gemini connectivity
 * 3. Voice API configuration
 * 4. Mahi Voice ID
 * 5. Microphone permission
 * 6. Notification permission
 * 7. Foreground-service eligibility
 * 8. Accessibility status
 * 9. Overlay permission
 * 10. Shizuku availability
 * 11. Agent health
 */
class InitializationVerifier(
    private val context: Context,
    private val prefs: SecurePreferences,
    private val geminiClient: GeminiClient,
    private val fleetManager: AgentFleetManager
) {
    private val permissionManager = PermissionManager(context)

    suspend fun verifyAll(checkLiveConnectivity: Boolean = true): InitializationPreflightReport {
        val checks = mutableListOf<PreflightCheckItem>()

        // 1. Gemini configuration
        val geminiKey = prefs.getGeminiApiKey()
        val hasGeminiKey = geminiKey.isNotBlank()
        checks += PreflightCheckItem(
            id = "gemini_config",
            title = "1. Gemini Configuration",
            severity = if (hasGeminiKey) PreflightSeverity.OK else PreflightSeverity.WARNING,
            message = if (hasGeminiKey) "Gemini API key configured (${prefs.getSelectedGeminiModel()})."
            else "Gemini API key is missing."
        )

        // 2. Gemini connectivity
        if (!hasGeminiKey) {
            checks += PreflightCheckItem(
                id = "gemini_connectivity",
                title = "2. Gemini Connectivity",
                severity = PreflightSeverity.WARNING,
                message = "Gemini connection failed: API key not set (local commands still work)."
            )
        } else if (checkLiveConnectivity) {
            val conn = try {
                geminiClient.testConnection(geminiKey, prefs.getSelectedGeminiModel())
            } catch (e: Exception) {
                ConnectionTestResult.Failure("Gemini connection failed: ${e.localizedMessage ?: "Network error"}")
            }
            checks += when (conn) {
                is ConnectionTestResult.Success -> {
                    if (conn.modelUsed != prefs.getSelectedGeminiModel()) {
                        prefs.setSelectedGeminiModel(conn.modelUsed)
                    }
                    fleetManager.clearAgentError("gemini_intelligence")
                    PreflightCheckItem(
                        id = "gemini_connectivity",
                        title = "2. Gemini Connectivity",
                        severity = PreflightSeverity.OK,
                        message = "Gemini connected (${conn.modelUsed})."
                    )
                }
                is ConnectionTestResult.Failure -> {
                    fleetManager.recordAgentError("gemini_intelligence", conn.errorMessage)
                    PreflightCheckItem(
                        id = "gemini_connectivity",
                        title = "2. Gemini Connectivity",
                        severity = PreflightSeverity.WARNING,
                        message = "Gemini connection failed. ${conn.errorMessage}"
                    )
                }
            }
        } else {
            checks += PreflightCheckItem(
                id = "gemini_connectivity",
                title = "2. Gemini Connectivity",
                severity = PreflightSeverity.OK,
                message = "Gemini key stored; ready for request."
            )
        }

        // 3. Pure AI Voice API configuration (ElevenLabs or Gemini Neural TTS — no Android native TTS)
        val hasVoiceKey = prefs.getVoiceApiKey().isNotBlank()
        checks += PreflightCheckItem(
            id = "voice_api_config",
            title = "3. AI Voice Configuration",
            severity = when {
                hasVoiceKey || hasGeminiKey -> PreflightSeverity.OK
                else -> PreflightSeverity.WARNING
            },
            message = when {
                hasVoiceKey -> "ElevenLabs Neural AI Voice configured."
                hasGeminiKey -> "Gemini Neural AI Voice (Mahi) active with Gemini key."
                else -> "AI Voice requires a Gemini or ElevenLabs API key."
            }
        )

        // 4. Mahi Voice ID
        val mahiVoiceId = prefs.getSelectedVoiceId()
        checks += PreflightCheckItem(
            id = "mahi_voice_id",
            title = "4. Mahi Voice ID",
            severity = if (mahiVoiceId.isNotBlank()) PreflightSeverity.OK else PreflightSeverity.WARNING,
            message = if (mahiVoiceId.isNotBlank()) {
                "Mahi Voice ID configured (${mahiVoiceId.take(8)}…)."
            } else {
                "Mahi voice is not configured."
            }
        )

        // 5. Microphone permission
        val hasMic = permissionManager.checkMicrophonePermission()
        checks += PreflightCheckItem(
            id = "mic_permission",
            title = "5. Microphone Permission",
            severity = if (hasMic) PreflightSeverity.OK else PreflightSeverity.BLOCKING_ERROR,
            message = if (hasMic) "Microphone permission granted." else "Microphone permission required."
        )

        // 6. Notification permission
        val hasNotifications = permissionManager.checkNotificationPermission()
        checks += PreflightCheckItem(
            id = "notification_permission",
            title = "6. Notification Permission",
            severity = if (hasNotifications) PreflightSeverity.OK else PreflightSeverity.WARNING,
            message = if (hasNotifications) "Notification permission granted."
            else "Notification permission disabled; foreground controls may not display."
        )

        // 7. Foreground-service eligibility & Direct AudioRecord capture availability
        val audioReady = try {
            AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } catch (_: Exception) {
            false
        }
        val fgEligible = hasMic && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED)
        checks += PreflightCheckItem(
            id = "foreground_service",
            title = "7. Direct Audio & Foreground Service",
            severity = when {
                !fgEligible -> PreflightSeverity.BLOCKING_ERROR
                !audioReady -> PreflightSeverity.WARNING
                else -> PreflightSeverity.OK
            },
            message = when {
                !fgEligible -> "Unable to start listening: foreground microphone permission not eligible."
                !audioReady -> "Direct microphone audio stream unavailable; text mode active."
                else -> "Direct AudioRecord stream & foreground service ready."
            }
        )

        // 8. Accessibility status (optional)
        val hasAccessibility = MyraaAccessibilityService.isRunning()
        checks += PreflightCheckItem(
            id = "accessibility_status",
            title = "8. Accessibility Service",
            severity = if (hasAccessibility) PreflightSeverity.OK else PreflightSeverity.WARNING,
            message = if (hasAccessibility) "Accessibility Service connected."
            else "Accessibility Service disabled (optional screen gestures unavailable)."
        )

        // 9. Overlay permission (optional)
        val hasOverlay = Settings.canDrawOverlays(context)
        checks += PreflightCheckItem(
            id = "overlay_permission",
            title = "9. Overlay Permission",
            severity = if (hasOverlay) PreflightSeverity.OK else PreflightSeverity.WARNING,
            message = if (hasOverlay) "Draw Over Other Apps granted."
            else "Overlay permission not granted (floating orb disabled)."
        )

        // 10. Shizuku availability (optional)
        val shizuku = ShizukuBridge.checkStatus(context)
        checks += PreflightCheckItem(
            id = "shizuku_status",
            title = "10. Shizuku Availability",
            severity = if (shizuku.isInstalled && shizuku.permissionGranted) PreflightSeverity.OK else PreflightSeverity.WARNING,
            message = when {
                !shizuku.isInstalled || !shizuku.isRunning -> "Shizuku service is unavailable."
                !shizuku.permissionGranted -> "Shizuku installed; authorization needed inside Shizuku."
                else -> "Shizuku service connected and authorized."
            }
        )

        // 11. Agent health
        fleetManager.refreshFleetStatus()
        val reports = fleetManager.agentReports.value
        val erroredAgents = reports.filter { it.status == AgentStatus.ERROR }
        checks += PreflightCheckItem(
            id = "agent_health",
            title = "11. Agent Health",
            severity = if (erroredAgents.isEmpty()) PreflightSeverity.OK else PreflightSeverity.WARNING,
            message = if (erroredAgents.isEmpty()) {
                "${reports.size} modular agents verified healthy."
            } else {
                "Agent error: ${erroredAgents.joinToString { it.name }}"
            }
        )

        val blocking = checks.filter { it.severity == PreflightSeverity.BLOCKING_ERROR }.map { it.message }
        val warnings = checks.filter { it.severity == PreflightSeverity.WARNING }.map { it.message }

        return InitializationPreflightReport(
            checks = checks,
            canStartVoiceSession = blocking.isEmpty(),
            blockingMessages = blocking,
            warningMessages = warnings
        )
    }
}
