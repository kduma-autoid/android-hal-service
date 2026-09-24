package dev.duma.android.hal.transport.core

/**
 * Registry of all transports in hal-service. Manages transport lifecycle (start/stop)
 * and broadcasts events to all enabled EventTransports.
 * hal-service registers transports discovered via reflection, then starts them all.
 */
class TransportRegistry {
    private val commandTransports = mutableListOf<CommandTransport>()
    private val eventTransports = mutableListOf<EventTransport>()

    fun registerCommand(transport: CommandTransport) {
        commandTransports.add(transport)
    }

    fun registerEvent(transport: EventTransport) {
        eventTransports.add(transport)
    }

    fun startAll(handler: CommandHandler, config: TransportConfig) {
        commandTransports.forEach { it.start(handler, config) }
        eventTransports.forEach { it.start(config) }
    }

    fun stopAll() {
        commandTransports.forEach { it.stop() }
        eventTransports.forEach { it.stop() }
    }

    fun getCommandTransports(): List<CommandTransport> = commandTransports.toList()

    fun getEventTransports(): List<EventTransport> = eventTransports.toList()

    fun pushEvent(eventName: String, jsonData: String, source: String) {
        eventTransports
            .filter { it.isEnabled }
            .forEach { it.pushEvent(eventName, jsonData, source) }
    }
}
