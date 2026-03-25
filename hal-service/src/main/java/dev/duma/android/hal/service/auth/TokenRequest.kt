package dev.duma.android.hal.service.auth

/**
 * Request to obtain a session token. If [developerKey] is provided, it is verified as JWT.
 * If null, a user consent dialog is shown.
 */
data class TokenRequest(
    val developerKey: String?,
    val clientId: String
)
