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

    /**
     * Executes a method of a registered [InterfaceContract], routing to a specific provider when
     * [providerPluginId] is given, otherwise to the interface's default provider. Fails if the
     * interface is not registered or has no available provider.
     *
     * Default falls back to [execute] (ignoring provider selection) for contexts that do not
     * support interface routing.
     */
    suspend fun executeInterface(
        interfaceId: String,
        providerPluginId: String?,
        method: String,
        params: String
    ): CommandResult = execute(method, params)

    /** Plugin ids currently providing [interfaceId], preferred first. Empty when unsupported. */
    fun getInterfaceProviders(interfaceId: String): List<String> = emptyList()
}
