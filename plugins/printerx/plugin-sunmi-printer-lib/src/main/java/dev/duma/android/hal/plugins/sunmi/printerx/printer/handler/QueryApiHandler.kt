package dev.duma.android.hal.plugins.sunmi.printerx.printer.handler

import com.sunmi.printerx.enums.PrinterInfo
import com.sunmi.printerx.enums.PrinterParam
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.success
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.unsupportedMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class QueryApiHandler {

    suspend fun handle(method: String, json: JSONObject): String {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val query = printer!!.queryApi()

        return when (method) {
            "sunmi.printerx.printer.query.getStatus" -> withContext(Dispatchers.IO) {
                success(query.status.name)
            }
            "sunmi.printerx.printer.query.getInfo" -> {
                val infoKey = json.getString("info")
                val info = PrinterInfo.valueOf(infoKey)
                success(query.getInfo(info))
            }
            "sunmi.printerx.printer.query.getParam" -> {
                val paramKey = json.getString("param")
                val param = PrinterParam.valueOf(paramKey)
                success(query.getParam(param))
            }
            // getAccessoryInfo not available in classes.jar of printerx:1.0.17
            else -> unsupportedMethod(method)
        }
    }
}
