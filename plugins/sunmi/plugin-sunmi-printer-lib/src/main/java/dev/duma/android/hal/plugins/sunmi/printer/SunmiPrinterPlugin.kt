package dev.duma.android.hal.plugins.sunmi.printer

import android.content.Context
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Stub implementation of Sunmi thermal printer plugin. Returns hardcoded responses
 * simulating print jobs and printer status. Will be replaced with real Sunmi SDK
 * integration in production. Accepts optional [Context] for hardware SDK access.
 */
class SunmiPrinterPlugin(private val appContext: Context? = null) : HalPlugin {

    override val pluginId = "sunmi.printer"
    override val version = 1

    private var callback: HalPluginEventCallback? = null

    override fun getCapabilities(): List<String> = listOf("sunmi.printer")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor("sunmi.printer.print", "Print receipt using Sunmi printer", "sunmi.printer"),
            MethodDescriptor("sunmi.printer.status", "Get Sunmi printer status", "sunmi.printer")
        ),
        events = emptyList()
    )

    override fun initialize(context: PluginContext) {
        // Stub — no PluginContext usage needed
    }

    override suspend fun execute(method: String, params: String): String {
        return when (method) {
            "sunmi.printer.print" -> """{"jobId":"job_${System.currentTimeMillis()}","status":"queued"}"""
            "sunmi.printer.status" -> """{"status":"idle","paperLevel":"ok"}"""
            else -> """{"error":"unsupported_method","method":"$method"}"""
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }
}
