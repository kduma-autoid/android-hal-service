package dev.duma.android.hal.service.auth

/**
 * Error types returned when service key JWT verification fails.
 * Maps to specific error codes in the auth response.
 */
enum class ServiceKeyError {
    INVALID_SIGNATURE,
    EXPIRED,
    RESTRICTION_MISMATCH
}
