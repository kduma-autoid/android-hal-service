package dev.duma.android.hal.service.config

import android.content.Context

/**
 * Per-interface user preferences stored in SharedPreferences: the preferred provider ORDER and the
 * set of DISABLED providers, keyed by interfaceId. Consumed by [dev.duma.android.hal.service.plugin.PluginRegistry]
 * when resolving interface providers (the top of the effective order is the default provider).
 *
 * Order must preserve sequence, so it is stored as a newline-joined string (a StringSet would lose
 * order); the disabled set is an unordered StringSet. Thread-safe — SharedPreferences handles
 * concurrent access internally.
 */
class InterfacePreferenceConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "hal_interface_prefs"
        private const val KEY_ORDER_PREFIX = "order."
        private const val KEY_DISABLED_PREFIX = "disabled."
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** User-defined provider order (pluginIds) for [interfaceId]; empty when the user has not set one. */
    fun getOrder(interfaceId: String): List<String> {
        val raw = prefs.getString(KEY_ORDER_PREFIX + interfaceId, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotEmpty() }
    }

    fun setOrder(interfaceId: String, order: List<String>) {
        prefs.edit().putString(KEY_ORDER_PREFIX + interfaceId, order.joinToString("\n")).apply()
    }

    /** Provider pluginIds the user has disabled for [interfaceId]. */
    fun getDisabled(interfaceId: String): Set<String> {
        return prefs.getStringSet(KEY_DISABLED_PREFIX + interfaceId, emptySet()) ?: emptySet()
    }

    fun isEnabled(interfaceId: String, pluginId: String): Boolean {
        return pluginId !in getDisabled(interfaceId)
    }

    fun setEnabled(interfaceId: String, pluginId: String, enabled: Boolean) {
        val current = getDisabled(interfaceId).toMutableSet()
        if (enabled) current.remove(pluginId) else current.add(pluginId)
        prefs.edit().putStringSet(KEY_DISABLED_PREFIX + interfaceId, current).apply()
    }
}
