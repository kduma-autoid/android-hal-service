package dev.duma.android.hal.transport.core

/**
 * Information about the client making a request. Each transport populates relevant fields:
 * AIDL/Intent provides packageName, certHash, callingUid; WS/HTTP provides origin, remoteAddress.
 * Used by the auth system for token validation and binding enforcement.
 */
data class CallerContext(
    val transport: String,
    val packageName: String? = null,
    val certHash: String? = null,
    val origin: String? = null,
    val remoteAddress: String? = null,
    val callingUid: Int? = null
)
