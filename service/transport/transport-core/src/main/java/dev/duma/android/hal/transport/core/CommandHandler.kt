package dev.duma.android.hal.transport.core

/**
 * Command handling interface implemented by hal-service (ServiceCommandHandler).
 * Transports delegate client requests to it: token authorization,
 * command execution on plugins, and event subscription management.
 */
interface CommandHandler {
    suspend fun requestToken(request: String, callerContext: CallerContext): String
    suspend fun execute(token: String, method: String, params: String, callerContext: CallerContext): String
    suspend fun subscribe(token: String, events: String, callerContext: CallerContext): String
    suspend fun unsubscribe(token: String, events: String, callerContext: CallerContext): String
    fun getStatus(): String
    fun describeApi(): String
}
