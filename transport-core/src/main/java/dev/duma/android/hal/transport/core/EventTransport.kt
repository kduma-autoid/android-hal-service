package dev.duma.android.hal.transport.core

/**
 * Event channel (push). Implemented by transports that deliver events
 * to clients: AIDL callback, WebSocket stream, Broadcast.
 * Can be toggled at runtime (isToggleable/isEnabled) via Dashboard.
 */
interface EventTransport {
    val transportId: String
    val displayName: String
    fun start(config: TransportConfig)
    fun stop()
    val isRunning: Boolean
    val isToggleable: Boolean
    var isEnabled: Boolean
    fun pushEvent(eventName: String, jsonData: String)
}
