package dev.duma.android.hal.service.config

import android.content.Context

/**
 * Manages per-plugin experimental method configuration stored in SharedPreferences.
 * Controls which plugins have experimental methods enabled locally.
 * Thread-safe — SharedPreferences handles concurrent access internally.
 */
class ExperimentalConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "hal_experimental"
        private const val KEY_ENABLED_PLUGINS = "enabled_plugins"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getEnabledPlugins(): Set<String> {
        return prefs.getStringSet(KEY_ENABLED_PLUGINS, emptySet()) ?: emptySet()
    }

    fun setEnabledPlugins(plugins: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED_PLUGINS, plugins).apply()
    }

    fun enablePlugin(pluginId: String) {
        val current = getEnabledPlugins().toMutableSet()
        current.add(pluginId)
        setEnabledPlugins(current)
    }

    fun disablePlugin(pluginId: String) {
        val current = getEnabledPlugins().toMutableSet()
        current.remove(pluginId)
        setEnabledPlugins(current)
    }

    fun isPluginEnabled(pluginId: String): Boolean {
        return pluginId in getEnabledPlugins()
    }
}
