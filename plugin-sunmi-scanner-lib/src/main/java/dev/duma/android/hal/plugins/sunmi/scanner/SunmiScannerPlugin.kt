package dev.duma.android.hal.plugins.sunmi.scanner

import android.content.Context
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Stub implementation of Sunmi barcode scanner plugin. Returns hardcoded responses
 * simulating scan triggers. Will be replaced with real Sunmi SDK integration
 * in production. Accepts optional [Context] for hardware SDK access.
 */
class SunmiScannerPlugin(private val appContext: Context? = null) : HalPlugin {

    override val pluginId = "sunmi.scanner"
    override val version = 1

    private var callback: HalPluginEventCallback? = null

    override fun getCapabilities(): List<String> = listOf("sunmi.scanner")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor("sunmi.scanner.trigger", "Trigger barcode scan", "sunmi.scanner"),
            MethodDescriptor("sunmi.scanner.stop", "Stop scanning", "sunmi.scanner")
        ),
        events = listOf(
            EventDescriptor("sunmi.scanner.barcode", "Barcode scanned by Sunmi scanner", "sunmi.scanner")
        )
    )

    override fun initialize(context: PluginContext) {
        // Stub — no PluginContext usage needed
    }

    override suspend fun execute(method: String, params: String): String {
        return when (method) {
            "sunmi.scanner.trigger" -> """{"status":"scanning"}"""
            "sunmi.scanner.stop" -> """{"status":"idle"}"""
            else -> """{"error":"unsupported_method","method":"$method"}"""
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }
}
