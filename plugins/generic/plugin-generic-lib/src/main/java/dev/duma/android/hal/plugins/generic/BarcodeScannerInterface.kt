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
 * Definer for the `barcodeScanner` interface — the unified barcode-reading surface that replaces
 * the hardcoded generic scanner wrapper. Providers (e.g. `sunmi.scanner.inner`) opt in via
 * [dev.duma.android.hal.contract.InterfaceBinding]: they trigger a scan and emit the unified
 * `barcodeScanner.onScan` event, whose `source` header identifies which scanner produced it (so a
 * client can subscribe to `barcodeScanner.onScan@sunmi.scanner.inner`).
 *
 * Naming: the id is qualified on both axes on purpose. A bare `scanner` would not say *what* is
 * scanned — RFID/NFC hardware (see `sunmi.rfid`) reads tags and is equally a "scanner" — and a bare
 * `barcode` would not say in *which direction*, since printers emit barcodes too (TSPL/ZPL label
 * jobs via the `printer` interface). A future tag-reading contract should therefore follow the same
 * pattern and be named `nfcScanner`, not `scanner` or `nfc`.
 *
 * This plugin only *registers* the contract (no hardware, no capabilities); its methods are callable
 * only while this definer is loaded.
 */
class BarcodeScannerInterface : HalPlugin {

    override val pluginId = "interface.barcodeScanner"
    override val version = 1

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[Interface] Barcode Scanner",
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        definesInterfaces = listOf(BARCODE_SCANNER_CONTRACT)
    )

    override fun initialize(pluginContext: PluginContext) {}

    // Never routed here — interface methods run on the resolved provider plugin.
    override suspend fun execute(method: String, params: String): CommandResult =
        CommandResult.unsupportedMethod(method)

    override fun setEventCallback(callback: HalPluginEventCallback?) {}

    companion object {
        private const val PERMISSION = "barcodeScanner"

        val BARCODE_SCANNER_CONTRACT = InterfaceContract(
            interfaceId = "barcodeScanner",
            version = 1,
            methods = listOf(
                MethodDescriptor(
                    "barcodeScanner.trigger",
                    "Starts a barcode scan on the active provider. The result is delivered later as a barcodeScanner.onScan event.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{"status":"scanning"}"""
                ),
                MethodDescriptor(
                    "barcodeScanner.stop",
                    "Stops scanning on the active provider.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{}"""
                )
            ),
            events = listOf(
                EventDescriptor(
                    "barcodeScanner.onScan",
                    "A barcode was decoded. The emitting scanner is carried in the event header (source), " +
                        "so clients can filter with barcodeScanner.onScan@<providerId>.",
                    PERMISSION,
                    exampleEvent = """{"data":"5901234123457","format":"EAN13"}"""
                )
            ),
            features = emptyList()
        )
    }
}
