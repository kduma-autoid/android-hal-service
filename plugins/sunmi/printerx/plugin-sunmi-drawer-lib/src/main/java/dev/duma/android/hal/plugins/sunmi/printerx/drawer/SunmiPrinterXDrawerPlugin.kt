package dev.duma.android.hal.plugins.sunmi.printerx.drawer

import android.content.Context
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.printerx.drawer.handler.CashDrawerApiHandler
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.BasePrinterXPlugin
import org.json.JSONObject

class SunmiPrinterXDrawerPlugin(context: Context? = null) : BasePrinterXPlugin(context) {

    override val pluginId = "sunmi.printerx.drawer"
    override val version = 1

    private val cashDrawerHandler by lazy { CashDrawerApiHandler() }

    override fun getCapabilities() = listOf("sunmi.printerx.drawer")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi: Cash Drawer",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.printerx.drawer.open",
                "Opens cash drawer. Synchronous — waits for hardware confirmation.",
                "sunmi.printerx.drawer",
                exampleParameters = "{}",
                exampleOutput = """{"status":"ok","resultCode":0,"message":""}"""
            ),
            MethodDescriptor(
                "sunmi.printerx.drawer.isOpen",
                "Gets cash drawer open/close status.",
                "sunmi.printerx.drawer",
                exampleParameters = """{"printerId":""}""",
                exampleOutput = """{"status":"ok","result":false}"""
            )
        ),
        events = emptyList()
    )

    override suspend fun handleExecute(method: String, params: String, json: JSONObject): String {
        // cashDrawer.open skips mutex (long-running async operation)
        return when {
            method == "sunmi.printerx.drawer.open" -> cashDrawerHandler.handle(method, json)
            else -> guardedExecute { cashDrawerHandler.handle(method, json) }
        }
    }
}
