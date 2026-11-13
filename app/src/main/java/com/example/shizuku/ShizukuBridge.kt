package com.example.shizuku

import android.content.Context
import android.content.pm.PackageManager

data class ShizukuStatus(
    val isInstalled: Boolean,
    val isRunning: Boolean,
    val permissionGranted: Boolean,
    val details: String
)

object ShizukuBridge {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    fun checkStatus(context: Context): ShizukuStatus {
        val pm = context.packageManager
        val isInstalled = try {
            pm.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }

        val hasPermission = context.checkCallingOrSelfPermission(SHIZUKU_PERMISSION) == PackageManager.PERMISSION_GRANTED

        val details = when {
            !isInstalled -> "Shizuku manager is not installed on this device. Install from GitHub/Play Store for rootless ADB automation."
            !hasPermission -> "Shizuku is installed! Grant API permission inside Shizuku app to enable elevated system automation."
            else -> "Shizuku elevated ADB bridge is connected and ready for direct shell actions."
        }

        return ShizukuStatus(
            isInstalled = isInstalled,
            isRunning = isInstalled,
            permissionGranted = hasPermission,
            details = details
        )
    }
}
