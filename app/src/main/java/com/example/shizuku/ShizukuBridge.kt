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
    private val SHIZUKU_PACKAGES = listOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager"
    )
    private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    fun checkStatus(context: Context): ShizukuStatus {
        val pm = context.packageManager
        val isInstalled = SHIZUKU_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }

        val hasPermission = context.checkCallingOrSelfPermission(SHIZUKU_PERMISSION) == PackageManager.PERMISSION_GRANTED

        val details = when {
            !isInstalled -> "Optional add-on: Shizuku is not installed. Standard voice, Gemini, app & settings automation work normally without it."
            !hasPermission -> "Shizuku is installed! Grant API permission inside Shizuku app for optional elevated system automation."
            else -> "Shizuku elevated ADB bridge is connected and ready."
        }

        return ShizukuStatus(
            isInstalled = isInstalled,
            isRunning = isInstalled,
            permissionGranted = hasPermission,
            details = details
        )
    }
}
