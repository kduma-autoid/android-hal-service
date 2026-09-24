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
    /**
     * Pushes an event to subscribed clients. [source] is the plugin id that emitted the event and is
     * delivered in the transport-level header (WS frame `source`, AIDL `onEvent` param, Broadcast
     * extra) — NOT merged into [jsonData] — so consumers can identify the provider without the
     * payload having to carry it.
     */
    fun pushEvent(eventName: String, jsonData: String, source: String)
}
