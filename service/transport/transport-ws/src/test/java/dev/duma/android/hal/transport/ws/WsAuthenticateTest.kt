package dev.duma.android.hal.transport.ws

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.CommandHandler
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class WsAuthenticateTest {

    /** Accepts only the token "good"; commands echo the token they ran with. */
    private class TokenCheckingHandler : CommandHandler {
        override suspend fun validateToken(token: String, callerContext: CallerContext) =
            if (token == "good") CommandResult.Success("""{"authenticated":true,"expires_at":1234}""")
            else CommandResult.unauthorized("Invalid token")

        override suspend fun execute(token: String, method: String, params: String, callerContext: CallerContext) =
            CommandResult.Success("""{"token":"$token"}""")

        override suspend fun requestToken(request: String, callerContext: CallerContext) = CommandResult.Success("{}")
        override suspend fun subscribe(token: String, events: String, callerContext: CallerContext) = CommandResult.Success("{}")
        override suspend fun unsubscribe(token: String, events: String, callerContext: CallerContext) = CommandResult.Success("{}")
        override fun getStatus() = "{}"
        override fun describeApi() = "{}"
    }

    @Test
    fun `authenticate validates the token and reports its expiry`() = testApplication {
        application { with(WsTransport()) { installWs(TokenCheckingHandler()) } }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(Frame.Text("""{"id":"a","type":"authenticate","token":"good"}"""))
            val ok = withTimeout(5_000) { (incoming.receive() as Frame.Text).readText() }
            assertTrue(ok.contains("\"authenticated\":true") && ok.contains("\"expires_at\":1234"), ok)
        }
    }

    @Test
    fun `a rejected token is unauthorized and replaces the session's previous token`() = testApplication {
        application { with(WsTransport()) { installWs(TokenCheckingHandler()) } }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(Frame.Text("""{"id":"a","type":"authenticate","token":"good"}"""))
            send(Frame.Text("""{"id":"b","type":"authenticate","token":"revoked"}"""))
            send(Frame.Text("""{"id":"c","type":"command","method":"system.ping","params":{}}"""))

            val replies = withTimeout(5_000) { List(3) { (incoming.receive() as Frame.Text).readText() } }
            assertTrue(replies[1].contains("\"unauthorized\""), replies.toString())
            // The old, valid token must not keep being used after a failed re-authenticate.
            assertTrue(replies[2].contains("Not authenticated"), replies.toString())
        }
    }
}
