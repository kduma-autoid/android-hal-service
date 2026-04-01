package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * A named group of methods and events within a plugin descriptor.
 * Groups allow organizing related methods and events together for display and documentation.
 * A null [name] indicates the default/ungrouped section.
 */
@Serializable
data class DescriptorGroup(
    val name: String? = null,
    val methods: List<MethodDescriptor> = emptyList(),
    val events: List<EventDescriptor> = emptyList()
)
