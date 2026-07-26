package dev.duma.android.hal.contract

/**
 * Base class for [HalPlugin] implementations that enforces the descriptor guard.
 *
 * The plugin's [getDescriptor] is the single source of truth for what is invocable: any method not
 * declared in it — including experimental methods stripped from a `stable` stability-flavor build —
 * is rejected with [CommandResult.unsupportedMethod] before it can reach a handler. Because the guard
 * lives in the plugin (not only in the service registry), it holds even when a plugin is used
 * directly as a library, bypassing hal-service.
 *
 * Subclasses implement [onExecute] instead of [execute].
 */
abstract class BaseHalPlugin : HalPlugin {

    final override suspend fun execute(method: String, params: String): CommandResult {
        val descriptor = getDescriptor()
        // Invocable = a method declared in the descriptor, OR a method in the namespace of an
        // interface this plugin declares it provides (e.g. "light.*" for an InterfaceBinding("light")).
        // Interface methods live in the registered InterfaceContract, not in the provider's own
        // descriptor, so the guard admits the interface namespace and lets onExecute (and the service's
        // interface routing, which validates against the contract) handle the specific method.
        val declared = descriptor.allMethods.any { it.name == method } ||
            descriptor.interfaces.any { method.startsWith("${it.interfaceId}.") }
        if (!declared) {
            return CommandResult.unsupportedMethod(method)
        }
        return onExecute(method, params)
    }

    /** Handle a method already validated to be declared in this plugin's descriptor. */
    protected abstract suspend fun onExecute(method: String, params: String): CommandResult
}
