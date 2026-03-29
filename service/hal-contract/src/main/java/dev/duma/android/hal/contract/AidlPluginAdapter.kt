package dev.duma.android.hal.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Adapter wrapping IHardwarePlugin (Binder, out-of-process) into the HalPlugin interface (in-process).
 * Used by hal-service to communicate with plugins running in separate processes
 * (e.g. plugin-sunmi-bundle APK). Moves execute() to Dispatchers.IO, parses PluginDescriptor from JSON.
 */
class AidlPluginAdapter(
    private val binder: IHardwarePlugin
) : HalPlugin {

    override val pluginId: String
        get() = binder.pluginId

    override val version: Int
        get() = binder.version

    override fun isSupported(): Boolean = binder.isSupported

    override fun getCapabilities(): List<String> = binder.capabilities

    override fun getDescriptor(): PluginDescriptor {
        val json = binder.descriptorJson
        return Json.decodeFromString(json)
    }

    override fun initialize(context: PluginContext) {
        val pluginContextBinder = object : IPluginContext.Stub() {
            override fun execute(method: String, jsonParams: String): String {
                return runBlocking(Dispatchers.IO) {
                    context.execute(method, jsonParams)
                }
            }

            override fun getAvailableCapabilities(): List<String> {
                return context.getAvailableCapabilities()
            }

            override fun hasCapability(capability: String): Boolean {
                return context.hasCapability(capability)
            }
        }
        binder.initialize(pluginContextBinder)
    }

    override suspend fun execute(method: String, params: String): String {
        return withContext(Dispatchers.IO) {
            binder.execute(method, params)
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        if (callback != null) {
            binder.registerEventCallback(object : IPluginEventCallback.Stub() {
                override fun onEvent(eventName: String, jsonData: String) {
                    callback.onEvent(eventName, jsonData)
                }

                override fun onError(deviceType: String, code: Int, message: String) {
                    callback.onError(deviceType, code, message)
                }
            })
        } else {
            // Cannot unregister a specific callback without tracking it;
            // callers should manage the lifecycle externally
        }
    }
}
