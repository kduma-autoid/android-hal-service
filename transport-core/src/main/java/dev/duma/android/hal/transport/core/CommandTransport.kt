package dev.duma.android.hal.transport.core

/**
 * Command channel (request -> response). Implemented by each transport that handles
 * commands: AIDL, WebSocket, HTTP REST, Intent.
 * hal-service starts transports and passes them a CommandHandler for request processing.
 */
interface CommandTransport {
    val transportId: String
    val displayName: String
    fun start(handler: CommandHandler, config: TransportConfig)
    fun stop()
    val isRunning: Boolean
}
