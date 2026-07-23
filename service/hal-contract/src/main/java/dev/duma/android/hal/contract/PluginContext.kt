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

    /**
     * Dynamically declares whether the owning plugin's capabilities are currently active
     * (routable and advertised in system.status / system.describe). Plugins that depend on
     * hot-pluggable or runtime-detected hardware call this from [HalPlugin.initialize] and from
     * hardware callbacks. Capabilities default to available once registered.
     *
     * In-process (built-in) plugins only; for out-of-process (AIDL) plugins this is a no-op.
     */
    fun setPluginAvailable(available: Boolean) {}
}
