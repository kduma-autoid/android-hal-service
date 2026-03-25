package dev.duma.android.hal.service.auth

import dev.duma.android.hal.transport.core.CallerContext

/**
 * Orchestrates the requestToken flow: verifies developer key JWT if provided,
 * or delegates to user consent dialog. Creates session tokens via [TokenManager].
 * Central entry point for all authorization requests from any transport.
 */
class AuthManager(
    private val tokenManager: TokenManager,
    private val developerKeyVerifier: DeveloperKeyVerifier,
    private val showGrantDialog: suspend (CallerContext, TokenRequest) -> GrantDecision
) {
    suspend fun requestToken(request: TokenRequest, callerContext: CallerContext): TokenResponse {
        if (request.developerKey != null) {
            return handleDeveloperKey(request, callerContext)
        }
        return handleUserGrant(request, callerContext)
    }

    private suspend fun handleDeveloperKey(request: TokenRequest, callerContext: CallerContext): TokenResponse {
        val result = developerKeyVerifier.verify(request.developerKey!!, callerContext)

        return when (result) {
            is VerificationResult.Success -> {
                val claims = result.claims
                val token = tokenManager.createToken(
                    clientId = request.clientId,
                    permissions = claims.permissions,
                    grantedBy = "developer_key",
                    duration = "permanent",
                    boundPackageName = if (claims.clientType == "android") callerContext.packageName else null,
                    boundCertHash = if (claims.clientType == "android") callerContext.certHash else null,
                    boundOrigin = if (claims.clientType == "web") callerContext.origin else null
                )
                TokenResponse.Success(
                    token = token.token,
                    permissions = claims.permissions,
                    expiresAt = token.expiresAt
                )
            }
            is VerificationResult.Error -> {
                val (code, message) = when (result.error) {
                    DeveloperKeyError.INVALID_SIGNATURE -> "invalid_developer_key" to "Invalid developer key signature"
                    DeveloperKeyError.EXPIRED -> "developer_key_expired" to "Developer key has expired"
                    DeveloperKeyError.RESTRICTION_MISMATCH -> "restriction_mismatch" to "Caller does not match developer key restrictions"
                }
                TokenResponse.Error(code, message)
            }
        }
    }

    private suspend fun handleUserGrant(request: TokenRequest, callerContext: CallerContext): TokenResponse {
        val decision = showGrantDialog(callerContext, request)

        return when (decision) {
            GrantDecision.AllowPermanent -> {
                val token = tokenManager.createToken(
                    clientId = request.clientId,
                    permissions = listOf("*"),
                    grantedBy = "user_permanent",
                    duration = "permanent",
                    boundPackageName = callerContext.packageName,
                    boundCertHash = callerContext.certHash,
                    boundOrigin = callerContext.origin
                )
                TokenResponse.Success(token.token, listOf("*"), token.expiresAt)
            }
            GrantDecision.AllowDay -> {
                val token = tokenManager.createToken(
                    clientId = request.clientId,
                    permissions = listOf("*"),
                    grantedBy = "user_day",
                    duration = "day",
                    boundPackageName = callerContext.packageName,
                    boundCertHash = callerContext.certHash,
                    boundOrigin = callerContext.origin
                )
                TokenResponse.Success(token.token, listOf("*"), token.expiresAt)
            }
            GrantDecision.Deny -> {
                TokenResponse.Error("user_denied", "User denied permission")
            }
        }
    }
}
