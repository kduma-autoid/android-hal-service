package dev.duma.android.hal.service.auth

import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom

class DeviceKeyManager(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_SECRET = "device_key_secret"
        private const val KEY_ENABLED = "device_key_enabled"
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getOrCreateSecret(): ByteArray {
        val existing = prefs.getString(KEY_SECRET, null)
        if (existing != null) return Base64.decode(existing, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        prefs.edit().putString(KEY_SECRET, Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)).apply()
        return key
    }

    fun getSecretBase64(): String {
        val existing = prefs.getString(KEY_SECRET, null)
        if (existing != null) return existing

        val key = getOrCreateSecret()
        return Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
