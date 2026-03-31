package dev.duma.android.hal.contract

import android.content.Context

/**
 * Context passed to in-process plugins during initialization.
 * Enables inter-plugin communication: executing commands on other plugins,
 * emitting events, and listening for events from other plugins.
 * Out-of-process (AIDL) plugins do not receive PluginContext.
 */
interface PluginContext {
    suspend fun execute(method: String, params: String): CommandResult
    fun getAvailableCapabilities(): List<String>
    fun hasCapability(capability: String): Boolean
    fun emitEvent(eventName: String, jsonData: String)
    fun onEvent(pattern: String, callback: (eventName: String, jsonData: String) -> Unit)
    val applicationContext: Context
}
