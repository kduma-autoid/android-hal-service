package dev.duma.android.hal.service.auth

/**
 * Error types returned when developer key JWT verification fails.
 * Maps to specific error codes in the auth response.
 */
enum class DeveloperKeyError {
    INVALID_SIGNATURE,
    EXPIRED,
    RESTRICTION_MISMATCH
}
