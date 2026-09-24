package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.InterfaceFeature
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Definer for the `printer` interface — the unified printing surface that replaces the hardcoded
 * generic printer wrapper. Providers (e.g. `sunmi.printerx.printer`) opt in via
 * [dev.duma.android.hal.contract.InterfaceBinding] and advertise, per feature, which command formats
 * they actually support.
 *
 * This plugin only *registers* the contract (no hardware, no capabilities); its methods are callable
 * only while this definer is loaded.
 */
class PrinterInterface : HalPlugin {

    override val pluginId = "interface.printer"
    override val version = 1

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[Interface] Printer",
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        definesInterfaces = listOf(PRINTER_CONTRACT)
    )

    override fun initialize(pluginContext: PluginContext) {}

    // Never routed here — interface methods run on the resolved provider plugin.
    override suspend fun execute(method: String, params: String): CommandResult =
        CommandResult.unsupportedMethod(method)

    override fun setEventCallback(callback: HalPluginEventCallback?) {}

    companion object {
        private const val PERMISSION = "printer"

        val PRINTER_CONTRACT = InterfaceContract(
            interfaceId = "printer",
            version = 1,
            methods = listOf(
                MethodDescriptor(
                    "printer.printEscPos",
                    "Sends raw ESC/POS command bytes (base64) to the printer. Only on providers advertising the 'escpos' feature.",
                    PERMISSION,
                    exampleParameters = """{"data":"G0A="}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "printer.printTspl",
                    "Sends raw TSPL command bytes (base64) to the printer. Only on providers advertising the 'tspl' feature.",
                    PERMISSION,
                    exampleParameters = """{"data":"U0laRSA0LDM="}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "printer.printZpl",
                    "Sends raw ZPL command bytes (base64) to the printer. Only on providers advertising the 'zpl' feature.",
                    PERMISSION,
                    exampleParameters = """{"data":"XlhBXlha"}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "printer.printImage",
                    "Prints a bitmap (base64 PNG/JPEG) with optional style. Only on providers advertising the 'image' feature.",
                    PERMISSION,
                    exampleParameters = """{"bitmap":"iVBORw0KGgo=","style":{"algorithm":"BINARIZATION","value":200}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "printer.cut",
                    "Cuts the paper. On providers whose hardware only supports a combined feed+cut, this feeds and cuts. Only on providers advertising the 'cut' feature.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{}"""
                )
            ),
            events = emptyList(),
            features = listOf(
                InterfaceFeature("escpos", "Supports raw ESC/POS command bytes.", methods = listOf("printer.printEscPos")),
                InterfaceFeature("tspl", "Supports raw TSPL command bytes.", methods = listOf("printer.printTspl")),
                InterfaceFeature("zpl", "Supports raw ZPL command bytes.", methods = listOf("printer.printZpl")),
                InterfaceFeature("image", "Supports bitmap/image printing.", methods = listOf("printer.printImage")),
                InterfaceFeature("cut", "Supports cutting the paper.", methods = listOf("printer.cut"))
            )
        )
    }
}
