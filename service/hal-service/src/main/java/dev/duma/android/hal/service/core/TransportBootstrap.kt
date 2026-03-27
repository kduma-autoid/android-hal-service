package dev.duma.android.hal.service.core

import android.util.Log
import dev.duma.android.hal.transport.core.CommandTransport
import dev.duma.android.hal.transport.core.EventTransport
import dev.duma.android.hal.transport.core.TransportRegistry

/**
 * Discovers and registers compiled-in transports via reflection. Each transport module
 * is optional — if not included in dependencies, its class won't be found and it's skipped.
 * This enables compile-time transport selection without build flavor changes.
 */
class TransportBootstrap {

    companion object {
        private const val TAG = "TransportBootstrap"

        private val KNOWN_TRANSPORTS = listOf(
            "dev.duma.android.hal.transport.aidl.AidlTransport",
            "dev.duma.android.hal.transport.ws.WsTransport",
            "dev.duma.android.hal.transport.http.HttpTransport",
            "dev.duma.android.hal.transport.intent.IntentTransport",
            "dev.duma.android.hal.transport.broadcast.BroadcastTransport"
        )
    }

    fun registerTransports(registry: TransportRegistry) {
        for (className in KNOWN_TRANSPORTS) {
            try {
                val clazz = Class.forName(className)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is CommandTransport) {
                    registry.registerCommand(instance)
                    Log.i(TAG, "Registered command transport: ${instance.transportId}")
                }
                if (instance is EventTransport) {
                    registry.registerEvent(instance)
                    Log.i(TAG, "Registered event transport: ${instance.transportId}")
                }
            } catch (_: ClassNotFoundException) {
                Log.d(TAG, "Transport not available: $className")
            }
        }
    }
}
