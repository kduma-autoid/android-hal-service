package dev.duma.android.hal.plugins.sunmi.printerx.drawer.handler

import dev.duma.android.hal.plugins.sunmi.printerx.sdk.awaitPrintResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.success
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.unsupportedMethod
import org.json.JSONObject

internal class CashDrawerApiHandler {

    suspend fun handle(method: String, json: JSONObject): String {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val drawer = printer!!.cashDrawerApi()

        return when (method) {
            "sunmi.printerx.drawer.open" -> {
                awaitPrintResult { result ->
                    drawer.open(result)
                }
            }
            "sunmi.printerx.drawer.isOpen" -> {
                success(drawer.isOpen())
            }
            else -> unsupportedMethod(method)
        }
    }
}
