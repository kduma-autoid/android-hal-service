package dev.duma.android.hal.plugins.sunmi.printerx.printer.handler

import android.util.Base64
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.success
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.unsupportedMethod
import org.json.JSONObject

internal class CommandApiHandler {

    suspend fun handle(method: String, json: JSONObject): String {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val data = Base64.decode(json.getString("data"), Base64.DEFAULT)

        return when (method) {
            "sunmi.printerx.printer.command.sendEscCommand" -> {
                printer!!.commandApi().sendEscCommand(data)
                success()
            }
            "sunmi.printerx.printer.command.sendTsplCommand" -> {
                printer!!.commandApi().sendTsplCommand(data)
                success()
            }
            else -> unsupportedMethod(method)
        }
    }
}
