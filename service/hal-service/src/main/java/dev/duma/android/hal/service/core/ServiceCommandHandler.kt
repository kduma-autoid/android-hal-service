package dev.duma.android.hal.service.core

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.service.auth.AuthManager
import dev.duma.android.hal.service.auth.TokenEntity
import dev.duma.android.hal.service.auth.TokenManager
import dev.duma.android.hal.service.auth.TokenRequest
import dev.duma.android.hal.service.auth.TokenResponse
import dev.duma.android.hal.service.config.ExperimentalConfig
import dev.duma.android.hal.service.config.InterfacePreferenceConfig
import dev.duma.android.hal.service.plugin.PluginRegistry
import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.CommandHandler
import dev.duma.android.hal.transport.core.TransportRegistry
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.allMethods
import dev.duma.android.hal.contract.allEvents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    private val interfacePreferenceConfig: InterfacePreferenceConfig,
    private val startTimeMillis: Long = System.currentTimeMillis(),
    private val versionName: String? = null,
    private val versionCode: Int? = null
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
            "system.interface.setOrder" -> {
                requireToken(token, callerContext) ?: return CommandResult.unauthorized("Invalid token")
                handleSetInterfaceOrder(params)
            }
            "system.interface.setEnabled" -> {
                requireToken(token, callerContext) ?: return CommandResult.unauthorized("Invalid token")
                handleSetInterfaceEnabled(params)
            }
            else -> {
                val tokenEntity = requireToken(token, callerContext)
                    ?: return CommandResult.unauthorized("Invalid token")

                // Provider selector: `method@providerId` pins one provider of an interface for this
                // call, mirroring the `event@source` subscription syntax. Split it off up front: the
                // descriptor lookup and routing key on the bare name, while the super/experimental
                // gates below deliberately use the full name so they can be granted per provider.
                val selector = method.indexOf('@')
                val baseMethod = if (selector >= 0) method.substring(0, selector) else method
                val provider = if (selector >= 0) method.substring(selector + 1).ifEmpty { null } else null

                val permissions = tokenEntity.permissions.split(",")
                val methodDescriptor = pluginRegistry.getMethodDescriptor(baseMethod)

                // The descriptor's declared permission is the source of truth — the same field
                // `system.describe` filters on, so the catalogue and enforcement cannot disagree.
                // Only when there is no descriptor (unknown method, or one filtered out of a stable
                // build) do we fall back to deriving it, so such calls still reach the plugin lookup
                // and surface as `not_found` rather than a misleading `forbidden`.
                val requiredPermission = methodDescriptor?.requiredPermission
                    ?: baseMethod.substringBeforeLast(".")
                if ("*" !in permissions && permissions.none { requiredPermission.startsWith(it) }) {
                    return CommandResult.forbidden("No permission for method: $method")
                }

                // Super and experimental gates are evaluated against the full method name *including*
                // the `@providerId` selector, so they can be granted per provider — the CPad LED and
                // the FLEX status light are different hardware behind one interface method.
                if (methodDescriptor?.superRequired == true) {
                    val hasSuperAccess = permissions.any { perm ->
                        perm == "super" ||                            // global super
                        perm == "$requiredPermission.super" ||        // capability-level super
                        perm == "$method.super"                       // method-level, provider-specific
                    }
                    if (!hasSuperAccess) {
                        return CommandResult.forbidden("Super permission required for: $method")
                    }
                }

                // Experimental method check
                val pluginForMethod = pluginRegistry.findForMethod(baseMethod)
                val pluginDescriptor = pluginForMethod?.getDescriptor()
                val isExperimental = methodDescriptor?.experimental == true || pluginDescriptor?.experimental == true
                if (isExperimental) {
                    val hasExperimentalAccess = permissions.any { perm ->
                        perm == "experimental" ||                            // global experimental
                        perm == "$requiredPermission.experimental" ||        // capability-level
                        perm == "$method.experimental"                       // method-level, provider-specific
                    }
                    val isEnabledViaPrefs = pluginDescriptor?.pluginId?.let {
                        experimentalConfig.isPluginEnabled(it)
                    } ?: false
                    if (!hasExperimentalAccess && !isEnabledViaPrefs) {
                        return CommandResult.forbidden("Experimental method not enabled: $method")
                    }
                }

                val interfaceId = pluginRegistry.interfaceIdForMethod(baseMethod)
                if (interfaceId != null) {
                    pluginRegistry.executeInterface(interfaceId, provider, baseMethod, params)
                } else if (provider != null) {
                    // Native methods are owned by exactly one plugin, so pinning a provider is
                    // meaningless there — reject it instead of silently ignoring the selector.
                    CommandResult.badRequest("Provider selector is only supported for interface methods: $method")
                } else {
                    pluginRegistry.executeOnPlugin(baseMethod, params)
                }
            }
        }
    }

    private fun handleSetInterfaceOrder(params: String): CommandResult {
        val obj = try { Json.parseToJsonElement(params) as? JsonObject } catch (_: Exception) { null }
            ?: return CommandResult.badRequest("Invalid JSON")
        val interfaceId = obj["interfaceId"]?.jsonPrimitive?.contentOrNull
            ?: return CommandResult.badRequest("Missing 'interfaceId'")
        val order = obj["order"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return CommandResult.badRequest("Missing 'order' array")
        pluginRegistry.setInterfaceOrder(interfaceId, order)
        return CommandResult.Success()
    }

    private fun handleSetInterfaceEnabled(params: String): CommandResult {
        val obj = try { Json.parseToJsonElement(params) as? JsonObject } catch (_: Exception) { null }
            ?: return CommandResult.badRequest("Invalid JSON")
        val interfaceId = obj["interfaceId"]?.jsonPrimitive?.contentOrNull
            ?: return CommandResult.badRequest("Missing 'interfaceId'")
        val pluginId = obj["pluginId"]?.jsonPrimitive?.contentOrNull
            ?: return CommandResult.badRequest("Missing 'pluginId'")
        val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return CommandResult.badRequest("Missing 'enabled' boolean")
        pluginRegistry.setInterfaceEnabled(interfaceId, pluginId, enabled)
        return CommandResult.Success()
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
                desc.copy(groups = filterGroups(desc.groups, methodPredicate = { !it.experimental }))
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
            if (versionName != null || versionCode != null) {
                putJsonObject("version") {
                    versionName?.let { put("name", it) }
                    versionCode?.let { put("code", it) }
                }
            }
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
                desc.copy(groups = filterGroups(desc.groups,
                    methodPredicate = { m -> permissions.any { m.requiredPermission.startsWith(it) } },
                    eventPredicate = { e -> permissions.any { e.requiredPermission.startsWith(it) } }
                ))
            }.filter { it.allMethods.isNotEmpty() || it.allEvents.isNotEmpty() || it.interfaces.isNotEmpty() || it.definesInterfaces.isNotEmpty() }
        }

        // Step 2: Filter super methods unless withSuper=true
        val afterSuperFilter = if (withSuper) {
            filtered
        } else {
            filtered.map { desc ->
                desc.copy(groups = filterGroups(desc.groups, methodPredicate = { !it.superRequired }))
            }.filter { it.allMethods.isNotEmpty() || it.allEvents.isNotEmpty() || it.interfaces.isNotEmpty() || it.definesInterfaces.isNotEmpty() }
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
                    desc.copy(groups = filterGroups(desc.groups, methodPredicate = { !it.experimental }, eventPredicate = { !it.experimental }))
                }
            }.filter { it.allMethods.isNotEmpty() || it.allEvents.isNotEmpty() || it.interfaces.isNotEmpty() || it.definesInterfaces.isNotEmpty() }
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
                        if (desc.interfaces.isNotEmpty()) {
                            putJsonArray("providesInterfaces") { desc.interfaces.forEach { add(JsonPrimitive(it.interfaceId)) } }
                        }
                        if (desc.definesInterfaces.isNotEmpty()) {
                            putJsonArray("definesInterfaces") { desc.definesInterfaces.forEach { add(JsonPrimitive(it.interfaceId)) } }
                        }
                        putJsonArray("groups") {
                            desc.groups.forEach { group ->
                                add(buildJsonObject {
                                    group.name?.let { put("name", it) }
                                    putJsonArray("methods") {
                                        group.methods.forEach { m ->
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
                                        group.events.forEach { e ->
                                            add(buildJsonObject {
                                                put("name", e.name)
                                                put("description", e.description)
                                                put("requiredPermission", e.requiredPermission)
                                                if (e.experimental || desc.experimental) {
                                                    put("experimental", true)
                                                    put("experimentalActive", isExpEnabledViaPrefs || hasExpViaToken)
                                                }
                                                put("exampleEvent", e.exampleEvent)
                                            })
                                        }
                                    }
                                })
                            }
                        }
                    })
                }
            }
            putJsonArray("interfaces") {
                pluginRegistry.getRegisteredInterfaces().forEach { contract ->
                    val methods = if ("*" in permissions) contract.methods
                        else contract.methods.filter { m -> permissions.any { m.requiredPermission.startsWith(it) } }
                    val events = if ("*" in permissions) contract.events
                        else contract.events.filter { e -> permissions.any { e.requiredPermission.startsWith(it) } }
                    if (methods.isEmpty() && events.isEmpty()) return@forEach
                    add(buildJsonObject {
                        put("kind", "interface")
                        put("interfaceId", contract.interfaceId)
                        put("version", contract.version)
                        putJsonArray("features") {
                            contract.features.forEach { f ->
                                add(buildJsonObject {
                                    put("key", f.key)
                                    put("description", f.description)
                                    putJsonArray("methods") { f.methods.forEach { add(JsonPrimitive(it)) } }
                                })
                            }
                        }
                        putJsonArray("methods") {
                            methods.forEach { m ->
                                add(buildJsonObject {
                                    put("name", m.name)
                                    put("description", m.description)
                                    put("requiredPermission", m.requiredPermission)
                                    put("exampleParameters", m.exampleParameters)
                                    put("exampleOutput", m.exampleOutput)
                                })
                            }
                        }
                        putJsonArray("events") {
                            events.forEach { e ->
                                add(buildJsonObject {
                                    put("name", e.name)
                                    put("description", e.description)
                                    put("requiredPermission", e.requiredPermission)
                                    put("exampleEvent", e.exampleEvent)
                                })
                            }
                        }
                        putJsonArray("providers") {
                            // API lists loaded (supported + dynamically-available) providers, INCLUDING
                            // user-disabled ones (with an `enabled` flag) so a client can re-enable them.
                            // Only unavailable/unsupported implementors are hidden from the API.
                            pluginRegistry.getAllInterfaceImplementors(contract.interfaceId)
                                .filter { it.available && it.supported }
                                .forEach { p ->
                                    add(buildJsonObject {
                                        put("pluginId", p.pluginId)
                                        put("source", p.source?.name?.lowercase() ?: "unknown")
                                        put("priority", p.priority)
                                        put("isDefault", p.isDefault)
                                        put("enabled", p.enabled)
                                        putJsonArray("features") { p.features.forEach { add(JsonPrimitive(it)) } }
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

    private fun filterGroups(
        groups: List<DescriptorGroup>,
        methodPredicate: ((MethodDescriptor) -> Boolean)? = null,
        eventPredicate: ((EventDescriptor) -> Boolean)? = null
    ): List<DescriptorGroup> = groups.map { g ->
        g.copy(
            methods = if (methodPredicate != null) g.methods.filter(methodPredicate) else g.methods,
            events = if (eventPredicate != null) g.events.filter(eventPredicate) else g.events
        )
    }.filter { it.methods.isNotEmpty() || it.events.isNotEmpty() }

    private fun errorTypeForCode(code: String): CommandResult.ErrorType = when (code) {
        "invalid_key", "key_expired", "restriction_mismatch" -> CommandResult.ErrorType.BAD_REQUEST
        "user_denied" -> CommandResult.ErrorType.FORBIDDEN
        "timeout" -> CommandResult.ErrorType.TIMEOUT
        else -> CommandResult.ErrorType.INTERNAL
    }
}
