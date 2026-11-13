package com.example.core

/**
 * MYRAA AI — Product Identity & Tagline
 * Tagline: "Your Voice. Your Intelligence. Your Control."
 */
object MyraaConstants {
    const val APP_NAME = "MYRAA AI"
    const val TAGLINE = "Your Voice. Your Intelligence. Your Control."
    const val APP_VERSION = "2.0-Era"

    // Supported Natural Languages
    const val LANG_AUTO = "auto"
    const val LANG_HINDI = "hi-IN"
    const val LANG_ENGLISH = "en-US"

    // Foreground service notification channel
    const val CHANNEL_ID_VOICE = "myraa_voice_listening_channel"
    const val CHANNEL_NAME_VOICE = "Myraa AI Continuous Voice Assistant"
    const val NOTIFICATION_ID_VOICE = 1001

    // Action types
    const val ACTION_CHAT = "CHAT"
    const val ACTION_OPEN_APP = "OPEN_APP"
    const val ACTION_FLASHLIGHT = "FLASHLIGHT"
    const val ACTION_VOLUME = "VOLUME"
    const val ACTION_ACCESSIBILITY = "ACCESSIBILITY"
    const val ACTION_SCREEN_CLICK = "SCREEN_CLICK"
    const val ACTION_SCREEN_TYPE = "SCREEN_TYPE"
    const val ACTION_SETTINGS = "SETTINGS"
    const val ACTION_WEB_SEARCH = "WEB_SEARCH"
}

sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Resource<Nothing>()
    object Loading : Resource<Nothing>()
    object Idle : Resource<Nothing>()
}
