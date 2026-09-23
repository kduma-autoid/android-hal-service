package dev.duma.android.hal.service.core

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.service.auth.AuthManager
import dev.duma.android.hal.service.auth.TokenEntity
import dev.duma.android.hal.service.auth.permissionList
import dev.duma.android.hal.service.auth.TokenManager
import dev.duma.android.hal.service.auth.TokenRequest
import dev.duma.android.hal.service.auth.TokenResponse
import dev.duma.android.hal.service.config.ExperimentalConfig
import dev.duma.android.hal.service.plugin.PluginRegistry
import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.CommandHandler
import dev.duma.android.hal.transport.core.TransportRegistry
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.InterfaceContract
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
                val tokenEntity = requireToken(token, callerContext)
                    ?: return CommandResult.unauthorized("Invalid token")
                handleSetInterfaceOrder(params, tokenEntity)
            }
            "system.interface.setEnabled" -> {
                val tokenEntity = requireToken(token, callerContext)
                    ?: return CommandResult.unauthorized("Invalid token")
                handleSetInterfaceEnabled(params, tokenEntity)
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

                val permissions = tokenEntity.permissionList
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

                // Experimental gate. Three independent sources mark a call experimental: the method
                // itself, the interface that owns it, and — for native methods — the whole plugin.
                val interfaceId = pluginRegistry.interfaceIdForMethod(baseMethod)
                val interfaceContract = interfaceId?.let { pluginRegistry.getInterfaceContract(it) }
                val pluginForMethod = pluginRegistry.findForMethod(baseMethod)
                val pluginDescriptor = pluginForMethod?.getDescriptor()
                val callerHasExperimental = permissions.any { perm ->
                    perm == "experimental" ||                            // global experimental
                    perm == "$requiredPermission.experimental" ||        // capability-level
                    perm == "$method.experimental"                       // method-level, provider-specific
                }
                val isExperimental = methodDescriptor?.experimental == true ||
                    interfaceContract?.experimental == true ||
                    pluginDescriptor?.experimental == true
                if (isExperimental) {
                    // The settings opt-in is keyed by plugin: the owning plugin for a native method,
                    // the *defining* plugin for anything reached through an interface.
                    val prefsKey = pluginDescriptor?.pluginId
                        ?: interfaceId?.let { pluginRegistry.definerForInterface(it) }
                    val isEnabledViaPrefs = prefsKey?.let { experimentalConfig.isPluginEnabled(it) } ?: false
                    if (!callerHasExperimental && !isEnabledViaPrefs) {
                        return CommandResult.forbidden("Experimental method not enabled: $method")
                    }
                }

                if (interfaceId != null) {
                    // The caller's experimental access travels with the call: an experimental provider
                    // the user has not enabled is excluded from resolution rather than gated here.
                    pluginRegistry.executeInterface(interfaceId, provider, baseMethod, params, callerHasExperimental)
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

    private fun handleSetInterfaceOrder(params: String, tokenEntity: TokenEntity): CommandResult {
        val obj = try { Json.parseToJsonElement(params) as? JsonObject } catch (_: Exception) { null }
            ?: return CommandResult.badRequest("Invalid JSON")
        val interfaceId = obj["interfaceId"]?.jsonPrimitive?.contentOrNull
            ?: return CommandResult.badRequest("Missing 'interfaceId'")
        val order = obj["order"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return CommandResult.badRequest("Missing 'order' array")
        requireInterfacePermission(interfaceId, tokenEntity)?.let { return it }
        pluginRegistry.setInterfaceOrder(interfaceId, order)
        return CommandResult.Success()
    }

    private fun handleSetInterfaceEnabled(params: String, tokenEntity: TokenEntity): CommandResult {
        val obj = try { Json.parseToJsonElement(params) as? JsonObject } catch (_: Exception) { null }
            ?: return CommandResult.badRequest("Invalid JSON")
        val interfaceId = obj["interfaceId"]?.jsonPrimitive?.contentOrNull
            ?: return CommandResult.badRequest("Missing 'interfaceId'")
        val pluginId = obj["pluginId"]?.jsonPrimitive?.contentOrNull
            ?: return CommandResult.badRequest("Missing 'pluginId'")
        val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return CommandResult.badRequest("Missing 'enabled' boolean")
        requireInterfacePermission(interfaceId, tokenEntity)?.let { return it }
        pluginRegistry.setInterfaceEnabled(interfaceId, pluginId, enabled)
        return CommandResult.Success()
    }

    override suspend fun subscribe(token: String, events: String, callerContext: CallerContext): CommandResult {
        val tokenEntity = requireToken(token, callerContext)
            ?: return CommandResult.unauthorized("Invalid token")
        val denied = deniedSubscriptions(events, tokenEntity)
        if (denied.isNotEmpty()) {
            return CommandResult.forbidden("No permission to subscribe: ${denied.joinToString(",")}")
        }
        return CommandResult.Success()
    }

    /**
     * Events a token may not subscribe to. Subscribing is how a client receives an event at all, so
     * it is the gate — without this any authenticated session could listen to `barcodeScanner.onScan`
     * or a native `*.barcode` with no permission for it.
     *
     * The `@source` half never widens access, so it is dropped before the permission is derived. The
     * permission itself still comes from the event *name* rather than from a descriptor, unlike
     * `execute`, because a wildcard subscription spans events that may not exist yet and so cannot be
     * resolved to one descriptor.
     */
    private fun deniedSubscriptions(events: String, tokenEntity: TokenEntity): List<String> {
        val permissions = tokenEntity.permissionList
        if ("*" in permissions) return emptyList()
        return events.split(",").map { it.trim() }.filter { it.isNotEmpty() }.filter { event ->
            val name = event.substringBefore('@')
            // Service-level events carry no device data — they announce that the plugin or interface
            // set changed — and every client needs them to re-resolve its backend. They are readable
            // by any valid token, like `system.status` and `system.describe`, and gating them here
            // broke every facade's onChanged() for tokens without `*`.
            if (name.startsWith("system.")) return@filter false
            val capability = if (name.endsWith(".*")) name.dropLast(2) else name.substringBeforeLast(".")
            permissions.none { capability.startsWith(it) }
        }
    }

    override suspend fun unsubscribe(token: String, events: String, callerContext: CallerContext): CommandResult {
        requireToken(token, callerContext)
            ?: return CommandResult.unauthorized("Invalid token")
        return CommandResult.Success()
    }

    override fun getStatus(): String = handleStatus()

    /**
     * Same shape as [handleDescribe]'s experimental step: events are filtered alongside methods, and
     * a plugin the filter emptied out is dropped instead of being advertised with nothing in it.
     */
    override fun describeApi(): String {
        val descriptors = pluginRegistry.getSupportedDescriptors().mapNotNull { desc ->
            if (experimentalConfig.isPluginEnabled(desc.pluginId)) {
                desc
            } else if (desc.experimental) {
                null
            } else {
                desc.copy(groups = filterGroups(desc.groups, methodPredicate = { !it.experimental }, eventPredicate = { !it.experimental }))
            }
        }.filter { it.allMethods.isNotEmpty() || it.allEvents.isNotEmpty() }
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

        val permissions = tokenEntity.permissionList
        val allDescriptors = pluginRegistry.getSupportedDescriptors()

        // The interfaces this caller sees, decided once. The `interfaces` section lists exactly these,
        // a plugin is listed for its interface work only through one of them, and its
        // providesInterfaces / definesInterfaces name only these — so a token scoped to `light` does
        // not learn from a dual light+printer provider that `printer` is wired there too.
        val visibleInterfaces = visibleInterfaces(permissions, withSuper, withExperimental)
        val visibleInterfaceIds = visibleInterfaces.map { it.contract.interfaceId }.toSet()

        // Step 1: Filter by token permissions
        val filtered = if ("*" in permissions) {
            allDescriptors
        } else {
            allDescriptors.map { desc ->
                desc.copy(groups = filterGroups(desc.groups,
                    methodPredicate = { m -> permissions.any { m.requiredPermission.startsWith(it) } },
                    eventPredicate = { e -> permissions.any { e.requiredPermission.startsWith(it) } }
                ))
            }.filter {
                it.allMethods.isNotEmpty() || it.allEvents.isNotEmpty() ||
                    hasVisibleInterface(it, visibleInterfaceIds)
            }
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
                        val provided = desc.interfaces.map { it.interfaceId }.filter { it in visibleInterfaceIds }
                        if (provided.isNotEmpty()) {
                            putJsonArray("providesInterfaces") { provided.forEach { add(JsonPrimitive(it)) } }
                        }
                        val defined = desc.definesInterfaces.map { it.interfaceId }.filter { it in visibleInterfaceIds }
                        if (defined.isNotEmpty()) {
                            putJsonArray("definesInterfaces") { defined.forEach { add(JsonPrimitive(it)) } }
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
                visibleInterfaces.forEach { visible ->
                    val (contract, methods, events, expViaToken, expUsable) = visible
                    add(buildJsonObject {
                        put("kind", "interface")
                        put("interfaceId", contract.interfaceId)
                        put("version", contract.version)
                        if (contract.experimental) {
                            put("experimental", true)
                            put("experimentalActive", expUsable)
                        }
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
                                    if (m.superRequired) put("superRequired", true)
                                    if (m.experimental) {
                                        put("experimental", true)
                                        put("experimentalActive", expUsable)
                                    }
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
                                    if (e.experimental) {
                                        put("experimental", true)
                                        put("experimentalActive", expUsable)
                                    }
                                    put("exampleEvent", e.exampleEvent)
                                })
                            }
                        }
                        putJsonArray("providers") {
                            // API lists loaded (supported + dynamically-available) providers, INCLUDING
                            // user-disabled ones (with an `enabled` flag) so a client can re-enable them.
                            // Only unavailable/unsupported implementors are hidden from the API.
                            // An experimental provider is hidden too until it is usable, because it is
                            // not part of the interface for this caller — routing skips it as well.
                            pluginRegistry.getAllInterfaceImplementors(contract.interfaceId, expViaToken)
                                .filter { it.available && it.supported }
                                .filter { p ->
                                    !p.experimental || withExperimental || expViaToken ||
                                        experimentalConfig.isPluginEnabled(p.pluginId)
                                }
                                .forEach { p ->
                                    add(buildJsonObject {
                                        put("pluginId", p.pluginId)
                                        put("source", p.source?.name?.lowercase() ?: "unknown")
                                        put("priority", p.priority)
                                        put("isDefault", p.isDefault)
                                        put("enabled", p.enabled)
                                        if (p.experimental) {
                                            put("experimental", true)
                                            put("experimentalActive",
                                                expViaToken || experimentalConfig.isPluginEnabled(p.pluginId))
                                        }
                                        putJsonArray("features") { p.features.forEach { add(JsonPrimitive(it)) } }
                                    })
                                }
                        }
                    })
                }
            }
        }.toString()
    }

    /**
     * Reordering or disabling an interface's providers is a persistent, device-wide write, so it takes
     * the permission of the interface it rewrites: every permission the contract declares must be
     * granted. A token scoped to `demo` cannot repoint the printer or the default scanner, which a
     * token check alone allowed.
     */
    private fun requireInterfacePermission(interfaceId: String, tokenEntity: TokenEntity): CommandResult? {
        val permissions = tokenEntity.permissionList
        if ("*" in permissions) return null
        val contract = pluginRegistry.getInterfaceContract(interfaceId)
            ?: return CommandResult.notFound("Interface not registered: $interfaceId")
        val required = (contract.methods.map { it.requiredPermission } +
            contract.events.map { it.requiredPermission }).distinct()
        val missing = required.filter { req -> permissions.none { req.startsWith(it) } }
        return if (missing.isEmpty()) null
        else CommandResult.forbidden("No permission for interface '$interfaceId': ${missing.joinToString(",")}")
    }

    private suspend fun requireToken(token: String, callerContext: CallerContext): TokenEntity? {
        return tokenManager.validateToken(token, callerContext)
    }

    /** A registered interface as one caller sees it: what of it is visible, and their experimental access. */
    private data class VisibleInterface(
        val contract: InterfaceContract,
        val methods: List<MethodDescriptor>,
        val events: List<EventDescriptor>,
        val expViaToken: Boolean,
        val expUsable: Boolean
    )

    /**
     * The registered interfaces a caller sees in `system.describe`, each narrowed to the methods and
     * events visible to it: permitted by the token, `super` ones only with [withSuper], experimental
     * ones only with access (token, or the user enabling the *defining* plugin) or
     * [withExperimental]. An interface with nothing left is not visible at all.
     */
    private fun visibleInterfaces(
        permissions: List<String>,
        withSuper: Boolean,
        withExperimental: Boolean
    ): List<VisibleInterface> = pluginRegistry.getRegisteredInterfaces().mapNotNull { contract ->
        // Experimental access for this interface: the caller's token, or the user having enabled the
        // plugin that *defines* it. `withExperimental` reveals it in the listing regardless, mirroring
        // how the plugins section behaves.
        val definer = pluginRegistry.definerForInterface(contract.interfaceId)
        val expViaToken = permissions.any { perm ->
            perm == "experimental" ||
            contract.methods.any { m -> perm == "${m.requiredPermission}.experimental" }
        }
        val expViaPrefs = definer?.let { experimentalConfig.isPluginEnabled(it) } ?: false
        val expUsable = expViaToken || expViaPrefs
        val expVisible = withExperimental || expUsable
        if (contract.experimental && !expVisible) return@mapNotNull null

        var methods = if ("*" in permissions) contract.methods
            else contract.methods.filter { m -> permissions.any { m.requiredPermission.startsWith(it) } }
        var events = if ("*" in permissions) contract.events
            else contract.events.filter { e -> permissions.any { e.requiredPermission.startsWith(it) } }
        if (!withSuper) methods = methods.filterNot { it.superRequired }
        if (!expVisible) {
            methods = methods.filterNot { it.experimental }
            events = events.filterNot { it.experimental }
        }
        if (methods.isEmpty() && events.isEmpty()) null
        else VisibleInterface(contract, methods, events, expViaToken, expUsable)
    }

    /**
     * Whether a plugin earns its place in the listing through interface work: it provides or defines
     * an interface the caller sees. A provider with no native methods has nothing else to show, so it
     * must stay visible to a caller who may use the interface — but keeping every such plugin for
     * everyone listed the device's pluginIds and its interface wiring to tokens holding no permission
     * for any of it.
     */
    private fun hasVisibleInterface(desc: PluginDescriptor, visibleInterfaceIds: Set<String>): Boolean =
        desc.interfaces.any { it.interfaceId in visibleInterfaceIds } ||
            desc.definesInterfaces.any { it.interfaceId in visibleInterfaceIds }

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
