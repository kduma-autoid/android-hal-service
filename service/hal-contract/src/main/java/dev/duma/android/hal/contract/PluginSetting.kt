package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * A user-facing on/off setting a plugin declares via [HalPlugin.getSettings]. hal-service shows it
 * on the plugin's detail screen and persists it; the plugin reads the stored value with
 * [PluginContext.getBooleanSetting] and is told about changes through [HalPlugin.onSettingChanged].
 */
@Serializable
data class PluginSetting(
    /** Stable key, unique within the plugin. */
    val key: String,
    val title: String,
    val description: String = "",
    val default: Boolean
)
