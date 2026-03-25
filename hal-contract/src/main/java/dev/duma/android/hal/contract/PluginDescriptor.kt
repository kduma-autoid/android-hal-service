package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * Plugin declaration — describes its methods and events. Used by Dashboard (API listing),
 * system.describe (client queries available API), broadcast configuration,
 * and AIDL serialization (getDescriptorJson).
 */
@Serializable
data class PluginDescriptor(
    val pluginId: String,
    val version: Int,
    val capabilities: List<String>,
    val methods: List<MethodDescriptor>,
    val events: List<EventDescriptor>
)
