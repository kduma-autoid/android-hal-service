package dev.duma.android.hal.transport.ws

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.CommandHandler
import dev.duma.android.hal.transport.core.CommandTransport
import dev.duma.android.hal.transport.core.EventTransport
import dev.duma.android.hal.transport.core.TransportConfig
import dev.duma.android.hal.transport.ktor.core.KtorServerManager
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * WebSocket transport implementing both command and event channels.
 * Registers WebSocket routing in [KtorServerManager] — does NOT start the server.
 * Manages per-session state (token, subscriptions) with thread-safe collections.
 */
class WsTransport : CommandTransport, EventTransport {

    override val transportId = "ws"
    override val displayName = "WebSocket"
    override val isToggleable = false
    override var isEnabled = true

    private var running = false
    override val isRunning: Boolean get() = running

    private data class WsSession(
        val sessionId: String,
        var token: String? = null,
        var permissions: List<String> = emptyList(),
        val subscribedEvents: CopyOnWriteArraySet<String> = CopyOnWriteArraySet(),
        val wsSession: WebSocketServerSession,
        val origin: String? = null,
        val remoteAddress: String? = null
    )

    private val sessions = ConcurrentHashMap<String, WsSession>()

    override fun start(handler: CommandHandler, config: TransportConfig) {
        val manager = config.ktorServerManager as? KtorServerManager
            ?: throw IllegalStateException("WsTransport requires KtorServerManager in TransportConfig")

        manager.addModule {
            install(WebSockets) {
                pingPeriodMillis = 15_000
                timeoutMillis = 30_000
            }
            routing {
                webSocket("/ws") {
                    handleSession(handler, this)
                }
            }
        }
        running = true
    }

    override fun start(config: TransportConfig) {
        // Event transport start — already handled by command start
    }

    override fun stop() {
        sessions.clear()
        running = false
    }

    override fun pushEvent(eventName: String, jsonData: String) {
        val eventJson = WsProtocol.serializeEvent(eventName, jsonData)
        for ((_, session) in sessions) {
            if (session.token != null &&
                WsProtocol.matchesAnySubscription(session.subscribedEvents, eventName)
            ) {
                try {
                    session.wsSession.outgoing.trySend(Frame.Text(eventJson))
                } catch (_: Exception) { }
            }
        }
    }

    private suspend fun handleSession(handler: CommandHandler, ws: WebSocketServerSession) {
        val sessionId = UUID.randomUUID().toString()
        val origin = ws.call.request.header("Origin")
        val remoteAddr = ws.call.request.local.remoteAddress
        val session = WsSession(
            sessionId = sessionId,
            wsSession = ws,
            origin = origin,
            remoteAddress = remoteAddr
        )
        sessions[sessionId] = session

        try {
            ws.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val response = handleMessage(handler, session, text)
                    if (response != null) {
                        ws.send(Frame.Text(response))
                    }
                }
            }
        } finally {
            sessions.remove(sessionId)
        }
    }

    private suspend fun handleMessage(handler: CommandHandler, session: WsSession, text: String): String? {
        val msg = WsProtocol.parse(text)

        val callerContext = CallerContext(
            transport = "ws",
            origin = session.origin,
            remoteAddress = session.remoteAddress
        )

        return when (msg) {
            is WsMessage.RequestToken -> {
                val request = buildString {
                    append("""{"clientId":"${msg.clientId}"""")
                    if (msg.serviceKey != null) {
                        append(""","serviceKey":"${msg.serviceKey}"""")
                    }
                    append("}")
                }
                val result = handler.requestToken(request, callerContext)
                serializeCommandResult(msg.id, result)
            }

            is WsMessage.Authenticate -> {
                session.token = msg.token
                WsProtocol.serializeResponse(msg.id, """{"authenticated":true}""")
            }

            is WsMessage.Command -> {
                val token = session.token
                    ?: return WsProtocol.serializeError(msg.id, "unauthorized", "Not authenticated")
                val result = handler.execute(token, msg.method, msg.params, callerContext)
                serializeCommandResult(msg.id, result)
            }

            is WsMessage.Subscribe -> {
                val token = session.token
                    ?: return WsProtocol.serializeError(msg.id, "unauthorized", "Not authenticated")
                session.subscribedEvents.addAll(msg.events)
                val result = handler.subscribe(token, msg.events.joinToString(","), callerContext)
                serializeCommandResult(msg.id, result)
            }

            is WsMessage.Unsubscribe -> {
                val token = session.token
                    ?: return WsProtocol.serializeError(msg.id, "unauthorized", "Not authenticated")
                session.subscribedEvents.removeAll(msg.events.toSet())
                val result = handler.unsubscribe(token, msg.events.joinToString(","), callerContext)
                serializeCommandResult(msg.id, result)
            }

            is WsMessage.ParseError -> {
                WsProtocol.serializeError(null, "parse_error", "Invalid message format")
            }
        }
    }

    private fun serializeCommandResult(id: String, result: CommandResult): String = when (result) {
        is CommandResult.Success -> WsProtocol.serializeResponse(id, result.body ?: "{}")
        is CommandResult.Failure -> WsProtocol.serializeError(id, result.code, result.message)
    }
}
