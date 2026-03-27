package dev.duma.android.hal.service.auth

/**
 * Request to obtain a session token. If [developerKey] is provided, it is verified as JWT.
 * If null, a user consent dialog is shown.
 * [requestedPermissions] optionally limits the granted permissions scope.
 * If null, all available permissions are granted (developer key claims or "*" for user grant).
 */
data class TokenRequest(
    val developerKey: String?,
    val clientId: String,
    val requestedPermissions: List<String>? = null
)
