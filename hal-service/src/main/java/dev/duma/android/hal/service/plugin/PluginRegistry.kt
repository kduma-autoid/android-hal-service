package dev.duma.android.hal.service.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.duma.android.hal.contract.AidlPluginAdapter
import dev.duma.android.hal.contract.EventBus
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.IHardwarePlugin
import dev.duma.android.hal.contract.PluginDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of all hardware plugins (built-in and external). Manages plugin lifecycle:
 * registration, external discovery via PackageManager, initialization with PluginContext,
 * and command routing. Thread-safe via ConcurrentHashMap for concurrent access from transports.
 */
class PluginRegistry {

    companion object {
        private const val TAG = "PluginRegistry"
        private const val ACTION_HARDWARE_PLUGIN = "dev.duma.hal.HARDWARE_PLUGIN"
    }

    private val plugins = ConcurrentHashMap<String, HalPlugin>()
    private val capabilityToPlugin = ConcurrentHashMap<String, HalPlugin>()
    private val serviceConnections = mutableListOf<ServiceConnection>()

    fun registerBuiltIn(plugin: HalPlugin) {
        plugins[plugin.pluginId] = plugin
        plugin.getCapabilities().forEach { capability ->
            capabilityToPlugin[capability] = plugin
        }
        Log.i(TAG, "Registered built-in plugin: ${plugin.pluginId}")
    }

    fun discoverExternal(context: Context) {
        val intent = Intent(ACTION_HARDWARE_PLUGIN)
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)

        for (info in resolveInfos) {
            val componentName = ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
            val pluginId = info.serviceInfo.metaData?.getString("plugin.id") ?: continue

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val binder = IHardwarePlugin.Stub.asInterface(service)
                    val adapter = AidlPluginAdapter(binder)
                    plugins[pluginId] = adapter
                    adapter.getCapabilities().forEach { capability ->
                        capabilityToPlugin[capability] = adapter
                    }
                    Log.i(TAG, "Connected external plugin: $pluginId from ${name.packageName}")
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    plugins.remove(pluginId)
                    Log.w(TAG, "Disconnected external plugin: $pluginId")
                }
            }

            serviceConnections.add(connection)
            context.bindService(
                Intent(ACTION_HARDWARE_PLUGIN).setComponent(componentName),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun initializeAll(appContext: Context, eventBus: EventBus) {
        for ((_, plugin) in plugins) {
            val context = PluginContextImpl(
                ownerPluginId = plugin.pluginId,
                registry = this,
                eventBus = eventBus,
                applicationContext = appContext
            )
            plugin.initialize(context)
        }
        Log.i(TAG, "Initialized ${plugins.size} plugins")
    }

    fun findForMethod(method: String): HalPlugin? {
        // Method format: "capability.operation" e.g. "sunmi.printer.print"
        // Try progressively shorter prefixes to find the capability
        val parts = method.split(".")
        for (i in parts.size - 1 downTo 1) {
            val capability = parts.subList(0, i).joinToString(".")
            val plugin = capabilityToPlugin[capability]
            if (plugin != null) return plugin
        }
        return null
    }

    suspend fun executeOnPlugin(method: String, params: String): String {
        val plugin = findForMethod(method)
            ?: return """{"error":"no_handler","message":"No plugin handles method: $method"}"""
        return plugin.execute(method, params)
    }

    fun allCapabilities(): List<String> {
        return capabilityToPlugin.keys().toList()
    }

    fun getAllDescriptors(): List<PluginDescriptor> {
        return plugins.values.map { it.getDescriptor() }
    }

    fun disconnectAll(context: Context) {
        serviceConnections.forEach { connection ->
            try {
                context.unbindService(connection)
            } catch (_: Exception) { }
        }
        serviceConnections.clear()
        plugins.clear()
        capabilityToPlugin.clear()
    }
}
