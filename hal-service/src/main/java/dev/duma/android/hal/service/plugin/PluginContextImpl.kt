package dev.duma.android.hal.service.plugin

import android.content.Context
import dev.duma.android.hal.contract.EventBus
import dev.duma.android.hal.contract.PluginContext

/**
 * Per-plugin implementation of [PluginContext]. Each plugin gets its own instance
 * with [ownerPluginId] for loop protection in EventBus. Enables inter-plugin
 * communication (execute, events) without auth — internal calls are trusted.
 */
class PluginContextImpl(
    private val ownerPluginId: String,
    private val registry: PluginRegistry,
    private val eventBus: EventBus,
    override val applicationContext: Context
) : PluginContext {

    override suspend fun execute(method: String, params: String): String {
        return registry.executeOnPlugin(method, params)
    }

    override fun getAvailableCapabilities(): List<String> {
        return registry.allCapabilities()
    }

    override fun hasCapability(capability: String): Boolean {
        return capability in registry.allCapabilities()
    }

    override fun emitEvent(eventName: String, jsonData: String) {
        eventBus.emit(eventName, jsonData, sourcePluginId = ownerPluginId)
    }

    override fun onEvent(pattern: String, callback: (eventName: String, jsonData: String) -> Unit) {
        eventBus.addPluginListener(
            listenerPluginId = ownerPluginId,
            pattern = pattern,
            callback = callback
        )
    }
}
