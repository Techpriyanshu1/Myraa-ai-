package com.example.agents

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import com.example.data.db.AppDatabase
import com.example.data.pref.SecurePreferences
import com.example.permissions.PermissionManager
import com.example.service.MyraaAccessibilityService
import com.example.shizuku.ShizukuBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared base lifecycle implementation enforcing strict status transitions,
 * configuration/permission validation, and error safety (failed agents NEVER become ACTIVE).
 */
abstract class BaseMyraaAgent(
    override val agentId: String,
    override val name: String,
    override val role: String,
    override val isOptional: Boolean = false,
    override val dependencies: List<String> = emptyList()
) : MyraaSubAgent {

    protected val _statusFlow = MutableStateFlow(AgentStatus.INITIALIZING)
    override val statusFlow: StateFlow<AgentStatus> = _statusFlow.asStateFlow()

    @Volatile
    protected var lastErrorReason: String? = null

    @Volatile
    protected var lastDetailMessage: String = "Standing by"

    @Volatile
    protected var lastActiveAt: Long = System.currentTimeMillis()

    protected open val requiresCredentialFlag: Boolean = false
    protected open val requiresPermissionFlag: Boolean = false

    override suspend fun initialize(): Boolean {
        _statusFlow.value = AgentStatus.INITIALIZING
        lastErrorReason = null

        val configValidation = validateConfiguration()
        if (!configValidation.isValid) {
            _statusFlow.value = configValidation.statusOnFailure
            lastDetailMessage = configValidation.reason
            lastErrorReason = configValidation.reason
            return false
        }

        val permValidation = validatePermissions()
        if (!permValidation.isValid) {
            _statusFlow.value = permValidation.statusOnFailure
            lastDetailMessage = permValidation.reason
            lastErrorReason = permValidation.reason
            return false
        }

        _statusFlow.value = AgentStatus.READY
        lastDetailMessage = onReadyDetail()
        return true
    }

    override suspend fun start(): Boolean {
        val isReady = initialize()
        if (!isReady) {
            // Strictly enforce: never mark failed/unconfigured/unpermitted agent as ACTIVE
            return false
        }
        return try {
            val started = onStartInternal()
            if (started) {
                _statusFlow.value = AgentStatus.ACTIVE
                lastActiveAt = System.currentTimeMillis()
                lastErrorReason = null
                lastDetailMessage = onActiveDetail()
                true
            } else {
                _statusFlow.value = if (isOptional) AgentStatus.DISABLED else AgentStatus.ERROR
                false
            }
        } catch (e: Exception) {
            recordError(e.localizedMessage ?: "Unexpected startup failure")
            false
        }
    }

    override suspend fun stop(): Boolean {
        onStopInternal()
        val configOk = validateConfiguration()
        val permOk = validatePermissions()
        _statusFlow.value = when {
            !configOk.isValid -> configOk.statusOnFailure
            !permOk.isValid -> permOk.statusOnFailure
            isOptional && !isOptionalFeatureEnabled() -> AgentStatus.DISABLED
            else -> AgentStatus.READY
        }
        lastDetailMessage = when (_statusFlow.value) {
            AgentStatus.READY -> onReadyDetail()
            AgentStatus.DISABLED -> lastErrorReason ?: "Optional agent stopped"
            else -> lastErrorReason ?: "Stopped"
        }
        return true
    }

    override fun recordError(reason: String) {
        lastErrorReason = reason
        lastDetailMessage = reason
        _statusFlow.value = AgentStatus.ERROR
    }

    override fun clearError() {
        lastErrorReason = null
    }

    override fun checkHealth(): AgentHealthReport {
        // Re-evaluate live permissions/config if currently idle/ready/disabled/permission_required
        val current = _statusFlow.value
        if (current != AgentStatus.INITIALIZING && current != AgentStatus.ERROR) {
            val configOk = validateConfiguration()
            val permOk = validatePermissions()
            when {
                !configOk.isValid -> {
                    _statusFlow.value = configOk.statusOnFailure
                    lastDetailMessage = configOk.reason
                }
                !permOk.isValid -> {
                    _statusFlow.value = permOk.statusOnFailure
                    lastDetailMessage = permOk.reason
                }
                current != AgentStatus.ACTIVE -> {
                    _statusFlow.value = AgentStatus.READY
                    lastDetailMessage = onReadyDetail()
                }
            }
        }

        val currentStatus = _statusFlow.value
        return AgentHealthReport(
            agentId = agentId,
            name = name,
            role = role,
            status = currentStatus,
            detailMessage = lastDetailMessage,
            isOptional = isOptional,
            requiresPermission = currentStatus == AgentStatus.PERMISSION_REQUIRED || requiresPermissionFlag,
            requiresCredential = currentStatus == AgentStatus.NOT_CONFIGURED || requiresCredentialFlag,
            errorReason = if (currentStatus == AgentStatus.ERROR || currentStatus == AgentStatus.DISABLED ||
                currentStatus == AgentStatus.PERMISSION_REQUIRED || currentStatus == AgentStatus.NOT_CONFIGURED
            ) lastErrorReason ?: lastDetailMessage else null,
            dependencies = dependencies,
            lastActiveTimestamp = lastActiveAt
        )
    }

    protected open fun isOptionalFeatureEnabled(): Boolean = true
    protected open fun onStartInternal(): Boolean = true
    protected open fun onStopInternal() {}
    protected abstract fun onReadyDetail(): String
    protected abstract fun onActiveDetail(): String
}

// 1. Gemini Intelligence Agent
class GeminiIntelligenceAgent(
    private val prefs: SecurePreferences
) : BaseMyraaAgent(
    agentId = "gemini_intelligence",
    name = "Gemini Intelligence Agent",
    role = "Structured JSON Intent Parsing & Multilingual AI",
    isOptional = false,
    dependencies = listOf("permission_manager")
) {
    override fun validateConfiguration(): AgentValidationResult {
        val key = prefs.getGeminiApiKey()
        return if (key.isBlank()) {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.NOT_CONFIGURED,
                reason = "Gemini API key not configured. Add key in API Settings."
            )
        } else {
            AgentValidationResult(true)
        }
    }

    override fun validatePermissions(): AgentValidationResult = AgentValidationResult(true)
    override fun onReadyDetail(): String = "Configured (${prefs.getSelectedGeminiModel()})"
    override fun onActiveDetail(): String = "Active with ${prefs.getSelectedGeminiModel()} (JSON Pipeline)"
}

// 2. Voice Recognition Agent
class VoiceRecognitionAgent(
    private val context: Context
) : BaseMyraaAgent(
    agentId = "voice_recognition",
    name = "Voice Recognition Agent",
    role = "Hindi, Hinglish & English Speech-to-Text",
    isOptional = false,
    dependencies = listOf("permission_manager")
) {
    override fun validateConfiguration(): AgentValidationResult {
        return if (SpeechRecognizer.isRecognitionAvailable(context)) {
            AgentValidationResult(true)
        } else {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.DISABLED,
                reason = "On-device SpeechRecognizer unavailable; system dialog fallback only."
            )
        }
    }

    override fun validatePermissions(): AgentValidationResult {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            AgentValidationResult(true)
        } else {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.PERMISSION_REQUIRED,
                reason = "Microphone (RECORD_AUDIO) permission required for voice listening."
            )
        }
    }

    override fun onReadyDetail(): String = "Microphone & SpeechRecognizer ready"
    override fun onActiveDetail(): String = "Listening continuously for Hindi/Hinglish/English"
}

// 3. Mahi Voice Output Agent
class MahiVoiceOutputAgent(
    private val prefs: SecurePreferences
) : BaseMyraaAgent(
    agentId = "mahi_voice_output",
    name = "Mahi Voice Output Agent",
    role = "ElevenLabs Mahi Neural TTS & Native Speech",
    isOptional = false
) {
    override fun validateConfiguration(): AgentValidationResult {
        val hasElevenKey = prefs.getVoiceApiKey().isNotBlank()
        val hasFallback = prefs.isNativeTtsFallbackEnabled()
        val voiceId = prefs.getSelectedVoiceId()
        return when {
            voiceId.isBlank() -> AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.NOT_CONFIGURED,
                reason = "Mahi Voice ID is empty in Voice Settings."
            )
            !hasElevenKey && !hasFallback -> AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.NOT_CONFIGURED,
                reason = "ElevenLabs API key missing and native TTS fallback is disabled."
            )
            else -> AgentValidationResult(true)
        }
    }

    override fun validatePermissions(): AgentValidationResult = AgentValidationResult(true)

    override fun onReadyDetail(): String {
        return if (prefs.getVoiceApiKey().isNotBlank()) {
            "Mahi Voice ID (${prefs.getSelectedVoiceId().take(8)}…) configured"
        } else {
            "Mahi Native TTS Fallback Ready (Configure ElevenLabs key for neural voice)"
        }
    }

    override fun onActiveDetail(): String {
        return if (prefs.getVoiceApiKey().isNotBlank()) {
            "Speaking via ElevenLabs Mahi (${prefs.getSelectedVoiceModel()})"
        } else {
            "Speaking via Android Native TTS fallback"
        }
    }
}

// 4. App Launcher Agent
class AppLauncherAgent(
    private val context: Context
) : BaseMyraaAgent(
    agentId = "app_launcher",
    name = "App Launcher Agent",
    role = "Launchable App Discovery & Safe Intent Dispatch",
    isOptional = false
) {
    private var cachedLaunchableCount = 0

    override fun validateConfiguration(): AgentValidationResult {
        cachedLaunchableCount = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(intent, 0).size
        } catch (_: Exception) {
            0
        }
        return AgentValidationResult(true)
    }

    override fun validatePermissions(): AgentValidationResult = AgentValidationResult(true)
    override fun onReadyDetail(): String = "$cachedLaunchableCount launchable apps indexed"
    override fun onActiveDetail(): String = "Active • $cachedLaunchableCount installed apps ready to launch"
}

// 5. Device Action Agent
class DeviceActionAgent(
    private val context: Context
) : BaseMyraaAgent(
    agentId = "device_action",
    name = "Device Action Agent",
    role = "Settings Screens, Camera, Calculator, Clock, Torch & Volume",
    isOptional = false
) {
    override fun validateConfiguration(): AgentValidationResult {
        val hasAudio = context.getSystemService(Context.AUDIO_SERVICE) is AudioManager
        return if (hasAudio) {
            AgentValidationResult(true)
        } else {
            AgentValidationResult(false, AgentStatus.ERROR, "Android AudioManager unavailable")
        }
    }

    override fun validatePermissions(): AgentValidationResult = AgentValidationResult(true)

    override fun onReadyDetail(): String {
        val hasTorch = try {
            (context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)?.cameraIdList?.isNotEmpty() == true
        } catch (_: Exception) { false }
        return if (hasTorch) "Settings, Camera, Clock, Calculator, Volume & Torch ready"
        else "Settings, Camera, Clock, Calculator & Volume ready"
    }

    override fun onActiveDetail(): String = "Allowlisted Android Intents & Hardware APIs active"
}

// 6. Permission Manager Agent
class PermissionManagerAgent(
    private val context: Context
) : BaseMyraaAgent(
    agentId = "permission_manager",
    name = "Permission Manager Agent",
    role = "Runtime & Special Permission Auditor",
    isOptional = false
) {
    private val pm = PermissionManager(context)

    override fun validateConfiguration(): AgentValidationResult = AgentValidationResult(true)

    override fun validatePermissions(): AgentValidationResult {
        val hasMic = pm.checkMicrophonePermission()
        return if (hasMic) {
            AgentValidationResult(true)
        } else {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.PERMISSION_REQUIRED,
                reason = "Mandatory Microphone permission not granted."
            )
        }
    }

    override fun onReadyDetail(): String = "All core permissions verified"
    override fun onActiveDetail(): String {
        val granted = pm.getAllPermissionItems().count { it.state == com.example.permissions.PermissionState.GRANTED }
        val total = pm.getAllPermissionItems().size
        return "Auditing permissions ($granted/$total granted)"
    }
}

// 7. Accessibility Agent (Optional)
class AccessibilityAgent : BaseMyraaAgent(
    agentId = "accessibility_agent",
    name = "Accessibility Agent",
    role = "On-Screen Clicks, Typing, Scroll & Navigation",
    isOptional = true
) {
    override fun validateConfiguration(): AgentValidationResult = AgentValidationResult(true)

    override fun validatePermissions(): AgentValidationResult {
        return if (MyraaAccessibilityService.isRunning()) {
            AgentValidationResult(true)
        } else {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.PERMISSION_REQUIRED,
                reason = "Optional: Enable MYRAA AI in Android Accessibility Settings to activate."
            )
        }
    }

    override fun isOptionalFeatureEnabled(): Boolean = MyraaAccessibilityService.isRunning()
    override fun onReadyDetail(): String = "Accessibility Service connected"
    override fun onActiveDetail(): String = "Active • Performing user-directed screen actions"
}

// 8. Shizuku Integration Agent (Optional)
class ShizukuIntegrationAgent(
    private val context: Context
) : BaseMyraaAgent(
    agentId = "shizuku_integration",
    name = "Shizuku Integration Agent",
    role = "Allowlisted Rootless Elevated Actions",
    isOptional = true
) {
    override fun validateConfiguration(): AgentValidationResult {
        val status = ShizukuBridge.checkStatus(context)
        return when {
            !status.isInstalled -> AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.DISABLED,
                reason = "Optional add-on standby (Shizuku app not installed; standard automation works normally)."
            )
            !status.isRunning -> AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.DISABLED,
                reason = "Optional add-on standby (Shizuku service is not running)."
            )
            else -> AgentValidationResult(true)
        }
    }

    override fun validatePermissions(): AgentValidationResult {
        val status = ShizukuBridge.checkStatus(context)
        return if (status.permissionGranted) {
            AgentValidationResult(true)
        } else {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.PERMISSION_REQUIRED,
                reason = "Optional add-on standby: authorize MYRAA AI inside Shizuku app if rootless ADB is desired."
            )
        }
    }

    override fun isOptionalFeatureEnabled(): Boolean = ShizukuBridge.checkStatus(context).permissionGranted
    override fun onReadyDetail(): String = "Shizuku authorized and standing by"
    override fun onActiveDetail(): String = "Active • Allowlisted Shizuku bridge ready"
}

// 9. Floating Assistant Agent (Optional)
class FloatingAssistantAgent(
    private val context: Context,
    private val prefs: SecurePreferences
) : BaseMyraaAgent(
    agentId = "floating_assistant",
    name = "Floating Assistant Agent",
    role = "Floating Aurora Orb & Overlay Controls",
    isOptional = true
) {
    override fun validateConfiguration(): AgentValidationResult {
        return if (!prefs.isOverlayEnabled()) {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.DISABLED,
                reason = "Floating orb disabled by user in App Settings."
            )
        } else {
            AgentValidationResult(true)
        }
    }

    override fun validatePermissions(): AgentValidationResult {
        return if (Settings.canDrawOverlays(context)) {
            AgentValidationResult(true)
        } else {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.PERMISSION_REQUIRED,
                reason = "Optional: Grant 'Draw Over Other Apps' permission for floating orb."
            )
        }
    }

    override fun isOptionalFeatureEnabled(): Boolean = prefs.isOverlayEnabled() && Settings.canDrawOverlays(context)
    override fun onReadyDetail(): String = "Overlay permission granted"
    override fun onActiveDetail(): String = "Floating Aurora Orb active over apps"
}

// 10. Activity Logger Agent
class ActivityLoggerAgent(
    private val context: Context
) : BaseMyraaAgent(
    agentId = "activity_logger",
    name = "Activity Logger Agent",
    role = "Room DB Local Action & Audit Persistence",
    isOptional = false
) {
    override fun validateConfiguration(): AgentValidationResult {
        return try {
            AppDatabase.getInstance(context)
            AgentValidationResult(true)
        } catch (e: Exception) {
            AgentValidationResult(
                isValid = false,
                statusOnFailure = AgentStatus.ERROR,
                reason = "Room database error: ${e.localizedMessage}"
            )
        }
    }

    override fun validatePermissions(): AgentValidationResult = AgentValidationResult(true)
    override fun onReadyDetail(): String = "Room DB initialized"
    override fun onActiveDetail(): String = "Recording all pipeline actions locally in Room DB"
}

// 11. System Health Agent
class SystemHealthAgent(
    private val reportProvider: () -> List<AgentHealthReport>
) : BaseMyraaAgent(
    agentId = "system_health",
    name = "System Health Agent",
    role = "Dependency Verification & Fleet Diagnostics",
    isOptional = false
) {
    override fun validateConfiguration(): AgentValidationResult = AgentValidationResult(true)
    override fun validatePermissions(): AgentValidationResult = AgentValidationResult(true)
    override fun onReadyDetail(): String = "Diagnostics standing by"
    override fun onActiveDetail(): String {
        val reports = reportProvider().filter { it.agentId != agentId }
        val activeCount = reports.count { it.status == AgentStatus.ACTIVE }
        val unavailable = reports.count {
            it.status == AgentStatus.ERROR || it.status == AgentStatus.DISABLED ||
                it.status == AgentStatus.PERMISSION_REQUIRED || it.status == AgentStatus.NOT_CONFIGURED
        }
        return "Monitoring fleet: $activeCount active, $unavailable unavailable/optional"
    }
}
