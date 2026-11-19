package dev.duma.android.hal.transport.core

import dev.duma.android.hal.contract.CommandResult

/**
 * Command handling interface implemented by hal-service (ServiceCommandHandler).
 * Transports delegate client requests to it: token authorization,
 * command execution on plugins, and event subscription management.
 */
interface CommandHandler {
    suspend fun requestToken(request: String, callerContext: CallerContext): CommandResult
    suspend fun execute(token: String, method: String, params: String, callerContext: CallerContext): CommandResult
    suspend fun subscribe(token: String, events: String, callerContext: CallerContext): CommandResult
    suspend fun unsubscribe(token: String, events: String, callerContext: CallerContext): CommandResult

    /**
     * Checks [token] for this caller (exists, not expired, binding matches). On success the body is the
     * reply to a WebSocket `authenticate`: `{"authenticated":true,"expires_at":<epoch ms | null>}`;
     * otherwise `unauthorized`. The default accepts any token without checking it — the behaviour
     * before tokens were validated at authenticate time.
     */
    suspend fun validateToken(token: String, callerContext: CallerContext): CommandResult =
        CommandResult.Success("""{"authenticated":true}""")
    fun getStatus(): String
    fun describeApi(): String
}
