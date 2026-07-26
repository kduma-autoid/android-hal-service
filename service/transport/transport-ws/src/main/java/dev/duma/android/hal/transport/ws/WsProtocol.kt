package dev.duma.android.hal.transport.ws

import dev.duma.android.hal.contract.EventBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WebSocket protocol serialization/deserialization utility. Parses client JSON messages
 * into typed [WsMessage] objects and serializes server responses, errors, and events.
 * Also provides subscription matching and permission validation.
 */
object WsProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): WsMessage {
        return try {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            val id = obj["id"]?.jsonPrimitive?.content ?: ""

            when (type) {
                "requestToken" -> WsMessage.RequestToken(
                    id = id,
                    clientId = obj["clientId"]?.jsonPrimitive?.content ?: "",
                    serviceKey = obj["serviceKey"]?.jsonPrimitive?.content
                )
                "authenticate" -> WsMessage.Authenticate(
                    id = id,
                    token = obj["token"]?.jsonPrimitive?.content ?: ""
                )
                "command" -> WsMessage.Command(
                    id = id,
                    method = obj["method"]?.jsonPrimitive?.content ?: "",
                    params = obj["params"]?.toString() ?: "{}"
                )
                "subscribe" -> WsMessage.Subscribe(
                    id = id,
                    events = obj["events"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                )
                "unsubscribe" -> WsMessage.Unsubscribe(
                    id = id,
                    events = obj["events"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                )
                else -> WsMessage.ParseError(text)
            }
        } catch (_: Exception) {
            WsMessage.ParseError(text)
        }
    }

    fun serializeResponse(id: String, result: String, provider: String? = null): String {
        return buildJsonObject {
            put("id", id)
            put("type", "response")
            // Provider that handled the call (interface methods), in the frame header — sibling of
            // `result`, not inside it. Absent for native/system methods.
            provider?.let { put("provider", it) }
            put("result", json.parseToJsonElement(result))
        }.toString()
    }

    fun serializeError(id: String?, code: String, message: String): String {
        return buildJsonObject {
            id?.let { put("id", it) }
            put("type", "error")
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString()
    }

    fun serializeEvent(eventName: String, jsonData: String, source: String): String {
        return buildJsonObject {
            put("type", "event")
            put("event", eventName)
            // Emitting plugin id, exposed in the frame header (sibling of `data`, not inside it).
            put("source", source)
            put("data", json.parseToJsonElement(jsonData))
        }.toString()
    }

    fun matchesAnySubscription(subscriptions: Set<String>, eventName: String, source: String): Boolean {
        return subscriptions.any { EventBus.matchesSubscription(it, eventName, source) }
    }

    data class SubscriptionValidation(
        val allowed: List<String>,
        val denied: List<String>
    )

    fun validateSubscriptions(events: List<String>, permissions: List<String>): SubscriptionValidation {
        if ("*" in permissions) {
            return SubscriptionValidation(allowed = events, denied = emptyList())
        }

        val allowed = mutableListOf<String>()
        val denied = mutableListOf<String>()

        for (event in events) {
            // Permission is derived from the event-name half; the optional `@source` filter doesn't
            // widen access.
            val name = event.substringBefore('@')
            val eventCapability = if (name.endsWith(".*")) {
                name.dropLast(2)
            } else {
                name.substringBeforeLast(".")
            }
            if (permissions.any { eventCapability.startsWith(it) }) {
                allowed.add(event)
            } else {
                denied.add(event)
            }
        }

        return SubscriptionValidation(allowed, denied)
    }
}
