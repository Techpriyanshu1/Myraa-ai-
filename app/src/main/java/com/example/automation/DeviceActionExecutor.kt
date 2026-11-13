package com.example.automation

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.example.data.api.ParsedCommand
import com.example.data.db.AppDatabase
import com.example.data.db.AutomationLogEntity
import com.example.data.pref.SecurePreferences
import com.example.service.MyraaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableAppInfo(
    val label: String,
    val packageName: String,
    val isSelectedByUser: Boolean = false
)

data class ExecutionResult(
    val success: Boolean,
    val actionType: String,
    val target: String,
    val details: String,
    val spokenFeedback: String? = null
)

class DeviceActionExecutor(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val logDao = db.automationLogDao()
    private val prefs = SecurePreferences(context)

    fun getInstalledLaunchableApps(): List<LaunchableAppInfo> {
        val pm = context.packageManager
        val selected = prefs.getSelectedApps()
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return try {
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            resolveInfos
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                    if (label.isBlank()) return@mapNotNull null
                    LaunchableAppInfo(
                        label = label,
                        packageName = pkg,
                        isSelectedByUser = selected.contains(pkg)
                    )
                }
                .distinctBy { it.packageName }
                .sortedWith(compareByDescending<LaunchableAppInfo> { it.isSelectedByUser }.thenBy { it.label.lowercase() })
        } catch (e: Exception) {
            Log.e("DeviceActionExecutor", "Failed to query launchable apps", e)
            emptyList()
        }
    }

    suspend fun launchSpecificPackage(packageName: String, label: String): ExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val result = try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                context.startActivity(intent)
                ExecutionResult(
                    success = true,
                    actionType = "OPEN_APP",
                    target = label,
                    details = "Launched user-selected app $label ($packageName)",
                    spokenFeedback = "$label open kar diya hai."
                )
            } else {
                ExecutionResult(
                    success = false,
                    actionType = "OPEN_APP",
                    target = label,
                    details = "App '$label' ($packageName) has no launcher activity or was uninstalled.",
                    spokenFeedback = "Maaf kijiye, $label is device par launch nahi ho paaya."
                )
            }
        } catch (e: Exception) {
            ExecutionResult(
                success = false,
                actionType = "OPEN_APP",
                target = label,
                details = "Failed to launch $label: ${e.message}",
                spokenFeedback = "$label open karne me dikkat aayi."
            )
        }

        val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        logPipelineOutcome(
            rawPrompt = "Manual Launch: $label",
            recognizedIntent = "open_app",
            actionType = result.actionType,
            target = result.target,
            agentInvolved = "App Launcher Agent",
            permissionStatus = "GRANTED",
            confirmationStatus = "USER_SELECTED",
            status = if (result.success) "SUCCESS" else "FAILED",
            spokenResponse = result.spokenFeedback ?: result.details,
            details = result.details,
            errorInfo = if (result.success) "" else result.details,
            executionDurationMs = durationMs
        )
        result
    }

    suspend fun executeCommand(
        command: ParsedCommand,
        confirmationStatus: String = "AUTO_APPROVED"
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val normalizedAction = command.actionType.uppercase()
        val result = when (normalizedAction) {
            "SCREEN_CLICK" -> performScreenClick(command.target)
            "SCREEN_TYPE" -> performScreenType(if (command.extraData.isNotBlank()) command.extraData else command.target)
            "OPEN_APP" -> openApp(command.target)
            "OPEN_CAMERA" -> openCamera()
            "OPEN_CALCULATOR" -> openCalculator()
            "OPEN_CLOCK" -> openClock()
            "OPEN_SETTINGS", "SETTINGS" -> openSystemSettings(command.target)
            "FLASHLIGHT" -> toggleFlashlight(command.target)
            "VOLUME" -> adjustVolume(command.target)
            "ACCESSIBILITY" -> performAccessibilityAction(command.target)
            "WEB_SEARCH" -> performWebSearch(command.target)
            "YOUTUBE_SEARCH" -> searchYouTube(command.target)
            "DIAL" -> dialPhoneNumber(command.target)
            "PAUSE_LISTENING" -> ExecutionResult(true, "PAUSE_LISTENING", "voice", "Voice listening paused by user")
            "RESUME_LISTENING" -> ExecutionResult(true, "RESUME_LISTENING", "voice", "Voice listening resumed by user")
            "STOP_AI" -> ExecutionResult(true, "STOP_AI", "session", "MYRAA AI session stopped by user")
            "LIST_APPS" -> ExecutionResult(true, "LIST_APPS", "apps", "Opened installed apps browser")
            "SHOW_HISTORY" -> ExecutionResult(true, "SHOW_HISTORY", "history", "Navigated to local activity history")
            "SYSTEM_QUERY" -> ExecutionResult(true, "SYSTEM_QUERY", command.target, "Answered user system query")
            else -> ExecutionResult(true, "CHAT", command.target, "Conversational response delivered")
        }

        val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val agentInvolved = resolveAgentName(normalizedAction)
        val permissionStatus = resolvePermissionStatus(normalizedAction)

        logPipelineOutcome(
            rawPrompt = command.rawPrompt,
            recognizedIntent = command.intent,
            actionType = result.actionType,
            target = result.target,
            agentInvolved = agentInvolved,
            permissionStatus = permissionStatus,
            confirmationStatus = confirmationStatus,
            status = if (result.success) "SUCCESS" else "FAILED",
            spokenResponse = result.spokenFeedback ?: command.spokenResponse,
            details = result.details,
            errorInfo = if (result.success) "" else result.details,
            executionDurationMs = durationMs
        )

        return@withContext result
    }

    private fun resolveAgentName(actionType: String): String = when (actionType) {
        "OPEN_APP", "LIST_APPS" -> "App Launcher Agent"
        "OPEN_CAMERA", "OPEN_CALCULATOR", "OPEN_CLOCK", "OPEN_SETTINGS", "SETTINGS", "FLASHLIGHT", "VOLUME", "DIAL", "WEB_SEARCH", "YOUTUBE_SEARCH" -> "Device Action Agent"
        "ACCESSIBILITY", "SCREEN_CLICK", "SCREEN_TYPE" -> "Accessibility Agent"
        "PAUSE_LISTENING", "RESUME_LISTENING", "STOP_AI" -> "Voice Recognition Agent"
        "SHOW_HISTORY" -> "Activity Logger Agent"
        else -> "Gemini Intelligence Agent"
    }

    private fun resolvePermissionStatus(actionType: String): String = when (actionType) {
        "ACCESSIBILITY", "SCREEN_CLICK", "SCREEN_TYPE" ->
            if (MyraaAccessibilityService.isRunning()) "GRANTED" else "PERMISSION_REQUIRED"
        "PAUSE_LISTENING", "RESUME_LISTENING", "STOP_AI" ->
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "PERMISSION_REQUIRED"
        else -> "NOT_REQUIRED"
    }

    suspend fun logPipelineOutcome(
        rawPrompt: String,
        recognizedIntent: String = "unknown",
        actionType: String,
        target: String,
        agentInvolved: String = "System Health Agent",
        permissionStatus: String = "GRANTED",
        confirmationStatus: String = "AUTO_APPROVED",
        status: String,
        spokenResponse: String,
        details: String,
        errorInfo: String = "",
        executionDurationMs: Long = 0L
    ) {
        val promptForLog = if (prefs.isCommandTextLoggingEnabled()) {
            com.example.core.NetworkRetryHelper.sanitizeSensitiveText(rawPrompt)
        } else {
            "[Redacted per privacy setting]"
        }
        val safeResponse = com.example.core.NetworkRetryHelper.sanitizeSensitiveText(spokenResponse)
        val safeDetails = com.example.core.NetworkRetryHelper.sanitizeSensitiveText(details)
        val safeError = com.example.core.NetworkRetryHelper.sanitizeSensitiveText(errorInfo)

        withContext(Dispatchers.IO) {
            try {
                logDao.insertLog(
                    AutomationLogEntity(
                        voicePrompt = promptForLog,
                        recognizedIntent = recognizedIntent,
                        actionType = actionType,
                        target = target,
                        agentInvolved = agentInvolved,
                        permissionStatus = permissionStatus,
                        confirmationStatus = confirmationStatus,
                        status = status,
                        aiResponse = safeResponse,
                        executionDetails = safeDetails,
                        errorInfo = safeError,
                        executionDurationMs = executionDurationMs
                    )
                )
            } catch (e: Exception) {
                Log.e("DeviceActionExecutor", "Failed to log action", e)
            }
        }
    }

    private fun openApp(appName: String): ExecutionResult {
        val pm = context.packageManager
        val query = appName.lowercase().trim()
        if (query.isBlank()) {
            return ExecutionResult(
                success = false,
                actionType = "OPEN_APP",
                target = appName,
                details = "No app name was specified.",
                spokenFeedback = "Kripya batayein kaunsa app open karna hai."
            )
        }

        if (query == "camera") return openCamera()
        if (query == "calculator" || query == "calc") return openCalculator()
        if (query == "clock" || query == "alarm") return openClock()
        if (query == "settings") return openSystemSettings("settings")

        // 0. Direct package-name launch if Gemini passed a qualified package name
        if (query.contains('.')) {
            val pkgIntent = pm.getLaunchIntentForPackage(query)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (pkgIntent != null) {
                try {
                    context.startActivity(pkgIntent)
                    return ExecutionResult(
                        success = true,
                        actionType = "OPEN_APP",
                        target = query,
                        details = "Launched package $query",
                        spokenFeedback = "$appName open kar diya hai."
                    )
                } catch (_: Exception) {}
            }
        }

        // 1. Check user-selected preferred apps first
        val installed = getInstalledLaunchableApps()
        val userSelectedMatch = installed.firstOrNull {
            it.isSelectedByUser && (it.label.lowercase() == query || it.label.lowercase().contains(query))
        }
        if (userSelectedMatch != null) {
            val intent = pm.getLaunchIntentForPackage(userSelectedMatch.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                try {
                    context.startActivity(intent)
                    return ExecutionResult(
                        success = true,
                        actionType = "OPEN_APP",
                        target = userSelectedMatch.label,
                        details = "Launched user-selected app ${userSelectedMatch.label} (${userSelectedMatch.packageName})",
                        spokenFeedback = "${userSelectedMatch.label} open kar diya hai."
                    )
                } catch (_: Exception) {}
            }
        }

        // 2. Known direct package mappings
        val directPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "telegram" to "org.telegram.messenger",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "play store" to "com.android.vending",
            "google play" to "com.android.vending",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "snapchat" to "com.snapchat.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "calendar" to "com.google.android.calendar",
            "files" to "com.google.android.apps.nbu.files"
        )
        directPackages[query]?.let { pkg ->
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return ExecutionResult(
                        success = true,
                        actionType = "OPEN_APP",
                        target = appName,
                        details = "Launched official app intent for $pkg",
                        spokenFeedback = "$appName open kar diya hai."
                    )
                } catch (_: Exception) {}
            }
        }

        // 3. Search installed launchable apps
        val exactOrPartialMatch = installed.firstOrNull { it.label.lowercase() == query }
            ?: installed.firstOrNull { it.label.lowercase().contains(query) || query.contains(it.label.lowercase()) }

        if (exactOrPartialMatch != null) {
            val launchIntent = pm.getLaunchIntentForPackage(exactOrPartialMatch.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                try {
                    context.startActivity(launchIntent)
                    return ExecutionResult(
                        success = true,
                        actionType = "OPEN_APP",
                        target = exactOrPartialMatch.label,
                        details = "Launched installed app ${exactOrPartialMatch.label} (${exactOrPartialMatch.packageName})",
                        spokenFeedback = "${exactOrPartialMatch.label} open kar diya hai."
                    )
                } catch (_: Exception) {}
            }
        }

        // Fallback category intents for generic system targets (dialer/phone/browser)
        if (query in listOf("phone", "dialer", "call")) {
            return try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(dialIntent)
                ExecutionResult(true, "OPEN_APP", "Dialer", "Opened Phone Dialer", "Phone dialer open kar diya hai.")
            } catch (_: Exception) {
                ExecutionResult(false, "OPEN_APP", appName, "Dialer not available", "Phone dialer available nahi hai.")
            }
        }
        if (query in listOf("browser", "internet", "web")) {
            return try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                ExecutionResult(true, "OPEN_APP", "Browser", "Opened default web browser", "Browser open kar diya hai.")
            } catch (_: Exception) {
                ExecutionResult(false, "OPEN_APP", appName, "Web browser not available", "Browser available nahi hai.")
            }
        }

        // 4. Honest missing app handling (never fake success)
        return ExecutionResult(
            success = false,
            actionType = "OPEN_APP",
            target = appName,
            details = "Application '$appName' is not installed on this device.",
            spokenFeedback = "Maaf kijiye, '$appName' app aapke phone me installed nahi mila."
        )
    }

    private fun openCamera(): ExecutionResult {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                val fallback = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
            ExecutionResult(true, "OPEN_CAMERA", "Camera", "Launched device Camera app", "Camera open kar diya hai.")
        } catch (e: Exception) {
            ExecutionResult(false, "OPEN_CAMERA", "Camera", "No camera application available: ${e.message}", "Camera app available nahi hai.")
        }
    }

    private fun openCalculator(): ExecutionResult {
        return try {
            val calcIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (calcIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(calcIntent)
                return ExecutionResult(true, "OPEN_CALCULATOR", "Calculator", "Launched Calculator via CATEGORY_APP_CALCULATOR", "Calculator open kar diya hai.")
            }
            val match = getInstalledLaunchableApps().firstOrNull {
                it.label.contains("calculator", ignoreCase = true) || it.packageName.contains("calculator", ignoreCase = true)
            }
            if (match != null) {
                val intent = context.packageManager.getLaunchIntentForPackage(match.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) {
                    context.startActivity(intent)
                    return ExecutionResult(true, "OPEN_CALCULATOR", match.label, "Launched Calculator (${match.packageName})", "Calculator open kar diya hai.")
                }
            }
            ExecutionResult(false, "OPEN_CALCULATOR", "Calculator", "Calculator app not found on device", "Is device par Calculator app nahi mila.")
        } catch (e: Exception) {
            ExecutionResult(false, "OPEN_CALCULATOR", "Calculator", "Failed to open Calculator: ${e.message}", "Calculator open karne me dikkat aayi.")
        }
    }

    private fun openClock(): ExecutionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return ExecutionResult(true, "OPEN_CLOCK", "Clock", "Opened Clock / Alarms screen", "Clock open kar diya hai.")
            }
            val match = getInstalledLaunchableApps().firstOrNull {
                it.label.contains("clock", ignoreCase = true) || it.packageName.contains("deskclock", ignoreCase = true)
            }
            if (match != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(match.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    return ExecutionResult(true, "OPEN_CLOCK", match.label, "Launched Clock (${match.packageName})", "Clock open kar diya hai.")
                }
            }
            ExecutionResult(false, "OPEN_CLOCK", "Clock", "Clock app not found on device", "Clock app is device par nahi mila.")
        } catch (e: Exception) {
            ExecutionResult(false, "OPEN_CLOCK", "Clock", "Failed to open Clock: ${e.message}", "Clock open karne me dikkat aayi.")
        }
    }

    private fun openSystemSettings(target: String): ExecutionResult {
        val cleanTarget = target.lowercase().trim()
        val (action, label, usePackageData) = when {
            cleanTarget.contains("wifi") || cleanTarget.contains("wi-fi") ->
                Triple(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi Settings", false)
            cleanTarget.contains("bluetooth") ->
                Triple(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth Settings", false)
            cleanTarget.contains("display") || cleanTarget.contains("brightness") ->
                Triple(Settings.ACTION_DISPLAY_SETTINGS, "Display Settings", false)
            cleanTarget.contains("sound") || cleanTarget.contains("audio") ->
                Triple(Settings.ACTION_SOUND_SETTINGS, "Sound Settings", false)
            cleanTarget.contains("location") || cleanTarget.contains("gps") ->
                Triple(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location Settings", false)
            cleanTarget.contains("notification") -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Triple(Settings.ACTION_APP_NOTIFICATION_SETTINGS, "Notification Settings", true)
                } else {
                    Triple(Settings.ACTION_SETTINGS, "System Settings", false)
                }
            }
            cleanTarget == "app" || cleanTarget == "apps" || cleanTarget.contains("app settings") ->
                Triple(Settings.ACTION_APPLICATION_SETTINGS, "App Settings", false)
            else ->
                Triple(Settings.ACTION_SETTINGS, "System Settings", false)
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (usePackageData && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                ExecutionResult(
                    success = true,
                    actionType = "OPEN_SETTINGS",
                    target = label,
                    details = "Opened $label screen ($action). Setting state was NOT modified automatically.",
                    spokenFeedback = "$label screen open kar di hai. Aap yahan se apni pasand ke mutabik badal sakte hain."
                )
            } else {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(fallback)
                ExecutionResult(
                    success = true,
                    actionType = "OPEN_SETTINGS",
                    target = "System Settings",
                    details = "Opened general System Settings screen (specific action $action unsupported on this ROM).",
                    spokenFeedback = "System settings screen open kar di hai."
                )
            }
        } catch (e: Exception) {
            ExecutionResult(
                success = false,
                actionType = "OPEN_SETTINGS",
                target = label,
                details = "Failed to open $label: ${e.message}",
                spokenFeedback = "$label open karne me samasya aayi."
            )
        }
    }

    private fun toggleFlashlight(target: String): ExecutionResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ExecutionResult(false, "FLASHLIGHT", target, "Camera service unavailable", "Camera flash service available nahi hai.")

        val turnOn = target.contains("on", ignoreCase = true) || target.contains("start", ignoreCase = true) || target.contains("enable", ignoreCase = true)
        return try {
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBack = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && isBack
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ExecutionResult(false, "FLASHLIGHT", target, "No camera flash unit found", "Is device me flash light nahi mili.")

            cameraManager.setTorchMode(flashCameraId, turnOn)
            val stateLabel = if (turnOn) "ON" else "OFF"
            ExecutionResult(
                success = true,
                actionType = "FLASHLIGHT",
                target = stateLabel,
                details = "Flashlight torch set to $stateLabel",
                spokenFeedback = if (turnOn) "Flashlight on kar di hai." else "Flashlight band kar di hai."
            )
        } catch (e: Exception) {
            ExecutionResult(false, "FLASHLIGHT", target, "Torch control error: ${e.message}", "Flashlight control nahi ho paaya.")
        }
    }

    private fun adjustVolume(target: String): ExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ExecutionResult(false, "VOLUME", target, "Audio service unavailable", "Audio control available nahi hai.")

        return try {
            when (target.lowercase()) {
                "up", "raise", "increase" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ExecutionResult(true, "VOLUME", "Volume UP", "Raised music audio stream volume", "Awaaz badha di hai.")
                }
                "down", "lower", "decrease" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    ExecutionResult(true, "VOLUME", "Volume DOWN", "Lowered music audio stream volume", "Awaaz kam kar di hai.")
                }
                "mute", "silent" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    ExecutionResult(true, "VOLUME", "MUTED", "Set media volume to mute", "Media volume mute kar di hai.")
                }
                "max", "full" -> {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                    ExecutionResult(true, "VOLUME", "MAX", "Set media volume to maximum level ($maxVol)", "Volume full kar di hai.")
                }
                else -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ExecutionResult(true, "VOLUME", "Volume Adjusted", "Adjusted media volume", "Volume adjust kar di hai.")
                }
            }
        } catch (e: Exception) {
            ExecutionResult(false, "VOLUME", target, "Volume adjustment failed: ${e.message}", "Volume badalne me samasya aayi.")
        }
    }

    private fun performAccessibilityAction(target: String): ExecutionResult {
        val service = MyraaAccessibilityService.instance
            ?: return ExecutionResult(
                success = false,
                actionType = "ACCESSIBILITY",
                target = target,
                details = "Accessibility Service is not active. Enable MYRAA AI in Android Settings > Accessibility.",
                spokenFeedback = "Is action ke liye Accessibility Service enable karni hogi. Permission Center me jakar ON karein."
            )

        val success = when (target.lowercase()) {
            "home" -> service.goHome()
            "back" -> service.goBack()
            "recents" -> service.openRecents()
            "notifications" -> service.openNotifications()
            "quick_settings" -> service.openQuickSettings()
            "lock" -> service.lockScreen()
            "scroll_down", "next_short", "next_video", "swipe_up" -> service.scrollSmart(forward = true)
            "scroll_up", "previous_short", "previous_video", "swipe_down" -> service.scrollSmart(forward = false)
            else -> false
        }

        return ExecutionResult(
            success = success,
            actionType = "ACCESSIBILITY",
            target = target,
            details = if (success) "Executed accessibility action: $target" else "Accessibility action $target could not be dispatched",
            spokenFeedback = if (success) "Ho gaya." else "Accessibility action $target fail ho gaya."
        )
    }

    private fun searchYouTube(query: String): ExecutionResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return openApp("youtube")
        }
        val ytPackage = "com.google.android.youtube"
        val encoded = Uri.encode(cleanQuery)
        return try {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(ytPackage)
                putExtra(SearchManager.QUERY, cleanQuery)
                putExtra("query", cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (searchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(searchIntent)
                return ExecutionResult(
                    success = true,
                    actionType = "YOUTUBE_SEARCH",
                    target = cleanQuery,
                    details = "Searched YouTube for '$cleanQuery' via ACTION_SEARCH",
                    spokenFeedback = "YouTube pe '$cleanQuery' search kar rahi hoon."
                )
            }

            val viewAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                setPackage(ytPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (viewAppIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(viewAppIntent)
                return ExecutionResult(
                    success = true,
                    actionType = "YOUTUBE_SEARCH",
                    target = cleanQuery,
                    details = "Opened YouTube search URL in YouTube app for '$cleanQuery'",
                    spokenFeedback = "YouTube pe '$cleanQuery' search kar rahi hoon."
                )
            }

            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            ExecutionResult(
                success = true,
                actionType = "YOUTUBE_SEARCH",
                target = cleanQuery,
                details = "Opened YouTube search URL in browser for '$cleanQuery'",
                spokenFeedback = "YouTube pe '$cleanQuery' search kar rahi hoon."
            )
        } catch (e: Exception) {
            ExecutionResult(
                success = false,
                actionType = "YOUTUBE_SEARCH",
                target = cleanQuery,
                details = "YouTube search failed: ${e.message}",
                spokenFeedback = "YouTube pe search nahi ho paaya."
            )
        }
    }

    private fun performWebSearch(query: String): ExecutionResult {
        return try {
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (searchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(searchIntent)
                ExecutionResult(true, "WEB_SEARCH", query, "Dispatched web search query for '$query'", "'$query' search kar rahi hoon.")
            } else {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                ExecutionResult(true, "WEB_SEARCH", query, "Opened browser search for '$query'", "'$query' browser me search kar rahi hoon.")
            }
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                ExecutionResult(true, "WEB_SEARCH", query, "Opened browser search for '$query'", "'$query' browser me search kar rahi hoon.")
            } catch (e2: Exception) {
                ExecutionResult(false, "WEB_SEARCH", query, "Web search failed: ${e2.message}", "Web search open nahi ho paaya.")
            }
        }
    }

    private fun dialPhoneNumber(target: String): ExecutionResult {
        val cleanNumber = target.replace(Regex("[^+0-9*#]"), "").trim()
        if (cleanNumber.isBlank()) {
            return ExecutionResult(false, "DIAL", target, "No valid phone number provided.", "Kripya sahi phone number batayein.")
        }
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (dialIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(dialIntent)
                ExecutionResult(true, "DIAL", cleanNumber, "Opened system dialer for $cleanNumber", "Dialer me $cleanNumber open kar diya hai.")
            } else {
                ExecutionResult(false, "DIAL", cleanNumber, "No phone dialer app available on device.", "Is device par dialer app available nahi hai.")
            }
        } catch (e: Exception) {
            ExecutionResult(false, "DIAL", cleanNumber, "Failed to open dialer: ${e.message}", "Dialer open nahi ho paaya.")
        }
    }

    private fun performScreenClick(target: String): ExecutionResult {
        val accessibility = MyraaAccessibilityService.instance
            ?: return ExecutionResult(
                success = false,
                actionType = "SCREEN_CLICK",
                target = target,
                details = "Accessibility Service required for hands-free screen clicks",
                spokenFeedback = "Screen click ke liye Accessibility Service enable karein."
            )

        if (com.example.service.DigitalMouseService.instance == null && Settings.canDrawOverlays(context)) {
            com.example.service.DigitalMouseService.start(context)
        }

        val match = accessibility.findBestClickableElement(target)
        return if (match != null) {
            val (x, y) = accessibility.clampToScreen(
                match.bounds.exactCenterX(),
                match.bounds.exactCenterY()
            )
            val mouse = com.example.service.DigitalMouseService.instance
            if (mouse != null) {
                mouse.animateClick(x, y)
            } else {
                val dispatched = accessibility.dispatchClickAt(x, y)
                if (!dispatched) {
                    accessibility.clickByText(target)
                }
            }
            ExecutionResult(
                success = true,
                actionType = "SCREEN_CLICK",
                target = target,
                details = "Digital mouse clicked on \"${match.text}\" at (${x.toInt()}, ${y.toInt()})",
                spokenFeedback = "\"${match.text}\" par tap kar diya hai."
            )
        } else {
            val clicked = accessibility.clickByText(target)
            ExecutionResult(
                success = clicked,
                actionType = "SCREEN_CLICK",
                target = target,
                details = if (clicked) "Clicked \"$target\"" else "Target \"$target\" not found on current screen",
                spokenFeedback = if (clicked) "\"$target\" par tap kar diya hai." else "Screen par \"$target\" button nahi mila."
            )
        }
    }

    private fun performScreenType(textToType: String): ExecutionResult {
        val accessibility = MyraaAccessibilityService.instance
            ?: return ExecutionResult(
                success = false,
                actionType = "SCREEN_TYPE",
                target = textToType,
                details = "Accessibility Service required for AI typing",
                spokenFeedback = "Screen typing ke liye Accessibility Service enable karein."
            )

        if (com.example.service.DigitalMouseService.instance == null && Settings.canDrawOverlays(context)) {
            com.example.service.DigitalMouseService.start(context)
        }

        val mouse = com.example.service.DigitalMouseService.instance
        if (mouse != null) {
            mouse.animateTyping(textToType)
        } else {
            accessibility.typeTextIntoFocused(textToType)
        }

        return ExecutionResult(true, "SCREEN_TYPE", textToType, "AI Virtual Keyboard typed \"$textToType\" into screen field", "\"$textToType\" type kar diya hai.")
    }
}
