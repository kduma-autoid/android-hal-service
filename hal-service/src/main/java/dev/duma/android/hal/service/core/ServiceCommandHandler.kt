package dev.duma.android.hal.service.core

import dev.duma.android.hal.service.auth.AuthManager
import dev.duma.android.hal.service.auth.TokenEntity
import dev.duma.android.hal.service.auth.TokenManager
import dev.duma.android.hal.service.auth.TokenRequest
import dev.duma.android.hal.service.auth.TokenResponse
import dev.duma.android.hal.service.plugin.PluginRegistry
import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.CommandHandler
import dev.duma.android.hal.transport.core.TransportRegistry
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Core command handler implementing [CommandHandler]. Routes requests to auth system,
 * system methods (ping/status/describe), or plugins. Bridge between transports
 * and hal-service internals. Thread-safe — delegates to thread-safe components.
 */
class ServiceCommandHandler(
    private val authManager: AuthManager,
    private val tokenManager: TokenManager,
    private val pluginRegistry: PluginRegistry,
    private val transportRegistry: TransportRegistry,
    private val startTimeMillis: Long = System.currentTimeMillis()
) : CommandHandler {

    override suspend fun requestToken(request: String, callerContext: CallerContext): String {
        val json = Json.parseToJsonElement(request) as? JsonObject
            ?: return errorJson("invalid_request", "Invalid JSON")

        val tokenRequest = TokenRequest(
            developerKey = json["developerKey"]?.jsonPrimitive?.content
                ?: json["developer_key"]?.jsonPrimitive?.content,
            clientId = json["clientId"]?.jsonPrimitive?.content
                ?: json["client_id"]?.jsonPrimitive?.content
                ?: "unknown"
        )

        return when (val result = authManager.requestToken(tokenRequest, callerContext)) {
            is TokenResponse.Success -> buildJsonObject {
                put("token", result.token)
                putJsonArray("permissions") { result.permissions.forEach { add(JsonPrimitive(it)) } }
                result.expiresAt?.let { put("expires_at", it) }
            }.toString()
            is TokenResponse.Error -> errorJson(result.code, result.message)
        }
    }

    override suspend fun execute(
        token: String,
        method: String,
        params: String,
        callerContext: CallerContext
    ): String {
        return when (method) {
            "system.ping" -> handlePing()
            "system.status" -> {
                requireToken(token, callerContext) ?: return errorJson("unauthorized", "Invalid token")
                handleStatus()
            }
            "system.describe" -> {
                val tokenEntity = requireToken(token, callerContext)
                    ?: return errorJson("unauthorized", "Invalid token")
                handleDescribe(tokenEntity)
            }
            else -> {
                val tokenEntity = requireToken(token, callerContext)
                    ?: return errorJson("unauthorized", "Invalid token")

                val permissions = tokenEntity.permissions.split(",")
                val methodCapability = method.substringBeforeLast(".")
                if ("*" !in permissions && permissions.none { methodCapability.startsWith(it) }) {
                    return errorJson("forbidden", "No permission for method: $method")
                }

                pluginRegistry.executeOnPlugin(method, params)
            }
        }
    }

    override suspend fun subscribe(token: String, events: String, callerContext: CallerContext): String {
        requireToken(token, callerContext)
            ?: return errorJson("unauthorized", "Invalid token")
        return buildJsonObject { put("status", "ok") }.toString()
    }

    override suspend fun unsubscribe(token: String, events: String, callerContext: CallerContext): String {
        requireToken(token, callerContext)
            ?: return errorJson("unauthorized", "Invalid token")
        return buildJsonObject { put("status", "ok") }.toString()
    }

    override fun getStatus(): String = handleStatus()

    override fun describeApi(): String {
        return Json.encodeToString(Json.encodeToJsonElement(pluginRegistry.getAllDescriptors()))
    }

    private fun handlePing(): String {
        return buildJsonObject {
            put("pong", true)
            put("timestamp", System.currentTimeMillis() / 1000)
        }.toString()
    }

    private fun handleStatus(): String {
        val uptimeSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000

        return buildJsonObject {
            put("uptime", uptimeSeconds)
            putJsonObject("plugins") {
                pluginRegistry.getAllDescriptors().forEach { desc ->
                    putJsonObject(desc.pluginId) {
                        put("version", desc.version)
                        putJsonArray("capabilities") { desc.capabilities.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
            putJsonObject("transports") {
                transportRegistry.getCommandTransports().forEach { t ->
                    putJsonObject(t.transportId) {
                        put("running", t.isRunning)
                    }
                }
                transportRegistry.getEventTransports().forEach { t ->
                    putJsonObject(t.transportId) {
                        put("running", t.isRunning)
                        put("toggleable", t.isToggleable)
                        put("enabled", t.isEnabled)
                    }
                }
            }
        }.toString()
    }

    private fun handleDescribe(tokenEntity: TokenEntity): String {
        val permissions = tokenEntity.permissions.split(",")
        val allDescriptors = pluginRegistry.getAllDescriptors()

        val filtered = if ("*" in permissions) {
            allDescriptors
        } else {
            allDescriptors.map { desc ->
                desc.copy(
                    methods = desc.methods.filter { m ->
                        permissions.any { m.requiredPermission.startsWith(it) }
                    },
                    events = desc.events.filter { e ->
                        permissions.any { e.requiredPermission.startsWith(it) }
                    }
                )
            }.filter { it.methods.isNotEmpty() || it.events.isNotEmpty() }
        }

        return Json.encodeToString(buildJsonObject {
            putJsonArray("plugins") {
                filtered.forEach { desc ->
                    add(Json.encodeToJsonElement<PluginDescriptor>(desc))
                }
            }
        })
    }

    private suspend fun requireToken(token: String, callerContext: CallerContext): TokenEntity? {
        return tokenManager.validateToken(token, callerContext)
    }

    private fun errorJson(code: String, message: String): String {
        return buildJsonObject {
            put("error", code)
            put("message", message)
        }.toString()
    }
}
