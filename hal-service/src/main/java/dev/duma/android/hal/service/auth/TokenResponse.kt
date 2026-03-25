package dev.duma.android.hal.service.auth

/**
 * Response from [AuthManager.requestToken]. Either a [Success] with token details
 * or an [Error] with a specific error code and message.
 */
sealed class TokenResponse {
    data class Success(
        val token: String,
        val permissions: List<String>,
        val expiresAt: Long?
    ) : TokenResponse()

    data class Error(
        val code: String,
        val message: String
    ) : TokenResponse()
}
