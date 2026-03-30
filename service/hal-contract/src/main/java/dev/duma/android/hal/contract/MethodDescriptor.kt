package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * Description of a single method exposed by a plugin (e.g. "sunmi.printer.print").
 * Contains the name, human-readable description, and required permission to invoke it.
 */
@Serializable
data class MethodDescriptor(
    val name: String,
    val description: String,
    val requiredPermission: String,
    val superRequired: Boolean = false,
    val experimental: Boolean = false,
    val exampleParameters: String,
    val exampleOutput: String
)
