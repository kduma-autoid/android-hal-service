package dev.duma.android.hal.contract

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.RemoteCallbackList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Wrapper exposing HalPlugin (in-process) as IHardwarePlugin.Stub (AIDL Binder).
 * Used by bundle APKs (e.g. plugin-sunmi-bundle) to expose plugins as an Android Service
 * with AIDL. Serializes PluginDescriptor to JSON, uses RemoteCallbackList
 * to manage event callbacks.
 */
class PluginServiceWrapper(
    private val plugin: HalPlugin,
    private val applicationContext: Context? = null
) : IHardwarePlugin.Stub() {

    private val callbackList = RemoteCallbackList<IPluginEventCallback>()

    init {
        plugin.setEventCallback(object : HalPluginEventCallback {
            override fun onEvent(eventName: String, jsonData: String) {
                broadcastEvent(eventName, jsonData)
            }

            override fun onError(deviceType: String, code: Int, message: String) {
                broadcastError(deviceType, code, message)
            }
        })
    }

    override fun getPluginId(): String = plugin.pluginId

    override fun getVersion(): Int = plugin.version

    override fun isSupported(): Boolean = plugin.isSupported()

    override fun getCapabilities(): List<String> = plugin.getCapabilities()

    override fun execute(method: String, jsonParams: String): CommandResult {
        return runBlocking { plugin.execute(method, jsonParams) }
    }

    override fun getDescriptorJson(): String {
        return Json.encodeToString(plugin.getDescriptor())
    }

    override fun registerEventCallback(callback: IPluginEventCallback) {
        callbackList.register(callback)
    }

    override fun unregisterEventCallback(callback: IPluginEventCallback) {
        callbackList.unregister(callback)
    }

    override fun initialize(pluginContext: IPluginContext) {
        val appCtx = applicationContext ?: return
        val remoteContext = RemotePluginContext(pluginContext, appCtx)
        Handler(Looper.getMainLooper()).post {
            plugin.initialize(remoteContext)
        }
    }

    /**
     * Tears down the wrapped plugin. Call from the hosting Service's onDestroy so that
     * plugins holding resources (e.g. a BroadcastReceiver) can release them.
     */
    fun dispose() {
        plugin.dispose()
    }

    private fun broadcastEvent(eventName: String, jsonData: String) {
        val count = callbackList.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbackList.getBroadcastItem(i).onEvent(eventName, jsonData)
                } catch (_: Exception) {
                    // Remote callback died — RemoteCallbackList handles cleanup
                }
            }
        } finally {
            callbackList.finishBroadcast()
        }
    }

    private fun broadcastError(deviceType: String, code: Int, message: String) {
        val count = callbackList.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbackList.getBroadcastItem(i).onError(deviceType, code, message)
                } catch (_: Exception) {
                    // Remote callback died
                }
            }
        } finally {
            callbackList.finishBroadcast()
        }
    }
}
