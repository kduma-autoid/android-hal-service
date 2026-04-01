package dev.duma.android.hal.service.auth

/**
 * Result of service key JWT verification. Either [Success] with extracted claims
 * or [Error] with a specific failure reason.
 */
sealed class VerificationResult {
    data class Success(val claims: ServiceKeyClaims) : VerificationResult()
    data class Error(val error: ServiceKeyError) : VerificationResult()
}
