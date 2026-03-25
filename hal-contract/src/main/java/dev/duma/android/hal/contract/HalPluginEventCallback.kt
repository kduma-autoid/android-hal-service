package dev.duma.android.hal.contract

/**
 * Callback for receiving events from a plugin. Set on the plugin by hal-service
 * via [HalPlugin.setEventCallback], allowing the plugin to notify about hardware
 * events (e.g. barcode scanned, printer error).
 */
interface HalPluginEventCallback {
    fun onEvent(eventName: String, jsonData: String)
    fun onError(deviceType: String, code: Int, message: String)
}
