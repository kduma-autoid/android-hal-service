package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * Plugin declaration — describes its methods and events organized into groups.
 * Used by Dashboard (API listing), system.describe (client queries available API),
 * broadcast configuration, and AIDL serialization (getDescriptorJson).
 */
@Serializable
data class PluginDescriptor(
    val pluginId: String,
    val name: String,
    val version: Int,
    val experimental: Boolean = false,
    val capabilities: List<String>,
    val groups: List<DescriptorGroup>,
    /** Interfaces this plugin registers (defines). Usually a dedicated definer plugin. */
    val definesInterfaces: List<InterfaceContract> = emptyList(),
    /** Interfaces this plugin provides (implements). Each is an explicit provider opt-in. */
    val interfaces: List<InterfaceBinding> = emptyList()
) {
    companion object {
        fun withFlatLists(
            pluginId: String,
            name: String,
            version: Int,
            experimental: Boolean = false,
            capabilities: List<String>,
            methods: List<MethodDescriptor> = emptyList(),
            events: List<EventDescriptor> = emptyList(),
            definesInterfaces: List<InterfaceContract> = emptyList(),
            interfaces: List<InterfaceBinding> = emptyList()
        ) = PluginDescriptor(
            pluginId = pluginId,
            name = name,
            version = version,
            experimental = experimental,
            capabilities = capabilities,
            groups = listOf(DescriptorGroup(methods = methods, events = events)),
            definesInterfaces = definesInterfaces,
            interfaces = interfaces
        )
    }
}

val PluginDescriptor.allMethods: List<MethodDescriptor>
    get() = groups.flatMap { it.methods }

val PluginDescriptor.allEvents: List<EventDescriptor>
    get() = groups.flatMap { it.events }
