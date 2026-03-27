package dev.duma.android.hal.plugins.sunmi.scanner

import android.content.Context
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random

/**
 * Stub implementation of Sunmi barcode scanner plugin. Returns hardcoded responses
 * simulating scan triggers. Will be replaced with real Sunmi SDK integration
 * in production. Accepts optional [Context] for hardware SDK access.
 */
class SunmiScannerPlugin(private val appContext: Context? = null) : HalPlugin {

    override val pluginId = "sunmi.scanner"
    override val version = 1

    private var callback: HalPluginEventCallback? = null
    private var demoTimer: Timer? = null

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = listOf("sunmi.scanner")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[DEMO] Sunmi Scanner",
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
        // Stub — timer started by trigger, stopped by stop
    }

    override suspend fun execute(method: String, params: String): String {
        return when (method) {
            "sunmi.scanner.trigger" -> {
                startDemoTimer()
                """{"status":"scanning"}"""
            }
            "sunmi.scanner.stop" -> {
                stopDemoTimer()
                """{"status":"idle"}"""
            }
            else -> """{"error":"unsupported_method","method":"$method"}"""
        }
    }

    private fun startDemoTimer() {
        stopDemoTimer()
        demoTimer = fixedRateTimer("demo-scanner", daemon = true, initialDelay = 30_000L, period = 30_000L) {
            val barcode = buildString {
                val prefixes = listOf("590", "978", "200", "400")
                append(prefixes.random())
                repeat(10) { append(Random.nextInt(10)) }
            }
            val formats = listOf("EAN13", "CODE128", "QR_CODE", "UPC_A", "DATA_MATRIX")
            callback?.onEvent("sunmi.scanner.barcode", """{"data":"$barcode","format":"${formats.random()}"}""")
        }
    }

    private fun stopDemoTimer() {
        demoTimer?.cancel()
        demoTimer = null
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }
}
