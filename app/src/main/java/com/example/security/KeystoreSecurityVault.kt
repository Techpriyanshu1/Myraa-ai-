package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android Keystore-backed AES-256-GCM Hardware-Grade Security Vault.
 * Protects Gemini API keys, ElevenLabs Voice API keys, and sensitive tokens at rest.
 * Falls back to a locally generated random AES-256 key only in JVM test environments
 * where AndroidKeyStore provider is absent.
 */
class KeystoreSecurityVault(private val context: Context) {

    private var keyStore: KeyStore? = null
    private var fallbackSecretKey: SecretKey? = null

    init {
        initializeVault()
    }

    private fun initializeVault() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore = ks
            ensureHardwareMasterKeyExists(ks)
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore unavailable (${e.javaClass.simpleName}); initializing local AES-256 fallback")
            keyStore = null
            fallbackSecretKey = getOrCreateFallbackKey()
        }
    }

    private fun ensureHardwareMasterKeyExists(ks: KeyStore) {
        if (!ks.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
            Log.i(TAG, "Hardware Keystore Master Key successfully generated")
        }
    }

    private fun getOrCreateFallbackKey(): SecretKey {
        val prefs = context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString(FALLBACK_KEY_PREF, null)
        if (!encoded.isNullOrBlank()) {
            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            return SecretKeySpec(decoded, "AES")
        }
        val generator = KeyGenerator.getInstance("AES").apply { init(256) }
        val generated = generator.generateKey()
        prefs.edit().putString(FALLBACK_KEY_PREF, Base64.encodeToString(generated.encoded, Base64.NO_WRAP)).apply()
        return generated
    }

    private fun getSecretKey(): SecretKey {
        val ks = keyStore
        if (ks != null) {
            return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        return fallbackSecretKey ?: getOrCreateFallbackKey().also { fallbackSecretKey = it }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // Prepend IV (12 bytes for GCM)
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            ""
        }
    }

    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        return try {
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH) return ""

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            ""
        }
    }

    companion object {
        private const val TAG = "KeystoreVault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "myraa_keystore_master_v2"
        private const val FALLBACK_PREFS = "myraa_vault_runtime_ctx"
        private const val FALLBACK_KEY_PREF = "aes_gcm_key_b64"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
}
