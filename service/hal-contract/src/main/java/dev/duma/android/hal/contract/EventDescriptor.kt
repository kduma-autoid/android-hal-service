package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * Description of a single event emitted by a plugin (e.g. "sunmi.scanner.barcode").
 * Contains the name, human-readable description, and required permission to subscribe.
 */
@Serializable
data class EventDescriptor(
    val name: String,
    val description: String,
    val requiredPermission: String,
    val exampleEvent: String? = null
)
