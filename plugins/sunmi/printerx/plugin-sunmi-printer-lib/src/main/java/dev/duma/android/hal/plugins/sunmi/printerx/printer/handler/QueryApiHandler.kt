package dev.duma.android.hal.plugins.sunmi.printerx.printer.handler

import com.sunmi.printerx.enums.PrinterInfo
import com.sunmi.printerx.enums.PrinterParam
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class QueryApiHandler {

    suspend fun handle(method: String, json: JSONObject): CommandResult {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val query = printer!!.queryApi()

        return when (method) {
            "sunmi.printerx.printer.query.getStatus" -> withContext(Dispatchers.IO) {
                CommandResult.Success(JSONObject().put("result", query.status.name).toString())
            }
            "sunmi.printerx.printer.query.getInfo" -> {
                val infoKey = json.getString("info")
                val info = PrinterInfo.valueOf(infoKey)
                CommandResult.Success(JSONObject().put("result", query.getInfo(info)).toString())
            }
            "sunmi.printerx.printer.query.getParam" -> {
                val paramKey = json.getString("param")
                val param = PrinterParam.valueOf(paramKey)
                CommandResult.Success(JSONObject().put("result", query.getParam(param)).toString())
            }
            // getAccessoryInfo not available in classes.jar of printerx:1.0.17
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
