package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Generic scanner abstraction plugin. Delegates scan commands to vendor-specific
 * scanner plugins and transforms vendor events into unified format
 * (e.g. "sunmi.scanner.barcode" -> "scanner.barcode"). Must be in-process.
 */
class GenericScannerPlugin : HalPlugin {

    override val pluginId = "scanner"
    override val version = 1

    private var ctx: PluginContext? = null

    companion object {
        private val VENDOR_SCANNERS = listOf("sunmi.scanner", "zebra.scanner", "chainway.scanner")
        private val VENDOR_PREFIXES = listOf("sunmi", "zebra", "chainway")
    }

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = listOf("scanner")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[DEMO] Scanner",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "scanner.trigger",
                "Trigger barcode scan",
                "scanner",
                exampleParameters = "{}",
                exampleOutput = """{"status":"scanning"}"""
            ),
            MethodDescriptor(
                "scanner.stop",
                "Stop scanning",
                "scanner",
                exampleParameters = "{}",
                exampleOutput = """{"status":"ok"}"""
            )
        ),
        events = listOf(
            EventDescriptor(
                "scanner.barcode",
                "Barcode scanned (unified)",
                "scanner",
                exampleEvent = """{"data":"5901234123457","format":"EAN13"}"""
            )
        )
    )

    override fun initialize(pluginContext: PluginContext) {
        this.ctx = pluginContext

        // Register event listeners for vendor -> unified event transformation
        for (vendor in VENDOR_PREFIXES) {
            pluginContext.onEvent("$vendor.scanner.*") { event, data ->
                // "sunmi.scanner.barcode" -> "scanner.barcode"
                val unifiedEvent = event.replaceFirst("$vendor.", "")
                pluginContext.emitEvent(unifiedEvent, data)
            }
        }
    }

    override suspend fun execute(method: String, params: String): CommandResult {
        val context = ctx ?: return CommandResult.unavailable("Plugin not initialized")

        val operation = method.removePrefix("scanner.")
        return when (operation) {
            "trigger", "stop" -> {
                val vendorMethod = findVendorMethod(context, operation)
                    ?: return CommandResult.unavailable("No vendor scanner plugin available")
                context.execute(vendorMethod, params)
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        // Events are emitted via PluginContext, not direct callback
    }

    private fun findVendorMethod(context: PluginContext, operation: String): String? {
        for (vendor in VENDOR_SCANNERS) {
            if (context.hasCapability(vendor)) {
                return "$vendor.$operation"
            }
        }
        return null
    }
}
