package dev.duma.android.hal.service.plugin

import android.content.Context
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventBus
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.PluginContext

/**
 * Per-initialize implementation of [PluginContext]. Each plugin gets a context carrying its
 * instance and initialize generation; [ownerPluginId] is still used for EventBus loop protection.
 * Enables inter-plugin communication (execute, events) without auth — internal calls are trusted.
 */
class PluginContextImpl(
    private val ownerPluginId: String,
    private val registry: PluginRegistry,
    private val eventBus: EventBus,
    override val applicationContext: Context,
    private val ownerPlugin: HalPlugin? = null,
    private val initializeGeneration: Long? = null
) : PluginContext {

    private fun isCurrent(): Boolean = registry.isContextCurrent(
        ownerPluginId,
        ownerPlugin,
        initializeGeneration
    )

    override suspend fun execute(method: String, params: String): CommandResult {
        return registry.executeOnPlugin(method, params)
    }

    override fun getAvailableCapabilities(): List<String> {
        return registry.allCapabilities()
    }

    override fun hasCapability(capability: String): Boolean {
        return capability in registry.allCapabilities()
    }

    override fun emitEvent(eventName: String, jsonData: String) {
        if (!isCurrent()) return
        eventBus.emit(eventName, jsonData, sourcePluginId = ownerPluginId)
    }

    override fun onEvent(pattern: String, callback: (eventName: String, jsonData: String) -> Unit) {
        if (!isCurrent()) return
        val plugin = ownerPlugin
        val generation = initializeGeneration
        eventBus.addPluginListener(
            listenerPluginId = ownerPluginId,
            pattern = pattern,
            // EventBus intentionally has no unregister operation. Gate the callback at delivery
            // time so a listener retained by an old initialize generation cannot receive events for
            // the replacement instance.
            callback = { eventName, jsonData ->
                if (registry.isContextCurrent(ownerPluginId, plugin, generation)) {
                    callback(eventName, jsonData)
                }
            }
        )
    }

    override fun setPluginAvailable(available: Boolean) {
        registry.setPluginAvailability(
            ownerPluginId,
            available,
            ownerPlugin,
            initializeGeneration
        )
    }

    override suspend fun executeInterface(
        interfaceId: String,
        providerPluginId: String?,
        method: String,
        params: String
    ): CommandResult {
        return registry.executeInterface(interfaceId, providerPluginId, method, params)
    }

    override fun getInterfaceProviders(interfaceId: String): List<String> {
        return registry.getInterfaceProviders(interfaceId).map { it.pluginId }
    }
}
