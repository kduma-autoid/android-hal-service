package dev.duma.android.hal.transport.intent

import dev.duma.android.hal.transport.core.CommandHandler
import dev.duma.android.hal.transport.core.CommandTransport
import dev.duma.android.hal.transport.core.TransportConfig

/**
 * Intent transport for one-shot commands via Android Intents. Stores handler reference
 * in companion object for [IntentGatewayActivity] to access. Toggleable at runtime via Dashboard.
 */
class IntentTransport : CommandTransport {

    override val transportId = "intent"
    override val displayName = "Android Intent"
    override val isRunning: Boolean get() = running

    private var running = false

    override fun start(handler: CommandHandler, config: TransportConfig) {
        Companion.handler = handler
        Companion.config = config
        running = true
    }

    override fun stop() {
        running = false
        Companion.handler = null
        Companion.config = null
    }

    companion object {
        var handler: CommandHandler? = null
            internal set
        var config: TransportConfig? = null
            internal set
        var isTransportEnabled = true
    }
}
