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
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.allMethods
import dev.duma.android.hal.service.config.ExperimentalConfig
import dev.duma.android.hal.service.config.InterfacePreferenceConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

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
        const val EVENT_PLUGINS_CHANGED = "system.plugins.changed"
        const val EVENT_INTERFACES_CHANGED = "system.interfaces.changed"
    }

    enum class PluginSource { BUILT_IN, EXTERNAL }

    data class PluginInfo(
        val source: PluginSource,
        val packageName: String? = null
    )

    /** A provider of an interface, as surfaced to callers/clients (see [getInterfaceProviders]). */
    data class ProviderRef(
        val pluginId: String,
        val source: PluginSource?,
        val version: Int,
        val priority: Int,
        val features: List<String>,
        val isDefault: Boolean,
        val available: Boolean,
        val supported: Boolean = true,
        val enabled: Boolean = true,
        /** The provider *plugin* is experimental — gated by settings or the caller's token. */
        val experimental: Boolean = false
    )

    private val plugins = ConcurrentHashMap<String, HalPlugin>()
    private val unsupportedPlugins = ConcurrentHashMap<String, HalPlugin>()
    private val capabilityToPlugin = ConcurrentHashMap<String, HalPlugin>()
    private val serviceConnections = mutableListOf<ServiceConnection>()
    // Dynamic availability: a registered plugin whose value is false is loaded but its
    // capabilities are not routable/advertised (e.g. hardware currently absent).
    private val available = ConcurrentHashMap<String, Boolean>()

    // Interface layer (see InterfaceContract / InterfaceBinding). All keyed by interfaceId / pluginId.
    private val registeredInterfaces = ConcurrentHashMap<String, InterfaceContract>()
    private val interfaceProviders = ConcurrentHashMap<String, CopyOnWriteArraySet<String>>()
    private val interfaceBindings = ConcurrentHashMap<String, List<InterfaceBinding>>()
    private val interfaceDefinitionsByPlugin = ConcurrentHashMap<String, List<String>>()

    /** User ordering / enable-disable preferences per interface. Null until wired by the service. */
    var interfacePreferenceConfig: InterfacePreferenceConfig? = null

    /** User's experimental opt-ins; consulted when an experimental plugin provides an interface. */
    var experimentalConfig: ExperimentalConfig? = null

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
                // Release the displaced built-in's resources; it is re-initialized if restored.
                safeDispose(existing)
            }
            existing.getCapabilities().forEach { capabilityToPlugin.remove(it, existing) }
            unindexInterfaces(existing.pluginId)
            Log.i(TAG, "Plugin $id: replacing v${existing.version} (${existingInfo.source}) with v${plugin.version} (${info.source})")
        }

        plugins[id] = plugin
        pluginInfo[id] = info
        available[id] = true
        plugin.getCapabilities().forEach { capabilityToPlugin[it] = plugin }
        indexInterfaces(plugin)
        return true
    }

    /** Indexes a plugin's defined interfaces (definer) and provided interfaces (bindings). */
    private fun indexInterfaces(plugin: HalPlugin) {
        val descriptor = plugin.getDescriptor()
        if (descriptor.definesInterfaces.isNotEmpty()) {
            descriptor.definesInterfaces.forEach { registeredInterfaces[it.interfaceId] = it }
            interfaceDefinitionsByPlugin[plugin.pluginId] = descriptor.definesInterfaces.map { it.interfaceId }
        }
        if (descriptor.interfaces.isNotEmpty()) {
            interfaceBindings[plugin.pluginId] = descriptor.interfaces
            descriptor.interfaces.forEach { binding ->
                interfaceProviders.getOrPut(binding.interfaceId) { CopyOnWriteArraySet() }.add(plugin.pluginId)
            }
        }
    }

    /** Removes a plugin's interface registrations/bindings. Uses stored state (no getDescriptor call). */
    private fun unindexInterfaces(pluginId: String) {
        interfaceDefinitionsByPlugin.remove(pluginId)?.forEach { registeredInterfaces.remove(it) }
        interfaceBindings.remove(pluginId)?.forEach { binding ->
            interfaceProviders[binding.interfaceId]?.remove(pluginId)
        }
    }

    /**
     * Toggles a registered plugin's dynamic availability. When unavailable, its capabilities are
     * removed from routing and excluded from [getSupportedDescriptors] (so system.status/describe
     * hide it); when available again they are restored. Emits [EVENT_PLUGINS_CHANGED] on change.
     */
    fun setPluginAvailability(pluginId: String, isAvailable: Boolean) {
        val plugin = plugins[pluginId] ?: return
        val previous = available[pluginId] ?: true
        if (previous == isAvailable) return
        available[pluginId] = isAvailable
        if (isAvailable) {
            plugin.getCapabilities().forEach { capabilityToPlugin[it] = plugin }
        } else {
            plugin.getCapabilities().forEach { capabilityToPlugin.remove(it, plugin) }
        }
        Log.i(TAG, "Plugin $pluginId availability -> $isAvailable")
        pendingInit?.second?.emit(
            EVENT_PLUGINS_CHANGED,
            """{"pluginId":"$pluginId","available":$isAvailable}""",
            sourcePluginId = "system"
        )
    }

    private fun isAvailable(pluginId: String): Boolean = available[pluginId] ?: true

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
                    available.remove(pluginId)
                    if (removed != null) {
                        removed.getCapabilities().forEach { capabilityToPlugin.remove(it, removed) }
                        unindexInterfaces(pluginId)
                        safeDispose(removed)
                    }

                    val fallback = displacedPlugins.remove(pluginId)
                    if (fallback != null) {
                        val (builtInPlugin, builtInInfo) = fallback
                        plugins[pluginId] = builtInPlugin
                        pluginInfo[pluginId] = builtInInfo
                        available[pluginId] = true
                        builtInPlugin.getCapabilities().forEach { capabilityToPlugin[it] = builtInPlugin }
                        indexInterfaces(builtInPlugin)
                        // Re-initialize so the restored built-in re-acquires resources released on displacement.
                        pendingInit?.let { (appContext, eventBus) ->
                            initializePlugin(builtInPlugin, eventBus, appContext)
                        }
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
        // The descriptor is the single source of truth for what is invocable. Methods absent from a
        // plugin's (possibly stability-filtered) descriptor — e.g. experimental methods stripped in a
        // `stable` plugin build — are not callable, even by name.
        if (plugin.getDescriptor().allMethods.none { it.name == method }) {
            return CommandResult.unsupportedMethod(method)
        }
        return plugin.execute(method, params)
    }

    fun getMethodDescriptor(method: String): MethodDescriptor? {
        // Interface methods are owned by the registered contract, not by any provider descriptor.
        for (contract in registeredInterfaces.values) {
            contract.methods.find { it.name == method }?.let { return it }
        }
        val plugin = findForMethod(method) ?: return null
        return plugin.getDescriptor().allMethods.find { it.name == method }
    }

    // ---- Interface layer ---------------------------------------------------------------------

    /** All currently registered interface contracts. */
    fun getRegisteredInterfaces(): List<InterfaceContract> = registeredInterfaces.values.toList()

    /** The plugin that registered [interfaceId] — the settings key gating an experimental interface. */
    fun definerForInterface(interfaceId: String): String? =
        interfaceDefinitionsByPlugin.entries.firstOrNull { interfaceId in it.value }?.key

    /** Whether [pluginId]'s own descriptor marks it experimental. */
    private fun isPluginExperimental(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        return try { plugin.getDescriptor().experimental } catch (_: Exception) { false }
    }

    /**
     * Whether an experimental provider is usable by this caller: either the user enabled the plugin
     * in settings, or the caller holds experimental access. A provider failing this gate is not part
     * of the interface for that caller — not the default, not routable, not listed.
     */
    private fun passesExperimentalGate(pluginId: String, callerHasExperimental: Boolean): Boolean =
        !isPluginExperimental(pluginId) ||
            callerHasExperimental ||
            experimentalConfig?.isPluginEnabled(pluginId) == true

    /** The registered contract for [interfaceId], or null if no plugin defines it. */
    fun getInterfaceContract(interfaceId: String): InterfaceContract? = registeredInterfaces[interfaceId]

    /**
     * The registered interface a method belongs to, or null. A method is an interface method only
     * when its interface is registered — so an unregistered interface's methods are never routed
     * here even if a provider is present.
     */
    fun interfaceIdForMethod(method: String): String? {
        for ((id, contract) in registeredInterfaces) {
            if (contract.methods.any { it.name == method }) return id
        }
        return null
    }

    /**
     * Providers of [interfaceId], available ones only, preferred first (priority desc, then external
     * over built-in, then version desc). The first is marked [ProviderRef.isDefault].
     *
     * Experimental provider plugins are omitted unless the user enabled them in settings or
     * [callerHasExperimental] is set, so an experimental backend never becomes the silent default.
     * The default is conservative: callers serving a token pass the caller's access explicitly.
     */
    fun getInterfaceProviders(interfaceId: String, callerHasExperimental: Boolean = false): List<ProviderRef> {
        val ids = interfaceProviders[interfaceId] ?: return emptyList()
        val config = interfacePreferenceConfig
        val order = config?.getOrder(interfaceId) ?: emptyList()
        val refs = ids.mapNotNull { id ->
            if (!plugins.containsKey(id) || !isAvailable(id)) return@mapNotNull null
            if (config?.isEnabled(interfaceId, id) == false) return@mapNotNull null
            if (!passesExperimentalGate(id, callerHasExperimental)) return@mapNotNull null
            val binding = interfaceBindings[id]?.firstOrNull { it.interfaceId == interfaceId } ?: return@mapNotNull null
            val plugin = plugins[id] ?: return@mapNotNull null
            ProviderRef(
                pluginId = id,
                source = pluginInfo[id]?.source,
                version = plugin.version,
                priority = binding.priority,
                features = binding.features,
                isDefault = false,
                available = true,
                experimental = isPluginExperimental(id)
            )
        }.sortedWith(providerComparator(order))
        return refs.mapIndexed { index, ref -> ref.copy(isDefault = index == 0) }
    }

    /**
     * ALL implementors of [interfaceId] for the Dashboard — including dynamically unavailable ones
     * and unsupported ones (which are not in the interface index, so they are scanned from
     * [unsupportedPlugins] descriptors). Sorted in effective order; [ProviderRef.isDefault] marks the
     * one routing would pick (first available + enabled). Carries `available`/`supported`/`enabled` flags.
     */
    fun getAllInterfaceImplementors(interfaceId: String): List<ProviderRef> {
        val config = interfacePreferenceConfig
        val order = config?.getOrder(interfaceId) ?: emptyList()
        val result = LinkedHashMap<String, ProviderRef>()
        interfaceProviders[interfaceId]?.forEach { id ->
            val plugin = plugins[id] ?: return@forEach
            val binding = interfaceBindings[id]?.firstOrNull { it.interfaceId == interfaceId } ?: return@forEach
            result[id] = ProviderRef(
                pluginId = id,
                source = pluginInfo[id]?.source,
                version = plugin.version,
                priority = binding.priority,
                features = binding.features,
                isDefault = false,
                available = isAvailable(id),
                supported = true,
                enabled = config?.isEnabled(interfaceId, id) != false,
                experimental = isPluginExperimental(id)
            )
        }
        // Unsupported plugins are never indexed — scan their descriptors for a binding.
        unsupportedPlugins.values.forEach { plugin ->
            if (result.containsKey(plugin.pluginId)) return@forEach
            val binding = try {
                plugin.getDescriptor().interfaces.firstOrNull { it.interfaceId == interfaceId }
            } catch (_: Exception) {
                null
            } ?: return@forEach
            result[plugin.pluginId] = ProviderRef(
                pluginId = plugin.pluginId,
                source = pluginInfo[plugin.pluginId]?.source,
                version = plugin.version,
                priority = binding.priority,
                features = binding.features,
                isDefault = false,
                available = false,
                supported = false,
                enabled = config?.isEnabled(interfaceId, plugin.pluginId) != false,
                experimental = try { plugin.getDescriptor().experimental } catch (_: Exception) { false }
            )
        }
        val sorted = result.values.sortedWith(providerComparator(order))
        val defaultId = sorted.firstOrNull { it.available && it.enabled }?.pluginId
        return sorted.map { it.copy(isDefault = it.pluginId == defaultId) }
    }

    /** User order first (by index), then priority desc, external over built-in, version desc. */
    private fun providerComparator(order: List<String>): Comparator<ProviderRef> {
        fun rank(id: String): Int = order.indexOf(id).let { if (it >= 0) it else Int.MAX_VALUE }
        return compareBy<ProviderRef> { rank(it.pluginId) }
            .thenByDescending { it.priority }
            .thenByDescending { it.source == PluginSource.EXTERNAL }
            .thenByDescending { it.version }
    }

    /** Sets the user provider order for an interface and notifies clients. */
    fun setInterfaceOrder(interfaceId: String, order: List<String>) {
        interfacePreferenceConfig?.setOrder(interfaceId, order)
        emitInterfacesChanged(interfaceId)
    }

    /** Enables/disables a provider for an interface and notifies clients. */
    fun setInterfaceEnabled(interfaceId: String, pluginId: String, enabled: Boolean) {
        interfacePreferenceConfig?.setEnabled(interfaceId, pluginId, enabled)
        emitInterfacesChanged(interfaceId)
    }

    private fun emitInterfacesChanged(interfaceId: String) {
        pendingInit?.second?.emit(
            EVENT_INTERFACES_CHANGED,
            """{"interfaceId":"$interfaceId"}""",
            sourcePluginId = "system"
        )
    }

    /**
     * Executes an interface method. When [providerPluginId] is null the default provider is used.
     * Fails if the interface is not registered, the method is not part of the contract, or no
     * (matching, available) provider exists.
     */
    suspend fun executeInterface(
        interfaceId: String,
        providerPluginId: String?,
        method: String,
        params: String,
        callerHasExperimental: Boolean = false
    ): CommandResult {
        val contract = registeredInterfaces[interfaceId]
            ?: return CommandResult.notFound("Interface not registered: $interfaceId")
        if (contract.methods.none { it.name == method }) {
            return CommandResult.unsupportedMethod(method)
        }
        val plugin = if (providerPluginId != null) {
            val bound = interfaceBindings[providerPluginId]?.any { it.interfaceId == interfaceId } == true
            val enabled = interfacePreferenceConfig?.isEnabled(interfaceId, providerPluginId) != false
            // An experimental provider the user has not enabled is not part of the interface, so
            // naming it explicitly is as unavailable as naming a plugin that never bound to it.
            val experimentalOk = passesExperimentalGate(providerPluginId, callerHasExperimental)
            val p = plugins[providerPluginId]
            if (p == null || !isAvailable(providerPluginId) || !bound || !enabled || !experimentalOk) {
                return CommandResult.unavailable("Provider '$providerPluginId' does not provide interface '$interfaceId'")
            }
            p
        } else {
            val defaultId = getInterfaceProviders(interfaceId, callerHasExperimental).firstOrNull()?.pluginId
                ?: return CommandResult.unavailable("No provider available for interface: $interfaceId")
            plugins[defaultId] ?: return CommandResult.unavailable("No provider available for interface: $interfaceId")
        }
        // Method-level feature gate: if the method is gated by an interface feature (feature.methods),
        // the resolved provider must advertise it. Parameter-level features (features with no `methods`,
        // e.g. a "timeout" option) are NOT enforced here — the core forwards params opaquely, so the
        // provider validates its own parameters.
        val requiredFeature = contract.features.firstOrNull { method in it.methods }?.key
        if (requiredFeature != null) {
            val providerFeatures = interfaceBindings[plugin.pluginId]
                ?.firstOrNull { it.interfaceId == interfaceId }?.features ?: emptyList()
            if (requiredFeature !in providerFeatures) {
                return CommandResult.unavailable(
                    "Provider '${plugin.pluginId}' does not support feature '$requiredFeature' required by '$method'"
                )
            }
        }
        // Report which provider actually handled the call (resolved default, or the pinned one),
        // exposed in the response header so clients don't have to rely on the plugin echoing it.
        val result = plugin.execute(method, params)
        return if (result is CommandResult.Success) result.copy(provider = plugin.pluginId) else result
    }

    fun allCapabilities(): List<String> {
        return capabilityToPlugin.keys().toList()
    }

    fun getSupportedDescriptors(): List<PluginDescriptor> {
        return plugins.filterKeys { isAvailable(it) }.values.map { it.getDescriptor() }
    }

    fun getAllDescriptors(): List<PluginDescriptor> {
        return (plugins.values + unsupportedPlugins.values).map { it.getDescriptor() }
    }

    fun getUnsupportedPluginIds(): Set<String> {
        return unsupportedPlugins.keys.toSet()
    }

    /** Registered plugins that are currently unavailable (loaded, but hardware/service absent right now). */
    fun getUnavailablePluginIds(): Set<String> {
        return plugins.keys.filter { available[it] == false }.toSet()
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
        // Tear down initialized plugins (active + displaced) so they release resources.
        plugins.values.forEach { safeDispose(it) }
        displacedPlugins.values.forEach { (plugin, _) -> safeDispose(plugin) }
        plugins.clear()
        pluginInfo.clear()
        displacedPlugins.clear()
        capabilityToPlugin.clear()
        available.clear()
        registeredInterfaces.clear()
        interfaceProviders.clear()
        interfaceBindings.clear()
        interfaceDefinitionsByPlugin.clear()
        pendingInit = null
    }

    private fun safeDispose(plugin: HalPlugin) {
        try {
            plugin.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "dispose() failed for ${plugin.pluginId}: ${e.message}")
        }
    }
}
