package com.example.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.MyraaAccessibilityService

enum class PermissionState {
    GRANTED,
    DENIED,
    REQUIRED_ACTION
}

data class PermissionItem(
    val id: String,
    val title: String,
    val description: String,
    val whyNeeded: String,
    val state: PermissionState,
    val isMandatory: Boolean,
    val settingsAction: String? = null
)

class PermissionManager(private val context: Context) {

    fun checkMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun checkAccessibilityPermission(): Boolean {
        return MyraaAccessibilityService.isRunning()
    }

    fun getAllPermissionItems(): List<PermissionItem> {
        val hasMic = checkMicrophonePermission()
        val hasOverlay = checkOverlayPermission()
        val hasAccessibility = checkAccessibilityPermission()
        val hasNotifications = checkNotificationPermission()

        return listOf(
            PermissionItem(
                id = "microphone",
                title = "Microphone Access",
                description = "Enables real-time voice recognition and continuous speech commands.",
                whyNeeded = "Required to hear your voice commands in Hindi, English, and Hinglish.",
                state = if (hasMic) PermissionState.GRANTED else PermissionState.DENIED,
                isMandatory = true
            ),
            PermissionItem(
                id = "accessibility",
                title = "Accessibility Automation",
                description = "Enables deterministic gestures: tapping buttons, scrolling, and virtual typing.",
                whyNeeded = "Used exclusively for user-directed actions without bypassing system security.",
                state = if (hasAccessibility) PermissionState.GRANTED else PermissionState.REQUIRED_ACTION,
                isMandatory = false,
                settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS
            ),
            PermissionItem(
                id = "overlay",
                title = "Draw Over Other Apps",
                description = "Displays the floating Aurora orb and digital mouse pointer across apps.",
                whyNeeded = "Allows you to view AI status and control your phone while using other apps.",
                state = if (hasOverlay) PermissionState.GRANTED else PermissionState.REQUIRED_ACTION,
                isMandatory = false,
                settingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            ),
            PermissionItem(
                id = "notifications",
                title = "Foreground Notifications",
                description = "Displays the persistent status notification with pause/stop buttons while listening.",
                whyNeeded = "Android requires a notification to keep the voice listening service transparent and active.",
                state = if (hasNotifications) PermissionState.GRANTED else PermissionState.DENIED,
                isMandatory = false
            ),
            PermissionItem(
                id = "shizuku",
                title = "Shizuku ADB Integration (Optional)",
                description = com.example.shizuku.ShizukuBridge.checkStatus(context).details,
                whyNeeded = "Used strictly for validated, allowlisted elevated actions. Never executes arbitrary AI shell code.",
                state = if (com.example.shizuku.ShizukuBridge.checkStatus(context).permissionGranted) PermissionState.GRANTED else PermissionState.REQUIRED_ACTION,
                isMandatory = false
            )
        )
    }
}
