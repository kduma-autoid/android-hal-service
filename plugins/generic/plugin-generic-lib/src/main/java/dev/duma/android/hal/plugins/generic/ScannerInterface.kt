package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Definer for the `scanner` interface — the unified barcode-scanner surface that replaces the
 * hardcoded generic scanner wrapper. Providers (e.g. `sunmi.scanner.inner`) opt in via
 * [dev.duma.android.hal.contract.InterfaceBinding]: they trigger a scan and emit the unified
 * `scanner.onScan` event, whose `source` header identifies which scanner produced it (so a client
 * can subscribe to `scanner.onScan@sunmi.scanner.inner`).
 *
 * This plugin only *registers* the contract (no hardware, no capabilities); its methods are callable
 * only while this definer is loaded.
 */
class ScannerInterface : HalPlugin {

    override val pluginId = "interface.scanner"
    override val version = 1

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[Interface] Scanner",
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        definesInterfaces = listOf(SCANNER_CONTRACT)
    )

    override fun initialize(pluginContext: PluginContext) {}

    // Never routed here — interface methods run on the resolved provider plugin.
    override suspend fun execute(method: String, params: String): CommandResult =
        CommandResult.unsupportedMethod(method)

    override fun setEventCallback(callback: HalPluginEventCallback?) {}

    companion object {
        private const val PERMISSION = "scanner"

        val SCANNER_CONTRACT = InterfaceContract(
            interfaceId = "scanner",
            version = 1,
            methods = listOf(
                MethodDescriptor(
                    "scanner.trigger",
                    "Starts a barcode scan on the active provider. The result is delivered later as a scanner.onScan event.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{"status":"scanning"}"""
                ),
                MethodDescriptor(
                    "scanner.stop",
                    "Stops scanning on the active provider.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{}"""
                )
            ),
            events = listOf(
                EventDescriptor(
                    "scanner.onScan",
                    "A barcode was decoded. The emitting scanner is carried in the event header (source), " +
                        "so clients can filter with scanner.onScan@<providerId>.",
                    PERMISSION,
                    exampleEvent = """{"data":"5901234123457","format":"EAN13"}"""
                )
            ),
            features = emptyList()
        )
    }
}
