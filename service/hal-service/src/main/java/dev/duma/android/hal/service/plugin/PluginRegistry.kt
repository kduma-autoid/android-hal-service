package dev.duma.android.hal.service.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.duma.android.hal.contract.AidlPluginAdapter
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventBus
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.IHardwarePlugin
import dev.duma.android.hal.contract.PluginDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of all hardware plugins (built-in and external). Manages plugin lifecycle:
 * registration, external discovery via PackageManager, initialization with PluginContext,
 * and command routing. Thread-safe via ConcurrentHashMap for concurrent access from transports.
 *
 * Conflict resolution: external plugins override built-in ones; when both are the same
 * source type, the higher [HalPlugin.version] wins. Displaced built-in plugins are
 * restored automatically when an external plugin disconnects.
 */
class PluginRegistry {

    companion object {
        private const val TAG = "PluginRegistry"
        private const val ACTION_HARDWARE_PLUGIN = "dev.duma.android.hal.HARDWARE_PLUGIN"
    }

    enum class PluginSource { BUILT_IN, EXTERNAL }

    data class PluginInfo(
        val source: PluginSource,
        val packageName: String? = null
    )

    private val plugins = ConcurrentHashMap<String, HalPlugin>()
    private val unsupportedPlugins = ConcurrentHashMap<String, HalPlugin>()
    private val capabilityToPlugin = ConcurrentHashMap<String, HalPlugin>()
    private val serviceConnections = mutableListOf<ServiceConnection>()

    private val pluginInfo = ConcurrentHashMap<String, PluginInfo>()
    private val displacedPlugins = ConcurrentHashMap<String, Pair<HalPlugin, PluginInfo>>()
    private var pendingInit: Pair<Context, EventBus>? = null

    fun getPluginInfo(pluginId: String): PluginInfo? = pluginInfo[pluginId]

    fun registerBuiltIn(plugin: HalPlugin) {
        if (plugin.isSupported()) {
            if (tryRegister(plugin, PluginInfo(PluginSource.BUILT_IN))) {
                Log.i(TAG, "Registered built-in plugin: ${plugin.pluginId} v${plugin.version}")
            }
        } else {
            unsupportedPlugins[plugin.pluginId] = plugin
            pluginInfo[plugin.pluginId] = PluginInfo(PluginSource.BUILT_IN)
            Log.i(TAG, "Plugin not supported on this device: ${plugin.pluginId}")
        }
    }

    private fun tryRegister(plugin: HalPlugin, info: PluginInfo): Boolean {
        val id = plugin.pluginId
        val existing = plugins[id]

        if (existing != null) {
            val existingInfo = pluginInfo[id]!!
            val shouldReplace = when {
                info.source == PluginSource.EXTERNAL && existingInfo.source == PluginSource.BUILT_IN -> true
                info.source == PluginSource.BUILT_IN && existingInfo.source == PluginSource.EXTERNAL -> false
                plugin.version > existing.version -> true
                plugin.version < existing.version -> false
                else -> false
            }

            if (!shouldReplace) {
                Log.i(TAG, "Plugin $id v${plugin.version} (${info.source}) skipped — existing v${existing.version} (${existingInfo.source}) has priority")
                return false
            }

            if (existingInfo.source == PluginSource.BUILT_IN) {
                displacedPlugins[id] = existing to existingInfo
            }
            existing.getCapabilities().forEach { capabilityToPlugin.remove(it, existing) }
            Log.i(TAG, "Plugin $id: replacing v${existing.version} (${existingInfo.source}) with v${plugin.version} (${info.source})")
        }

        plugins[id] = plugin
        pluginInfo[id] = info
        plugin.getCapabilities().forEach { capabilityToPlugin[it] = plugin }
        return true
    }

    fun discoverExternal(context: Context) {
        val intent = Intent(ACTION_HARDWARE_PLUGIN)
        val resolveInfos = context.packageManager.queryIntentServices(
            intent, android.content.pm.PackageManager.GET_META_DATA
        )
        Log.i(TAG, "External plugin discovery: found ${resolveInfos.size} services")

        for (info in resolveInfos) {
            val componentName = ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
            val pluginId = info.serviceInfo.metaData?.getString("plugin.id") ?: continue

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val binder = IHardwarePlugin.Stub.asInterface(service)
                    val adapter = AidlPluginAdapter(binder)
                    if (adapter.isSupported()) {
                        val extInfo = PluginInfo(PluginSource.EXTERNAL, name.packageName)
                        if (tryRegister(adapter, extInfo)) {
                            Log.i(TAG, "Connected external plugin: $pluginId v${adapter.version} from ${name.packageName}")
                            pendingInit?.let { (appContext, eventBus) ->
                                initializePlugin(adapter, eventBus, appContext)
                            }
                        }
                    } else {
                        unsupportedPlugins[pluginId] = adapter
                        pluginInfo[pluginId] = PluginInfo(PluginSource.EXTERNAL, name.packageName)
                        Log.i(TAG, "External plugin not supported on this device: $pluginId from ${name.packageName}")
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    Log.w(TAG, "Disconnected external plugin: $pluginId")
                    val removed = plugins.remove(pluginId)
                    pluginInfo.remove(pluginId)
                    if (removed != null) {
                        removed.getCapabilities().forEach { capabilityToPlugin.remove(it, removed) }
                    }

                    val fallback = displacedPlugins.remove(pluginId)
                    if (fallback != null) {
                        val (builtInPlugin, builtInInfo) = fallback
                        plugins[pluginId] = builtInPlugin
                        pluginInfo[pluginId] = builtInInfo
                        builtInPlugin.getCapabilities().forEach { capabilityToPlugin[it] = builtInPlugin }
                        Log.i(TAG, "Restored built-in plugin: $pluginId v${builtInPlugin.version}")
                    }
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
        pendingInit = appContext to eventBus
        for ((_, plugin) in plugins) {
            initializePlugin(plugin, eventBus, appContext)
        }
        Log.i(TAG, "Initialized ${plugins.size} plugins")
    }

    private fun initializePlugin(plugin: HalPlugin, eventBus: EventBus, appContext: Context) {
        val context = PluginContextImpl(
            ownerPluginId = plugin.pluginId,
            registry = this,
            eventBus = eventBus,
            applicationContext = appContext
        )
        plugin.setEventCallback(object : HalPluginEventCallback {
            override fun onEvent(eventName: String, jsonData: String) {
                eventBus.emit(eventName, jsonData, plugin.pluginId)
            }
            override fun onError(deviceType: String, code: Int, message: String) {
                Log.w(TAG, "Plugin ${plugin.pluginId} error [$deviceType]: $code $message")
            }
        })
        plugin.initialize(context)
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

    suspend fun executeOnPlugin(method: String, params: String): CommandResult {
        val plugin = findForMethod(method)
            ?: return CommandResult.notFound("No plugin handles method: $method")
        return plugin.execute(method, params)
    }

    fun getMethodDescriptor(method: String): dev.duma.android.hal.contract.MethodDescriptor? {
        val plugin = findForMethod(method) ?: return null
        return plugin.getDescriptor().methods.find { it.name == method }
    }

    fun allCapabilities(): List<String> {
        return capabilityToPlugin.keys().toList()
    }

    fun getSupportedDescriptors(): List<PluginDescriptor> {
        return plugins.values.map { it.getDescriptor() }
    }

    fun getAllDescriptors(): List<PluginDescriptor> {
        return (plugins.values + unsupportedPlugins.values).map { it.getDescriptor() }
    }

    fun getUnsupportedPluginIds(): Set<String> {
        return unsupportedPlugins.keys.toSet()
    }

    fun getExperimentalPluginIds(): Set<String> {
        return (plugins.values + unsupportedPlugins.values)
            .filter { it.getDescriptor().experimental }
            .map { it.pluginId }
            .toSet()
    }

    fun disconnectAll(context: Context) {
        serviceConnections.forEach { connection ->
            try {
                context.unbindService(connection)
            } catch (_: Exception) { }
        }
        serviceConnections.clear()
        plugins.clear()
        pluginInfo.clear()
        displacedPlugins.clear()
        capabilityToPlugin.clear()
        pendingInit = null
    }
}
