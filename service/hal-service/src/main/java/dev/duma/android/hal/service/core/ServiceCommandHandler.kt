package dev.duma.android.hal.service.core

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.service.auth.AuthManager
import dev.duma.android.hal.service.auth.TokenEntity
import dev.duma.android.hal.service.auth.TokenManager
import dev.duma.android.hal.service.auth.TokenRequest
import dev.duma.android.hal.service.auth.TokenResponse
import dev.duma.android.hal.service.config.ExperimentalConfig
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
import kotlinx.serialization.json.jsonArray
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
    private val experimentalConfig: ExperimentalConfig,
    private val startTimeMillis: Long = System.currentTimeMillis()
) : CommandHandler {

    override suspend fun requestToken(request: String, callerContext: CallerContext): CommandResult {
        val json = Json.parseToJsonElement(request) as? JsonObject
            ?: return CommandResult.badRequest("Invalid JSON")

        val requestedPermissions = (json["requestedPermissions"] ?: json["requested_permissions"])
            ?.jsonArray?.map { it.jsonPrimitive.content }

        val tokenRequest = TokenRequest(
            serviceKey = json["serviceKey"]?.jsonPrimitive?.content
                ?: json["service_key"]?.jsonPrimitive?.content,
            clientId = json["clientId"]?.jsonPrimitive?.content
                ?: json["client_id"]?.jsonPrimitive?.content
                ?: "unknown",
            requestedPermissions = requestedPermissions
        )

        return when (val result = authManager.requestToken(tokenRequest, callerContext)) {
            is TokenResponse.Success -> CommandResult.Success(buildJsonObject {
                put("token", result.token)
                putJsonArray("permissions") { result.permissions.forEach { add(JsonPrimitive(it)) } }
                result.expiresAt?.let { put("expires_at", it) }
            }.toString())
            is TokenResponse.Error -> CommandResult.Failure(result.code, result.message, errorTypeForCode(result.code))
        }
    }

    override suspend fun execute(
        token: String,
        method: String,
        params: String,
        callerContext: CallerContext
    ): CommandResult {
        return when (method) {
            "system.ping" -> CommandResult.Success(handlePing())
            "system.status" -> {
                requireToken(token, callerContext) ?: return CommandResult.unauthorized("Invalid token")
                CommandResult.Success(handleStatus())
            }
            "system.describe" -> {
                val tokenEntity = requireToken(token, callerContext)
                    ?: return CommandResult.unauthorized("Invalid token")
                CommandResult.Success(handleDescribe(tokenEntity, params))
            }
            else -> {
                val tokenEntity = requireToken(token, callerContext)
                    ?: return CommandResult.unauthorized("Invalid token")

                val permissions = tokenEntity.permissions.split(",")
                val methodCapability = method.substringBeforeLast(".")
                if ("*" !in permissions && permissions.none { methodCapability.startsWith(it) }) {
                    return CommandResult.forbidden("No permission for method: $method")
                }

                // Super permission check
                val methodDescriptor = pluginRegistry.getMethodDescriptor(method)
                if (methodDescriptor?.superRequired == true) {
                    val hasSuperAccess = permissions.any { perm ->
                        perm == "super" ||                          // global super
                        perm == "$methodCapability.super" ||        // capability-level super
                        perm == "$method.super"                     // method-level super
                    }
                    if (!hasSuperAccess) {
                        return CommandResult.forbidden("Super permission required for: $method")
                    }
                }

                // Experimental method check
                val pluginForMethod = pluginRegistry.findForMethod(method)
                val pluginDescriptor = pluginForMethod?.getDescriptor()
                val isExperimental = methodDescriptor?.experimental == true || pluginDescriptor?.experimental == true
                if (isExperimental) {
                    val hasExperimentalAccess = permissions.any { perm ->
                        perm == "experimental" ||                          // global experimental
                        perm == "$methodCapability.experimental" ||        // capability-level
                        perm == "$method.experimental"                     // method-level
                    }
                    val isEnabledViaPrefs = pluginDescriptor?.pluginId?.let {
                        experimentalConfig.isPluginEnabled(it)
                    } ?: false
                    if (!hasExperimentalAccess && !isEnabledViaPrefs) {
                        return CommandResult.forbidden("Experimental method not enabled: $method")
                    }
                }

                pluginRegistry.executeOnPlugin(method, params)
            }
        }
    }

    override suspend fun subscribe(token: String, events: String, callerContext: CallerContext): CommandResult {
        requireToken(token, callerContext)
            ?: return CommandResult.unauthorized("Invalid token")
        return CommandResult.Success()
    }

    override suspend fun unsubscribe(token: String, events: String, callerContext: CallerContext): CommandResult {
        requireToken(token, callerContext)
            ?: return CommandResult.unauthorized("Invalid token")
        return CommandResult.Success()
    }

    override fun getStatus(): String = handleStatus()

    override fun describeApi(): String {
        val descriptors = pluginRegistry.getSupportedDescriptors().mapNotNull { desc ->
            if (experimentalConfig.isPluginEnabled(desc.pluginId)) {
                desc
            } else if (desc.experimental) {
                null
            } else {
                desc.copy(methods = desc.methods.filter { !it.experimental })
            }
        }
        return Json.encodeToString(Json.encodeToJsonElement(descriptors))
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
                pluginRegistry.getSupportedDescriptors().forEach { desc ->
                    putJsonObject(desc.pluginId) {
                        put("version", desc.version)
                        putJsonArray("capabilities") { desc.capabilities.forEach { add(JsonPrimitive(it)) } }
                        val info = pluginRegistry.getPluginInfo(desc.pluginId)
                        put("source", info?.source?.name?.lowercase() ?: "unknown")
                        info?.packageName?.let { put("package", it) }
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

    private fun handleDescribe(tokenEntity: TokenEntity, params: String): String {
        val json = try { Json.parseToJsonElement(params) as? JsonObject } catch (_: Exception) { null }
        val withSuper = json?.get("withSuper")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val withExperimental = json?.get("withExperimental")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val permissions = tokenEntity.permissions.split(",")
        val allDescriptors = pluginRegistry.getSupportedDescriptors()

        // Step 1: Filter by token permissions
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

        // Step 2: Filter super methods unless withSuper=true
        val afterSuperFilter = if (withSuper) {
            filtered
        } else {
            filtered.map { desc ->
                desc.copy(methods = desc.methods.filter { !it.superRequired })
            }.filter { it.methods.isNotEmpty() || it.events.isNotEmpty() }
        }

        // Step 3: Filter experimental methods/plugins unless withExperimental=true
        val afterExperimentalFilter = if (withExperimental) {
            afterSuperFilter
        } else {
            afterSuperFilter.mapNotNull { desc ->
                val hasExperimentalViaToken = permissions.any { perm ->
                    perm == "experimental" ||
                    desc.capabilities.any { cap -> perm == "$cap.experimental" }
                }
                val isEnabledViaPrefs = experimentalConfig.isPluginEnabled(desc.pluginId)

                if (hasExperimentalViaToken || isEnabledViaPrefs) {
                    desc
                } else if (desc.experimental) {
                    null
                } else {
                    desc.copy(methods = desc.methods.filter { !it.experimental })
                }
            }.filter { it.methods.isNotEmpty() || it.events.isNotEmpty() }
        }

        // Step 4: Build response with extra metadata
        return buildJsonObject {
            putJsonArray("plugins") {
                afterExperimentalFilter.forEach { desc ->
                    val isExpEnabledViaPrefs = experimentalConfig.isPluginEnabled(desc.pluginId)
                    val hasExpViaToken = permissions.any { perm ->
                        perm == "experimental" ||
                        desc.capabilities.any { cap -> perm == "$cap.experimental" }
                    }
                    add(buildJsonObject {
                        put("pluginId", desc.pluginId)
                        put("name", desc.name)
                        put("version", desc.version)
                        if (desc.experimental) {
                            put("experimental", true)
                            put("experimentalActive", isExpEnabledViaPrefs || hasExpViaToken)
                        }
                        putJsonArray("capabilities") { desc.capabilities.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("methods") {
                            desc.methods.forEach { m ->
                                add(buildJsonObject {
                                    put("name", m.name)
                                    put("description", m.description)
                                    put("requiredPermission", m.requiredPermission)
                                    if (m.superRequired) put("superRequired", true)
                                    if (m.experimental || desc.experimental) {
                                        put("experimental", true)
                                        put("experimentalActive", isExpEnabledViaPrefs || hasExpViaToken)
                                    }
                                    put("exampleParameters", m.exampleParameters)
                                    put("exampleOutput", m.exampleOutput)
                                })
                            }
                        }
                        putJsonArray("events") {
                            desc.events.forEach { e ->
                                add(buildJsonObject {
                                    put("name", e.name)
                                    put("description", e.description)
                                    put("requiredPermission", e.requiredPermission)
                                    put("exampleEvent", e.exampleEvent)
                                })
                            }
                        }
                    })
                }
            }
        }.toString()
    }

    private suspend fun requireToken(token: String, callerContext: CallerContext): TokenEntity? {
        return tokenManager.validateToken(token, callerContext)
    }

    private fun errorTypeForCode(code: String): CommandResult.ErrorType = when (code) {
        "invalid_key", "key_expired", "restriction_mismatch" -> CommandResult.ErrorType.BAD_REQUEST
        "user_denied" -> CommandResult.ErrorType.FORBIDDEN
        "timeout" -> CommandResult.ErrorType.TIMEOUT
        else -> CommandResult.ErrorType.INTERNAL
    }
}
