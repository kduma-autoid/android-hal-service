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
        if (getDescriptor().allMethods.none { it.name == method }) {
            return CommandResult.unsupportedMethod(method)
        }
        return onExecute(method, params)
    }

    /** Handle a method already validated to be declared in this plugin's descriptor. */
    protected abstract suspend fun onExecute(method: String, params: String): CommandResult
}
