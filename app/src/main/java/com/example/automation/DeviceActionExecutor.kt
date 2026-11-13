package com.example.automation

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.data.api.ParsedCommand
import com.example.data.db.AppDatabase
import com.example.data.db.AutomationLogEntity
import com.example.service.MyraaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExecutionResult(
    val success: Boolean,
    val actionType: String,
    val target: String,
    val details: String
)

class DeviceActionExecutor(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val logDao = db.automationLogDao()

    suspend fun executeCommand(command: ParsedCommand): ExecutionResult = withContext(Dispatchers.Main) {
        val result = when (command.actionType) {
            "SCREEN_CLICK" -> performScreenClick(command.target)
            "SCREEN_TYPE" -> performScreenType(if (command.extraData.isNotBlank()) command.extraData else command.target)
            "OPEN_APP" -> openApp(command.target)
            "FLASHLIGHT" -> toggleFlashlight(command.target)
            "VOLUME" -> adjustVolume(command.target)
            "ACCESSIBILITY" -> performAccessibilityAction(command.target)
            "SETTINGS" -> openSystemSettings(command.target)
            "WEB_SEARCH" -> performWebSearch(command.target)
            "SYSTEM_QUERY" -> ExecutionResult(true, "SYSTEM_QUERY", command.target, "Answered user system query")
            else -> ExecutionResult(true, "CHAT", command.target, "Conversational response delivered")
        }

        // Transparency: Log every action locally in Room database
        withContext(Dispatchers.IO) {
            try {
                logDao.insertLog(
                    AutomationLogEntity(
                        voicePrompt = command.rawPrompt,
                        actionType = result.actionType,
                        target = result.target,
                        status = if (result.success) "SUCCESS" else "FAILED",
                        aiResponse = command.spokenResponse,
                        executionDetails = result.details
                    )
                )
            } catch (e: Exception) {
                Log.e("DeviceActionExecutor", "Failed to log action", e)
            }
        }

        return@withContext result
    }

    private fun openApp(appName: String): ExecutionResult {
        val pm = context.packageManager
        val query = appName.lowercase().trim()

        // Known direct package mappings for instant reliable launch
        val directPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "camera" to "camera_intent",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "gmail" to "com.google.android.gm"
        )

        if (query == "camera") {
            return try {
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ExecutionResult(true, "OPEN_APP", "Camera", "Launched system Camera application")
            } catch (e: Exception) {
                openAppBySearch(pm, query)
            }
        }

        if (directPackages.containsKey(query)) {
            val pkg = directPackages[query]!!
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ExecutionResult(true, "OPEN_APP", appName, "Launched direct package $pkg")
            }
        }

        return openAppBySearch(pm, query)
    }

    private fun openAppBySearch(pm: PackageManager, query: String): ExecutionResult {
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            var matchedPkg: String? = null
            var matchedLabel = ""

            for (app in installedApps) {
                // Filter launchable apps
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    if (label.contains(query) || query.contains(label)) {
                        matchedPkg = app.packageName
                        matchedLabel = label
                        break
                    }
                }
            }

            if (matchedPkg != null) {
                val intent = pm.getLaunchIntentForPackage(matchedPkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) {
                    context.startActivity(intent)
                    return ExecutionResult(true, "OPEN_APP", matchedLabel, "Launched app package $matchedPkg")
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceActionExecutor", "Error finding app", e)
        }

        // Fallback: search on Play Store or Web
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ExecutionResult(true, "OPEN_APP", query, "App not found locally, opening store search")
        } catch (e: Exception) {
            ExecutionResult(false, "OPEN_APP", query, "Could not find or launch application '$query'")
        }
    }

    private fun toggleFlashlight(target: String): ExecutionResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ExecutionResult(false, "FLASHLIGHT", target, "Camera service unavailable")

        val turnOn = target.contains("on") || target.contains("start") || target.contains("enable")
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ExecutionResult(false, "FLASHLIGHT", target, "No camera flash unit found")
            cameraManager.setTorchMode(cameraId, turnOn)
            ExecutionResult(true, "FLASHLIGHT", if (turnOn) "ON" else "OFF", "Flashlight torch set to $turnOn")
        } catch (e: CameraAccessException) {
            ExecutionResult(false, "FLASHLIGHT", target, "Torch control error: ${e.message}")
        }
    }

    private fun adjustVolume(target: String): ExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ExecutionResult(false, "VOLUME", target, "Audio service unavailable")

        return try {
            when (target.lowercase()) {
                "up", "raise", "increase" -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ExecutionResult(true, "VOLUME", "Volume UP", "Raised music audio stream volume")
                }
                "down", "lower", "decrease" -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ExecutionResult(true, "VOLUME", "Volume DOWN", "Lowered music audio stream volume")
                }
                "mute", "silent" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    ExecutionResult(true, "VOLUME", "MUTED", "Set media volume to mute")
                }
                "max", "full" -> {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                    ExecutionResult(true, "VOLUME", "MAX", "Set media volume to maximum level ($maxVol)")
                }
                else -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ExecutionResult(true, "VOLUME", "Volume Adjusted", "Adjusted media volume")
                }
            }
        } catch (e: Exception) {
            ExecutionResult(false, "VOLUME", target, "Volume adjustment failed: ${e.message}")
        }
    }

    private fun performAccessibilityAction(target: String): ExecutionResult {
        val service = MyraaAccessibilityService.instance
        if (service == null) {
            return ExecutionResult(
                false,
                "ACCESSIBILITY",
                target,
                "Accessibility Service is not active. Please enable Myraa AI in System Settings > Accessibility."
            )
        }

        val success = when (target.lowercase()) {
            "home" -> service.goHome()
            "back" -> service.goBack()
            "recents" -> service.openRecents()
            "notifications" -> service.openNotifications()
            "quick_settings" -> service.openQuickSettings()
            "lock" -> service.lockScreen()
            "scroll_down" -> service.scroll(forward = true)
            "scroll_up" -> service.scroll(forward = false)
            else -> false
        }

        return ExecutionResult(
            success = success,
            actionType = "ACCESSIBILITY",
            target = target,
            details = if (success) "Executed accessibility action: $target" else "Accessibility action $target could not be dispatched"
        )
    }

    private fun openSystemSettings(target: String): ExecutionResult {
        val action = when (target.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ExecutionResult(true, "SETTINGS", target, "Opened system settings: $action")
        } catch (e: Exception) {
            ExecutionResult(false, "SETTINGS", target, "Failed to open settings: ${e.message}")
        }
    }

    private fun performWebSearch(query: String): ExecutionResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ExecutionResult(true, "WEB_SEARCH", query, "Dispatched web search query for '$query'")
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ExecutionResult(true, "WEB_SEARCH", query, "Opened browser search for '$query'")
            } catch (e2: Exception) {
                ExecutionResult(false, "WEB_SEARCH", query, "Web search failed: ${e2.message}")
            }
        }
    }

    private fun performScreenClick(target: String): ExecutionResult {
        val accessibility = com.example.service.MyraaAccessibilityService.instance
            ?: return ExecutionResult(false, "SCREEN_CLICK", target, "Accessibility Service required for hands-free screen clicks")

        val elements = accessibility.getVisibleScreenElements()
        val match = elements.firstOrNull { it.text.contains(target, ignoreCase = true) }

        return if (match != null) {
            val x = match.bounds.centerX().toFloat()
            val y = match.bounds.centerY().toFloat()
            com.example.service.DigitalMouseService.instance?.animateClick(x, y)
                ?: accessibility.dispatchClickAt(x, y)
            ExecutionResult(true, "SCREEN_CLICK", target, "Digital mouse clicked on \"${match.text}\" at ($x, $y)")
        } else {
            val clicked = accessibility.clickByText(target)
            ExecutionResult(clicked, "SCREEN_CLICK", target, if (clicked) "Clicked \"$target\"" else "Target \"$target\" not found on current screen")
        }
    }

    private fun performScreenType(textToType: String): ExecutionResult {
        val accessibility = com.example.service.MyraaAccessibilityService.instance
            ?: return ExecutionResult(false, "SCREEN_TYPE", textToType, "Accessibility Service required for AI typing")

        com.example.service.DigitalMouseService.instance?.animateTyping(textToType)
            ?: accessibility.typeTextIntoFocused(textToType)

        return ExecutionResult(true, "SCREEN_TYPE", textToType, "AI Virtual Keyboard typed \"$textToType\" into screen field")
    }
}
