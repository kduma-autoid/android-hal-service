package dev.duma.android.hal.transport.http

import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.CommandHandler
import dev.duma.android.hal.transport.core.CommandTransport
import dev.duma.android.hal.transport.core.TransportConfig
import dev.duma.android.hal.transport.ktor.core.KtorServerManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HTTP REST transport implementing the command channel. Registers routes
 * in [KtorServerManager] — does NOT start the server. Provides endpoints for
 * token requests, command execution, health, status, and API description.
 */
class HttpTransport : CommandTransport {

    override val transportId = "http"
    override val displayName = "HTTP REST"
    override val isRunning: Boolean get() = running
    private var running = false

    override fun start(handler: CommandHandler, config: TransportConfig) {
        val manager = config.ktorServerManager as? KtorServerManager
            ?: throw IllegalStateException("HttpTransport requires KtorServerManager in TransportConfig")

        manager.addModule {
            routing {
                post("/api/token") {
                    val body = call.receiveText()
                    val callerContext = buildCallerContext(call)
                    val result = handler.requestToken(body, callerContext)
                    call.respondText(result, ContentType.Application.Json)
                }

                post("/api/execute") {
                    val token = call.request.header("Authorization")
                        ?.removePrefix("Bearer ")?.trim()
                    if (token.isNullOrBlank()) {
                        call.respondText(
                            """{"error":"unauthorized","message":"Missing Bearer token"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized
                        )
                        return@post
                    }
                    val body = call.receiveText()
                    val parsed = try {
                        Json.parseToJsonElement(body).jsonObject
                    } catch (_: Exception) {
                        call.respondText(
                            """{"error":"parse_error","message":"Invalid JSON"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@post
                    }
                    val method = parsed["method"]?.jsonPrimitive?.content ?: ""
                    val params = parsed["params"]?.toString() ?: "{}"
                    val callerContext = buildCallerContext(call)
                    val result = handler.execute(token, method, params, callerContext)
                    call.respondText(result, ContentType.Application.Json)
                }

                get("/api/health") {
                    val callerContext = buildCallerContext(call)
                    val result = handler.execute("", "system.ping", "{}", callerContext)
                    call.respondText(result, ContentType.Application.Json)
                }

                get("/api/status") {
                    val token = call.request.header("Authorization")
                        ?.removePrefix("Bearer ")?.trim()
                    if (token.isNullOrBlank()) {
                        call.respondText(
                            """{"error":"unauthorized","message":"Missing Bearer token"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized
                        )
                        return@get
                    }
                    val callerContext = buildCallerContext(call)
                    val result = handler.execute(token, "system.status", "{}", callerContext)
                    call.respondText(result, ContentType.Application.Json)
                }

                get("/api/describe") {
                    val token = call.request.header("Authorization")
                        ?.removePrefix("Bearer ")?.trim()
                    if (token.isNullOrBlank()) {
                        call.respondText(
                            """{"error":"unauthorized","message":"Missing Bearer token"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized
                        )
                        return@get
                    }
                    val callerContext = buildCallerContext(call)
                    val result = handler.execute(token, "system.describe", "{}", callerContext)
                    call.respondText(result, ContentType.Application.Json)
                }
            }
        }
        running = true
    }

    override fun stop() {
        running = false
    }

    private fun buildCallerContext(call: ApplicationCall): CallerContext {
        return CallerContext(
            transport = "http",
            origin = call.request.header("Origin"),
            remoteAddress = call.request.local.remoteAddress
        )
    }
}
