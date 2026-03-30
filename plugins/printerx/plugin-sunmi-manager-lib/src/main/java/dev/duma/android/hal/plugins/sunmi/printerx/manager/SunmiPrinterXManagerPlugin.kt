package dev.duma.android.hal.plugins.sunmi.printerx.manager

import android.content.Context
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.BasePrinterXPlugin
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.SharedPrinterManager
import org.json.JSONArray
import org.json.JSONObject

class SunmiPrinterXManagerPlugin(context: Context? = null) : BasePrinterXPlugin(context) {

    override val pluginId = "sunmi.printerx.manager"
    override val version = 1

    private val discoveryListener = SharedPrinterManager.DiscoveryListener { defaultPrinterId, allPrinterIds ->
        val arr = JSONArray().apply { allPrinterIds.forEach { put(it) } }

        emitEvent(
            "sunmi.printerx.manager.printersUpdated",
            JSONObject().put("printers", arr).toString()
        )
        emitEvent(
            "sunmi.printerx.manager.defaultPrinterChanged",
            JSONObject().put("printerId", defaultPrinterId ?: JSONObject.NULL).toString()
        )
    }

    override fun initialize(pluginContext: PluginContext) {
        super.initialize(pluginContext)
        SharedPrinterManager.addDiscoveryListener(discoveryListener)
    }

    override fun getCapabilities() = listOf("sunmi.printerx.manager")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi PrinterX Manager",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.printerx.manager.getPrinters",
                "Gets list of available printers and default printer ID.",
                "sunmi.printerx.manager",
                exampleParameters = "{}",
                exampleOutput = """{"status":"ok","printers":["printer1","printer2"],"defaultPrinter":"printer1"}"""
            )
        ),
        events = listOf(
            EventDescriptor("sunmi.printerx.manager.defaultPrinterChanged", "Default printer updated.", "sunmi.printerx.manager"),
            EventDescriptor("sunmi.printerx.manager.printersUpdated", "Printer list updated.", "sunmi.printerx.manager")
        )
    )

    override suspend fun handleExecute(method: String, params: String, json: JSONObject): String {
        return when (method) {
            "sunmi.printerx.manager.getPrinters" -> SharedPrinterManager.buildGetPrintersResponse()
            else -> unsupportedMethod(method)
        }
    }
}
