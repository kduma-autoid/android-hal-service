package dev.duma.android.hal.service.auth

/**
 * User's decision from the grant permission dialog.
 * Determines token duration: permanent, day, or denied.
 */
enum class GrantDecision {
    AllowPermanent,
    AllowDay,
    Deny
}
