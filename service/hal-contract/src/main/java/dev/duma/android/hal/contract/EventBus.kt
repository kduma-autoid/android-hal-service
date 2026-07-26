package dev.duma.android.hal.contract

import java.util.concurrent.CopyOnWriteArrayList
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

    private val _events = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = 256)
    val events: SharedFlow<EventEnvelope> = _events.asSharedFlow()

    private val listeners = CopyOnWriteArrayList<PluginListener>()

    fun emit(eventName: String, jsonData: String, sourcePluginId: String) {
        val envelope = EventEnvelope(eventName, jsonData, sourcePluginId)

        listeners.forEach { listener ->
            if (listener.listenerPluginId != sourcePluginId &&
                matchesSubscription(listener.pattern, eventName, sourcePluginId)
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

        /**
         * Matches an event against a subscription entry of the form `namePattern[@sourcePattern]`.
         * Both halves use [matchesPattern] (exact / `prefix.*` / `*`); a missing `@source` matches any
         * emitter — e.g. `scanner.*@sunmi.*` or `demo.notice@demo.beta`.
         */
        fun matchesSubscription(subscription: String, eventName: String, sourcePluginId: String): Boolean {
            val at = subscription.indexOf('@')
            if (at < 0) return matchesPattern(subscription, eventName)
            val namePattern = subscription.substring(0, at)
            val sourcePattern = subscription.substring(at + 1)
            return matchesPattern(namePattern, eventName) && matchesPattern(sourcePattern, sourcePluginId)
        }
    }
}
