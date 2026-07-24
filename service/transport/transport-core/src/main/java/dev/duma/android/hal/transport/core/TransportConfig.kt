package dev.duma.android.hal.transport.core

import android.content.Context

/**
 * Configuration passed to transports at startup. Contains the Ktor server port,
 * Android application context, and broadcast event filtering.
 * [broadcastEventFilter] is a dynamic filter checked per-event; if null, falls back
 * to the static [enabledBroadcastEvents] set.
 */
data class TransportConfig(
    val port: Int = 8400,
    val host: String = "0.0.0.0",
    val context: Context,
    val enabledBroadcastEvents: Set<String> = emptySet(),
    val broadcastEventFilter: ((String) -> Boolean)? = null,
    val ktorServerManager: Any? = null
)
