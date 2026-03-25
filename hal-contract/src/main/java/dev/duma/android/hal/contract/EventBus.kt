package dev.duma.android.hal.contract

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central event hub in hal-service. Plugins emit events via PluginContext.emitEvent(),
 * and EventBus delivers them to: (1) plugin listeners (with loop protection — a plugin
 * does not receive its own events), (2) WS/AIDL clients via SharedFlow -> TransportRegistry.
 * Supports pattern matching: exact, wildcard prefix ("rfid.*"), global ("*").
 */
class EventBus {
    data class EventEnvelope(
        val eventName: String,
        val jsonData: String,
        val sourcePluginId: String
    )

    data class PluginListener(
        val listenerPluginId: String,
        val pattern: String,
        val callback: (eventName: String, jsonData: String) -> Unit
    )

    private val _events = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = 64)
    val events: SharedFlow<EventEnvelope> = _events.asSharedFlow()

    private val listeners = mutableListOf<PluginListener>()

    fun emit(eventName: String, jsonData: String, sourcePluginId: String) {
        val envelope = EventEnvelope(eventName, jsonData, sourcePluginId)

        listeners.forEach { listener ->
            if (listener.listenerPluginId != sourcePluginId &&
                matchesPattern(listener.pattern, eventName)
            ) {
                listener.callback(eventName, jsonData)
            }
        }

        _events.tryEmit(envelope)
    }

    fun addPluginListener(
        listenerPluginId: String,
        pattern: String,
        callback: (eventName: String, jsonData: String) -> Unit
    ) {
        listeners.add(PluginListener(listenerPluginId, pattern, callback))
    }

    companion object {
        fun matchesPattern(pattern: String, eventName: String): Boolean {
            return when {
                pattern == "*" -> true
                pattern.endsWith(".*") -> {
                    val prefix = pattern.dropLast(1) // keep the dot: "rfid."
                    eventName.startsWith(prefix)
                }
                else -> pattern == eventName
            }
        }
    }
}
