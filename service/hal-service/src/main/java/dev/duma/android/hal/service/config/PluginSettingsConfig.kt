package dev.duma.android.hal.service.config

import android.content.Context

/**
 * Persists the values of plugins' user-facing settings ([dev.duma.android.hal.contract.PluginSetting])
 * in SharedPreferences, keyed `<pluginId>/<key>`. Only values the user changed are stored.
 */
class PluginSettingsConfig(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBoolean(pluginId: String, key: String, default: Boolean): Boolean =
        prefs.getBoolean(prefKey(pluginId, key), default)

    fun setBoolean(pluginId: String, key: String, value: Boolean) {
        prefs.edit().putBoolean(prefKey(pluginId, key), value).apply()
    }

    private fun prefKey(pluginId: String, key: String) = "$pluginId/$key"

    private companion object {
        const val PREFS_NAME = "hal_plugin_settings"
    }
}
