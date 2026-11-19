package dev.duma.android.hal.transport.ws

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.CommandHandler
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class WsTransportConcurrencyTest {

    /** `slow` blocks until `cancel` arrives — as a card read waits for its cancel. */
    private class BlockingHandler : CommandHandler {
        val released = CompletableDeferred<Unit>()

        override suspend fun execute(token: String, method: String, params: String, callerContext: CallerContext) =
            when (method) {
                "slow" -> { released.await(); CommandResult.Success("""{"slow":"done"}""") }
                "cancel" -> { released.complete(Unit); CommandResult.Success("""{"cancelled":true}""") }
                else -> CommandResult.Success("{}")
            }

        override suspend fun requestToken(request: String, callerContext: CallerContext) = CommandResult.Success("{}")
        override suspend fun subscribe(token: String, events: String, callerContext: CallerContext) = CommandResult.Success("{}")
        override suspend fun unsubscribe(token: String, events: String, callerContext: CallerContext) = CommandResult.Success("{}")
        override fun getStatus() = "{}"
        override fun describeApi() = "{}"
    }

    @Test
    fun `a pending command does not block the next one on the same session`() = testApplication {
        val handler = BlockingHandler()
        application { with(WsTransport()) { installWs(handler) } }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(Frame.Text("""{"id":"a","type":"authenticate","token":"t"}"""))
            send(Frame.Text("""{"id":"1","type":"command","method":"slow","params":{}}"""))
            send(Frame.Text("""{"id":"2","type":"command","method":"cancel","params":{}}"""))

            val replies = withTimeout(5_000) { List(3) { (incoming.receive() as Frame.Text).readText() } }
            // Before the fix the session handled frames one by one: "slow" never finished because
            // "cancel" waited behind it, and this timed out.
            assertTrue(replies[0].contains("\"authenticated\":true"), replies.toString())
            assertTrue(replies[1].contains("\"cancelled\":true"), "cancel must answer first: $replies")
            assertTrue(replies[2].contains("\"slow\":\"done\""), replies.toString())
        }
    }
}
