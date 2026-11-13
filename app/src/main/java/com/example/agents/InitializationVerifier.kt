package com.example.agents

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

        // 3. Voice API configuration
        val hasVoiceKey = prefs.getVoiceApiKey().isNotBlank()
        val hasFallback = prefs.isNativeTtsFallbackEnabled()
        checks += PreflightCheckItem(
            id = "voice_api_config",
            title = "3. Voice API Configuration",
            severity = when {
                hasVoiceKey -> PreflightSeverity.OK
                hasFallback -> PreflightSeverity.WARNING
                else -> PreflightSeverity.WARNING
            },
            message = when {
                hasVoiceKey -> "ElevenLabs Voice API configured."
                hasFallback -> "ElevenLabs key missing; Android Native TTS fallback active."
                else -> "Voice API not configured and native fallback is disabled."
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

        // 7. Foreground-service eligibility & speech recognition availability
        val speechAvail = SpeechRecognizer.isRecognitionAvailable(context)
        val dialogAvail = try {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
        } catch (_: Exception) {
            false
        }
        val fgEligible = hasMic && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED)
        checks += PreflightCheckItem(
            id = "foreground_service",
            title = "7. Foreground-Service Eligibility",
            severity = when {
                !fgEligible -> PreflightSeverity.BLOCKING_ERROR
                !speechAvail && !dialogAvail -> PreflightSeverity.WARNING
                else -> PreflightSeverity.OK
            },
            message = when {
                !fgEligible -> "Unable to start listening: foreground microphone permission not eligible."
                !speechAvail && !dialogAvail -> "System speech service unavailable; text command mode active."
                !speechAvail -> "Voice dialog fallback & foreground mic ready."
                else -> "Microphone foreground service eligible and ready."
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
