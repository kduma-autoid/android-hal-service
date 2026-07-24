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
    val groups: List<DescriptorGroup>
) {
    companion object {
        fun withFlatLists(
            pluginId: String,
            name: String,
            version: Int,
            experimental: Boolean = false,
            capabilities: List<String>,
            methods: List<MethodDescriptor> = emptyList(),
            events: List<EventDescriptor> = emptyList()
        ) = PluginDescriptor(
            pluginId = pluginId,
            name = name,
            version = version,
            experimental = experimental,
            capabilities = capabilities,
            groups = listOf(DescriptorGroup(methods = methods, events = events))
        )
    }
}

val PluginDescriptor.allMethods: List<MethodDescriptor>
    get() = groups.flatMap { it.methods }

val PluginDescriptor.allEvents: List<EventDescriptor>
    get() = groups.flatMap { it.events }

/**
 * Returns a copy of this descriptor with all experimental content removed.
 *
 * - If the whole plugin is experimental, the result exposes nothing (empty groups, flag cleared) —
 *   the plugin has no stable surface.
 * - Otherwise, experimental methods and events are dropped, and groups left empty by that filter
 *   are removed.
 *
 * Used by plugins built in the `stable` stability flavor so experimental methods are absent from the
 * descriptor (and, together with the descriptor guard, non-invocable) in production builds.
 */
fun PluginDescriptor.stripExperimental(): PluginDescriptor =
    if (experimental) {
        copy(experimental = false, groups = emptyList())
    } else {
        copy(
            groups = groups.map { group ->
                group.copy(
                    methods = group.methods.filterNot { it.experimental },
                    events = group.events.filterNot { it.experimental }
                )
            }.filter { it.methods.isNotEmpty() || it.events.isNotEmpty() }
        )
    }
