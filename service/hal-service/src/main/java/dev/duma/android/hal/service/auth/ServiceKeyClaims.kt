package dev.duma.android.hal.service.auth

/**
 * Claims extracted from a valid service key JWT. Contains the permissions granted,
 * client type, and optional restrictions (package name, cert hash, allowed origins).
 */
data class ServiceKeyClaims(
    val permissions: List<String>,
    val clientTypes: List<String>,
    val subject: String? = null,
    val clientId: String? = null,
    val packageName: String? = null,
    val certHash: String? = null,
    val origins: List<String>? = null
)
