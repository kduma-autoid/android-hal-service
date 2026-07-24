package dev.duma.android.hal.plugins.sunmi.printer

import android.content.Context
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.stripExperimental

/**
 * Stub implementation of Sunmi thermal printer plugin. Returns hardcoded responses
 * simulating print jobs and printer status. Will be replaced with real Sunmi SDK
 * integration in production. Accepts optional [Context] for hardware SDK access.
 */
class SunmiPrinterPlugin(private val appContext: Context? = null) : HalPlugin {

    override val pluginId = "sunmi.printer"
    override val version = 1

    private var callback: HalPluginEventCallback? = null

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = listOf("sunmi.printer")

    override fun getDescriptor() = fullDescriptor().let {
        if (BuildConfig.WITH_EXPERIMENTAL) it else it.stripExperimental()
    }

    private fun fullDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[DEMO] Sunmi Printer",
        version = version,
        capabilities = getCapabilities(),
        experimental = true,
        groups = listOf(
            DescriptorGroup(
                name = "Printing",
                methods = listOf(
                    MethodDescriptor(
                        "sunmi.printer.print",
                        "Print receipt using Sunmi printer",
                        "sunmi.printer",
                        exampleParameters = """{"text":"Hello World"}""",
                        exampleOutput = """{"jobId":"job_1234567890","status":"queued"}"""
                    ),
                ),
            ),
            DescriptorGroup(
                name = "Statuses",
                methods = listOf(
                    MethodDescriptor(
                        "sunmi.printer.status",
                        "Get Sunmi printer status",
                        "sunmi.printer",
                        exampleParameters = "{}",
                        exampleOutput = """{"status":"idle","paperLevel":"ok"}"""
                    ),
                ),
            ),
        )
    )

    override fun initialize(pluginContext: PluginContext) {
        // Stub — no PluginContext usage needed
    }

    override suspend fun execute(method: String, params: String): CommandResult {
        return when (method) {
            "sunmi.printer.print" -> CommandResult.Success("""{"jobId":"job_${System.currentTimeMillis()}","status":"queued"}""")
            "sunmi.printer.status" -> CommandResult.Success("""{"status":"idle","paperLevel":"ok"}""")
            else -> CommandResult.unsupportedMethod(method)
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }
}
