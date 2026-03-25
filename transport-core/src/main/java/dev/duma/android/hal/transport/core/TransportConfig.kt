package dev.duma.android.hal.transport.core

import android.content.Context

/**
 * Configuration passed to transports at startup. Contains the Ktor server port,
 * Android application context, and the set of events enabled for broadcast.
 */
data class TransportConfig(
    val port: Int = 8400,
    val context: Context,
    val enabledBroadcastEvents: Set<String> = emptySet(),
    val ktorServerManager: Any? = null
)
