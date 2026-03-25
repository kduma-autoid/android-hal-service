package dev.duma.android.hal.transport.ws

/**
 * Typed representation of WebSocket messages from the client.
 * Parsed from JSON by [WsProtocol.parse].
 */
sealed class WsMessage {
    abstract val id: String?

    data class RequestToken(
        override val id: String,
        val clientId: String,
        val developerKey: String?
    ) : WsMessage()

    data class Authenticate(
        override val id: String,
        val token: String
    ) : WsMessage()

    data class Command(
        override val id: String,
        val method: String,
        val params: String
    ) : WsMessage()

    data class Subscribe(
        override val id: String,
        val events: List<String>
    ) : WsMessage()

    data class Unsubscribe(
        override val id: String,
        val events: List<String>
    ) : WsMessage()

    data class ParseError(
        val rawMessage: String
    ) : WsMessage() {
        override val id: String? = null
    }
}
