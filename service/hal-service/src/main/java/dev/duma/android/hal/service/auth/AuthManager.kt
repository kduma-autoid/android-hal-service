package dev.duma.android.hal.service.auth

import dev.duma.android.hal.transport.core.CallerContext

/**
 * Orchestrates the requestToken flow: verifies service key JWT if provided,
 * or delegates to user consent dialog. Creates session tokens via [TokenManager].
 * Central entry point for all authorization requests from any transport.
 */
class AuthManager(
    private val tokenManager: TokenManager,
    private val serviceKeyVerifier: ServiceKeyVerifier,
    private val deviceKeyVerifier: (() -> ServiceKeyVerifier?)? = null,
    private val showGrantDialog: suspend (CallerContext, TokenRequest) -> GrantDecision,
    private val isSuperViaDialogAllowed: () -> Boolean = { false }
) {
    suspend fun requestToken(request: TokenRequest, callerContext: CallerContext): TokenResponse {
        if (request.serviceKey != null) {
            return handleServiceKey(request, callerContext)
        }
        return handleUserGrant(request, callerContext)
    }

    private suspend fun handleServiceKey(request: TokenRequest, callerContext: CallerContext): TokenResponse {
        val jwt = request.serviceKey!!
        var result = serviceKeyVerifier.verify(jwt, callerContext, request.clientId)
        var keySource = "developer key"

        // If service key signature doesn't match, try device key as fallback
        if (result is VerificationResult.Error
            && result.error == ServiceKeyError.INVALID_SIGNATURE
            && deviceKeyVerifier != null) {
            val deviceVerifier = deviceKeyVerifier.invoke()
            if (deviceVerifier != null) {
                val deviceResult = deviceVerifier.verify(jwt, callerContext, request.clientId)
                if (deviceResult !is VerificationResult.Error
                    || deviceResult.error != ServiceKeyError.INVALID_SIGNATURE) {
                    result = deviceResult
                    keySource = "device key"
                }
            }
        }

        // Device keys cannot grant unrestricted tokens
        if (keySource == "device key"
            && result is VerificationResult.Success
            && "unrestricted" in result.claims.clientTypes) {
            return TokenResponse.Error("restriction_mismatch", "Device key cannot grant unrestricted tokens")
        }

        return handleVerificationResult(result, request, callerContext, keySource)
    }

    private suspend fun handleVerificationResult(
        result: VerificationResult,
        request: TokenRequest,
        callerContext: CallerContext,
        keySource: String
    ): TokenResponse {
        return when (result) {
            is VerificationResult.Success -> {
                val claims = result.claims
                val effectivePermissions = filterPermissions(claims.permissions, request.requestedPermissions)
                val isUnrestricted = "unrestricted" in claims.clientTypes
                val boundPackageName = if (!isUnrestricted) callerContext.packageName else null
                val boundCertHash = if (!isUnrestricted) callerContext.certHash else null
                val boundOrigin = if (!isUnrestricted) callerContext.origin else null

                val grantedBy = keySource.replace(" ", "_")
                val existing = tokenManager.findExistingToken(
                    clientId = request.clientId,
                    grantedBy = grantedBy,
                    requiredPermissions = effectivePermissions,
                    boundPackageName = boundPackageName,
                    boundCertHash = boundCertHash,
                    boundOrigin = boundOrigin
                )

                val token = existing ?: tokenManager.createToken(
                    clientId = request.clientId,
                    permissions = effectivePermissions,
                    grantedBy = grantedBy,
                    duration = "day",
                    boundPackageName = boundPackageName,
                    boundCertHash = boundCertHash,
                    boundOrigin = boundOrigin
                )
                TokenResponse.Success(
                    token = token.token,
                    permissions = effectivePermissions,
                    expiresAt = token.expiresAt
                )
            }
            is VerificationResult.Error -> {
                val (code, message) = when (result.error) {
                    ServiceKeyError.INVALID_SIGNATURE -> "invalid_key" to "Invalid $keySource signature"
                    ServiceKeyError.EXPIRED -> "key_expired" to "The $keySource has expired"
                    ServiceKeyError.RESTRICTION_MISMATCH -> "restriction_mismatch" to "Caller does not match $keySource restrictions"
                }
                TokenResponse.Error(code, message)
            }
        }
    }

    private suspend fun handleUserGrant(request: TokenRequest, callerContext: CallerContext): TokenResponse {
        val requestedPerms = request.requestedPermissions ?: listOf("*")
        // Filter out super permissions unless explicitly allowed in Dashboard
        val afterSuperFilter = if (isSuperViaDialogAllowed()) {
            requestedPerms
        } else {
            requestedPerms.filter { !it.endsWith(".super") && it != "super" }
        }
        // Experimental permissions are never granted via user dialog — only via service key JWT
        val grantedPermissions = afterSuperFilter.filter {
            !it.endsWith(".experimental") && it != "experimental"
        }

        val existing = tokenManager.findExistingToken(
            clientId = request.clientId,
            grantedBy = "user",
            requiredPermissions = grantedPermissions,
            boundPackageName = callerContext.packageName,
            boundCertHash = callerContext.certHash,
            boundOrigin = callerContext.origin
        )
        if (existing != null) {
            return TokenResponse.Success(existing.token, grantedPermissions, existing.expiresAt)
        }

        // Pass filtered permissions to dialog so user sees only what will actually be granted
        val filteredRequest = request.copy(requestedPermissions = grantedPermissions.takeIf { it != listOf("*") })
        val decision = showGrantDialog(callerContext, filteredRequest)

        return when (decision) {
            GrantDecision.AllowPermanent -> {
                val token = tokenManager.createToken(
                    clientId = request.clientId,
                    permissions = grantedPermissions,
                    grantedBy = "user",
                    duration = "permanent",
                    boundPackageName = callerContext.packageName,
                    boundCertHash = callerContext.certHash,
                    boundOrigin = callerContext.origin
                )
                TokenResponse.Success(token.token, grantedPermissions, token.expiresAt)
            }
            GrantDecision.AllowDay -> {
                val token = tokenManager.createToken(
                    clientId = request.clientId,
                    permissions = grantedPermissions,
                    grantedBy = "user",
                    duration = "day",
                    boundPackageName = callerContext.packageName,
                    boundCertHash = callerContext.certHash,
                    boundOrigin = callerContext.origin
                )
                TokenResponse.Success(token.token, grantedPermissions, token.expiresAt)
            }
            GrantDecision.Deny -> {
                TokenResponse.Error("user_denied", "User denied permission")
            }
        }
    }

    /**
     * Intersects granted permissions with requested ones.
     * If requested is null, all granted permissions are returned.
     * Wildcard "*" in granted means all requested permissions are allowed.
     */
    private fun filterPermissions(granted: List<String>, requested: List<String>?): List<String> {
        if (requested == null) return granted
        if ("*" in granted) return requested
        return requested.filter { req -> granted.any { req.startsWith(it) } }
    }
}
