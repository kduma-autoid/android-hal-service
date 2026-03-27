package dev.duma.android.hal.service.config

import android.content.Context

/**
 * Manages per-event broadcast configuration stored in SharedPreferences.
 * Controls which plugin events are forwarded via BroadcastTransport.
 * Thread-safe — SharedPreferences handles concurrent access internally.
 */
class BroadcastConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "broadcast_config"
        private const val KEY_ENABLED_EVENTS = "enabled_events"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getEnabledEvents(): Set<String> {
        return prefs.getStringSet(KEY_ENABLED_EVENTS, emptySet()) ?: emptySet()
    }

    fun setEnabledEvents(events: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED_EVENTS, events).apply()
    }

    fun enableEvent(eventName: String) {
        val current = getEnabledEvents().toMutableSet()
        current.add(eventName)
        setEnabledEvents(current)
    }

    fun disableEvent(eventName: String) {
        val current = getEnabledEvents().toMutableSet()
        current.remove(eventName)
        setEnabledEvents(current)
    }

    fun isEventEnabled(eventName: String): Boolean {
        return eventName in getEnabledEvents()
    }
}
