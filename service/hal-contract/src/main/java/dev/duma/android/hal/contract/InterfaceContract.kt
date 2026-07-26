package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * Authoritative definition of a HAL interface — a named, registered contract (e.g. "light")
 * that multiple plugins may provide. Registered by a definer plugin via
 * [PluginDescriptor.definesInterfaces]; it is the single source of truth for the interface's
 * method/event signatures and permissions.
 *
 * Interface methods are callable only while the interface is registered — a plugin declaring
 * an [InterfaceBinding] for an unregistered interface does not make its methods reachable.
 *
 * The contract owns the `superRequired`/`experimental` gates of its methods: a provider implements
 * the method but cannot loosen or tighten them, because the core resolves an interface method's
 * descriptor from the contract and never from the provider's own descriptor.
 *
 * @property methods canonical methods of the interface (e.g. "light.on"); a provider implements
 *   these directly in its own execute(), so they are separate from (and may differ in behaviour
 *   from) the provider's native methods.
 * @property features optional capabilities providers may or may not support (see [InterfaceFeature]).
 * @property experimental marks the whole interface as experimental — every method and event of it
 *   requires experimental access, the same way [PluginDescriptor.experimental] gates a whole plugin.
 *   Access comes from the caller's token or from the user enabling the *defining* plugin in settings.
 * @property defaultProviderPolicy how the default provider is chosen when a call omits an explicit
 *   provider. Currently only "priority" (highest binding priority, then external over built-in,
 *   then version).
 */
@Serializable
data class InterfaceContract(
    val interfaceId: String,
    val version: Int = 1,
    val methods: List<MethodDescriptor>,
    val events: List<EventDescriptor> = emptyList(),
    val features: List<InterfaceFeature> = emptyList(),
    val experimental: Boolean = false,
    val defaultProviderPolicy: String = "priority"
)

/**
 * An optional capability of an interface that a provider may or may not support (the server-side
 * equivalent of the client's per-backend capability flags). A provider advertises the features it
 * supports via [InterfaceBinding.features].
 *
 * @property key stable identifier, e.g. "multiFlash" or "timeout".
 * @property methods interface methods gated by this feature (e.g. ["light.multiFlash"]); empty when
 *   the feature gates a parameter rather than a whole method (e.g. the "timeout" option), in which
 *   case the provider enforces it.
 */
@Serializable
data class InterfaceFeature(
    val key: String,
    val description: String,
    val methods: List<String> = emptyList()
)
