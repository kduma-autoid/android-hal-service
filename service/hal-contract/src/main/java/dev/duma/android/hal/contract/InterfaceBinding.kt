package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * A provider plugin's explicit opt-in declaration that it implements a given interface.
 * Declared in [PluginDescriptor.interfaces]. The provider implements the interface's canonical
 * methods (from the registered [InterfaceContract]) directly in its own execute(); it does not
 * redeclare their descriptors here.
 *
 * @property priority higher wins when several providers implement the same interface and a caller
 *   does not name one explicitly.
 * @property features the optional [InterfaceFeature] keys this provider supports (e.g. ["timeout"]);
 *   surfaced to clients so they know which optional methods/params work on this provider.
 */
@Serializable
data class InterfaceBinding(
    val interfaceId: String,
    val priority: Int = 0,
    val features: List<String> = emptyList()
)
