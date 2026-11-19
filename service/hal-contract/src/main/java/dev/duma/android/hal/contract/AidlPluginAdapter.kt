package dev.duma.android.hal.contract

import android.util.Log
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

    internal companion object {
        private const val TAG = "AidlPluginAdapter"

        /** Decodes the bundle's keyboard device list; missing or malformed input means none. */
        fun decodeKeyboardDevices(json: String?): List<KeyboardDeviceId> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                Json.decodeFromString<List<KeyboardDeviceId>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Malformed keyboard device list: ${e.message}")
                emptyList()
            }
        }
    }

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

    /**
     * A bundle built before this transaction existed does not handle it: depending on the AIDL
     * generator the call returns no reply (read as `null`) or throws. Either way it simply has no
     * keyboard devices, and must not fail registration.
     */
    override fun getKeyboardFilterDevices(): List<KeyboardDeviceId> {
        val json = try {
            binder.keyboardFilterDevicesJson
        } catch (e: Exception) {
            Log.w(TAG, "getKeyboardFilterDevicesJson() unavailable: ${e.message}")
            return emptyList()
        }
        return decodeKeyboardDevices(json)
    }

    override fun initialize(pluginContext: PluginContext) {
        val pluginContextBinder = object : IPluginContext.Stub() {
            override fun execute(method: String, jsonParams: String): CommandResult {
                return runBlocking(Dispatchers.IO) {
                    pluginContext.execute(method, jsonParams)
                }
            }

            override fun getAvailableCapabilities(): List<String> {
                return pluginContext.getAvailableCapabilities()
            }

            override fun hasCapability(capability: String): Boolean {
                return pluginContext.hasCapability(capability)
            }

            override fun setPluginAvailable(available: Boolean) {
                pluginContext.setPluginAvailable(available)
            }

            override fun getSetting(key: String): String? {
                // Both lookups miss only when the user never changed the setting.
                val a = pluginContext.getBooleanSetting(key, default = true)
                val b = pluginContext.getBooleanSetting(key, default = false)
                return if (a == b) a.toString() else null
            }
        }
        binder.initialize(pluginContextBinder)
    }

    /** Settings of the remote plugin; empty for bundles built before settings existed. */
    override fun getSettings(): List<PluginSetting> = try {
        binder.settingsJson?.takeIf { it.isNotBlank() }?.let { Json.decodeFromString<List<PluginSetting>>(it) }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    override fun onSettingChanged(key: String, value: Boolean) {
        try {
            binder.onSettingChanged(key, value)
        } catch (_: Exception) {
            // Remote plugin gone or too old to know settings.
        }
    }

    override suspend fun execute(method: String, params: String): CommandResult {
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
