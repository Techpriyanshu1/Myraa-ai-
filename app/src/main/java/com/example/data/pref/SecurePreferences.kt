package com.example.data.pref

import android.content.Context
import android.content.SharedPreferences
import com.example.security.KeystoreSecurityVault

class SecurePreferences(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("myraa_secure_vault_v2", Context.MODE_PRIVATE)

    private val keystoreVault = KeystoreSecurityVault(context)

    companion object {
        // Voice Provider Presets
        // Default Voice: MAHI — Indian Conversational, Warm, Friendly, Natural Pronunciation
        const val VOICE_MAHI = "21m00Tcm4TlvDq8ikWAM"      // Default Indian Conversational Preset
        const val VOICE_MAHI_NAME = "Mahi (Default Indian Conversational)"

        // Default Model
        const val MODEL_ELEVEN_MULTILINGUAL_V2 = "eleven_multilingual_v2"
        const val MODEL_ELEVEN_FLASH_V2_5 = "eleven_flash_v2_5"

        // Default Gemini Model
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
    }

    // --- GEMINI API ---
    fun getGeminiApiKey(): String {
        val encrypted = prefs.getString("gemini_api_key_vault", "") ?: ""
        val decrypted = keystoreVault.decrypt(encrypted).trim()
        if (decrypted.isNotBlank()) return decrypted

        return try {
            val buildKey = com.example.BuildConfig.GEMINI_API_KEY
            if (!buildKey.isNullOrBlank() && buildKey != "MY_GEMINI_API_KEY") {
                buildKey.trim()
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setGeminiApiKey(key: String) {
        val trimmed = key.trim()
        val encrypted = keystoreVault.encrypt(trimmed)
        prefs.edit().putString("gemini_api_key_vault", encrypted).apply()
    }

    fun getSelectedGeminiModel(): String {
        val saved = prefs.getString("selected_gemini_model", DEFAULT_GEMINI_MODEL)?.trim().orEmpty()
        if (saved.isBlank() || saved == "gemini-3.8-flash" || saved == "gemini-3.5-flash" || saved == "gemini-pro-latest") {
            return DEFAULT_GEMINI_MODEL
        }
        return saved
    }

    fun setSelectedGeminiModel(model: String) {
        prefs.edit().putString("selected_gemini_model", model.trim()).apply()
    }

    // --- ELEVENLABS VOICE API ---
    fun getVoiceApiKey(): String {
        val encrypted = prefs.getString("voice_api_key_vault", "") ?: ""
        return keystoreVault.decrypt(encrypted).trim()
    }

    fun setVoiceApiKey(key: String) {
        val trimmed = key.trim()
        val encrypted = keystoreVault.encrypt(trimmed)
        prefs.edit().putString("voice_api_key_vault", encrypted).apply()
    }

    fun getSelectedVoiceId(): String {
        val encrypted = prefs.getString("selected_voice_id_vault", "") ?: ""
        if (encrypted.isNotBlank()) {
            val decrypted = keystoreVault.decrypt(encrypted).trim()
            if (decrypted.isNotBlank()) return decrypted
        }
        val legacy = prefs.getString("selected_voice_id", "")?.trim() ?: ""
        if (legacy.isNotBlank()) {
            setSelectedVoiceId(legacy)
            return legacy
        }
        return VOICE_MAHI
    }

    fun setSelectedVoiceId(voiceId: String) {
        val trimmed = voiceId.trim()
        val effective = if (trimmed.isNotBlank()) trimmed else VOICE_MAHI
        val encrypted = keystoreVault.encrypt(effective)
        prefs.edit()
            .putString("selected_voice_id_vault", encrypted)
            .remove("selected_voice_id")
            .apply()
    }

    fun getSelectedVoiceModel(): String {
        return prefs.getString("selected_voice_model", MODEL_ELEVEN_MULTILINGUAL_V2) ?: MODEL_ELEVEN_MULTILINGUAL_V2
    }

    fun setSelectedVoiceModel(model: String) {
        prefs.edit().putString("selected_voice_model", model.trim()).apply()
    }

    fun isNativeTtsFallbackEnabled(): Boolean {
        return prefs.getBoolean("native_tts_fallback_enabled", true)
    }

    fun setNativeTtsFallbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("native_tts_fallback_enabled", enabled).apply()
    }

    fun getSpeechSpeed(): Float {
        return prefs.getFloat("speech_speed_rate", 1.0f)
    }

    fun setSpeechSpeed(speed: Float) {
        prefs.edit().putFloat("speech_speed_rate", speed.coerceIn(0.5f, 2.0f)).apply()
    }

    // --- APP & ONBOARDING STATE ---
    fun isSetupCompleted(): Boolean {
        return prefs.getBoolean("setup_completed", false)
    }

    fun setSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean("setup_completed", completed).apply()
    }

    fun isOverlayEnabled(): Boolean {
        return prefs.getBoolean("overlay_aurora_enabled", true)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("overlay_aurora_enabled", enabled).apply()
    }

    fun isContinuousListeningEnabled(): Boolean {
        return prefs.getBoolean("continuous_listening", true)
    }

    fun setContinuousListeningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("continuous_listening", enabled).apply()
    }

    fun getPreferredLanguage(): String {
        return prefs.getString("preferred_language", "auto") ?: "auto"
    }

    fun setPreferredLanguage(lang: String) {
        prefs.edit().putString("preferred_language", lang).apply()
    }

    // --- USER-SELECTED APPS FOR LAUNCHER AGENT ---
    fun getSelectedApps(): Set<String> {
        return prefs.getStringSet("user_selected_apps", emptySet())?.toSet() ?: emptySet()
    }

    fun setSelectedApps(packageNames: Set<String>) {
        prefs.edit().putStringSet("user_selected_apps", packageNames).apply()
    }

    fun toggleSelectedApp(packageName: String): Set<String> {
        val updated = getSelectedApps().toMutableSet()
        if (updated.contains(packageName)) {
            updated.remove(packageName)
        } else {
            updated.add(packageName)
        }
        setSelectedApps(updated)
        return updated
    }

    // --- PRIVACY & CREDENTIAL MANAGEMENT ---
    fun isCommandTextLoggingEnabled(): Boolean {
        return prefs.getBoolean("privacy_log_command_text", true)
    }

    fun setCommandTextLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("privacy_log_command_text", enabled).apply()
    }

    fun isOfflineModeAccepted(): Boolean {
        return prefs.getBoolean("offline_mode_accepted", false)
    }

    fun setOfflineModeAccepted(accepted: Boolean) {
        prefs.edit().putBoolean("offline_mode_accepted", accepted).apply()
    }

    fun hasStoredGeminiKey(): Boolean {
        return getGeminiApiKey().isNotBlank()
    }

    fun hasStoredVoiceKey(): Boolean {
        return prefs.getString("voice_api_key_vault", "")?.isNotBlank() == true
    }

    fun deleteGeminiCredentials() {
        prefs.edit().remove("gemini_api_key_vault").apply()
    }

    fun deleteVoiceCredentials() {
        prefs.edit()
            .remove("voice_api_key_vault")
            .remove("selected_voice_id_vault")
            .remove("selected_voice_id")
            .apply()
    }

    fun deleteAllCredentials() {
        prefs.edit()
            .remove("gemini_api_key_vault")
            .remove("voice_api_key_vault")
            .remove("selected_voice_id_vault")
            .remove("selected_voice_id")
            .apply()
    }
}
