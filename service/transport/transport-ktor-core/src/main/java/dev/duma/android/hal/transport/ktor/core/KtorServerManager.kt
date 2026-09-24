package dev.duma.android.hal.transport.ktor.core

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*

/**
 * Response header naming the interface provider that handled a call. Shared so the transport that
 * writes it and the CORS config that exposes it cannot drift apart — they did, and the browser
 * silently read `undefined` for every HTTP call.
 */
const val HAL_PROVIDER_HEADER = "X-Hal-Provider"

/**
 * Manages a single shared Ktor embedded server for all Ktor-based transports.
 * transport-ws and transport-http register their routing modules via [addModule],
 * then hal-service calls [start] after all modules are registered.
 * This avoids multiple Ktor servers on different ports.
 */
class KtorServerManager {
    private var server: ApplicationEngine? = null
    private val modules = mutableListOf<Application.() -> Unit>()
    private var started = false

    val isRunning: Boolean get() = started
    val hasModules: Boolean get() = modules.isNotEmpty()

    /**
     * Register a Ktor application module (routing, WebSocket, etc.).
     * Must be called before [start].
     */
    fun addModule(module: Application.() -> Unit) {
        check(!started) { "Cannot add modules after server started" }
        modules.add(module)
    }

    /**
     * Start the Ktor server with all registered modules.
     * Called by hal-service after all transports have registered their modules.
     *
     * @param host bind address. Defaults to all interfaces; hal-service passes localhost for
     *   production builds and the configured address for development builds.
     */
    fun start(port: Int, host: String = "0.0.0.0") {
        check(!started) { "Server already started" }
        server = embeddedServer(Netty, host = host, port = port) {
            install(ContentNegotiation) { json() }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                // Fetch hides every non-safelisted response header cross-origin, so without this the
                // browser reads `undefined` and an interface call over HTTP never reports which
                // provider handled it — the WS path does. That is the demo's exact setup.
                exposeHeader(HAL_PROVIDER_HEADER)
            }
            modules.forEach { it(this) }
        }.start(wait = false)
        started = true
    }

    /**
     * Stop the Ktor server gracefully.
     */
    fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
        server = null
        started = false
    }
}
