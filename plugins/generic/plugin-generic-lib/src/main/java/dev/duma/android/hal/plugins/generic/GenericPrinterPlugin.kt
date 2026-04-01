package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Generic printer abstraction plugin. Delegates print commands to the first available
 * vendor-specific printer plugin (Sunmi, Zebra, Chainway) via PluginContext.
 * Must be in-process — requires PluginContext for inter-plugin communication.
 */
class GenericPrinterPlugin : HalPlugin {

    override val pluginId = "printer"
    override val version = 1

    private var ctx: PluginContext? = null

    companion object {
        private val VENDOR_PRINTERS = listOf("sunmi.printer", "zebra.printer", "chainway.printer")
    }

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = listOf("printer")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "[DEMO] Printer",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "printer.print",
                "Print using available printer",
                "printer",
                exampleParameters = """{"text":"Hello World"}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "printer.status",
                "Get printer status",
                "printer",
                exampleParameters = "{}",
                exampleOutput = """{}"""
            )
        ),
        events = emptyList()
    )

    override fun initialize(pluginContext: PluginContext) {
        this.ctx = pluginContext
    }

    override suspend fun execute(method: String, params: String): CommandResult {
        val context = ctx ?: return CommandResult.unavailable("Plugin not initialized")

        val operation = method.removePrefix("printer.")
        return when (operation) {
            "print", "status" -> {
                val vendorMethod = findVendorMethod(context, operation)
                    ?: return CommandResult.unavailable("No vendor printer plugin available")
                context.execute(vendorMethod, params)
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        // Generic printer does not emit events
    }

    private fun findVendorMethod(context: PluginContext, operation: String): String? {
        for (vendor in VENDOR_PRINTERS) {
            if (context.hasCapability(vendor)) {
                return "$vendor.$operation"
            }
        }
        return null
    }
}
