package dev.duma.android.hal.plugins.sunmi.printerx.printer.handler

import android.util.Base64
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import org.json.JSONObject

internal class CommandApiHandler {

    suspend fun handle(method: String, json: JSONObject): CommandResult {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val data = Base64.decode(json.getString("data"), Base64.DEFAULT)

        return when (method) {
            "sunmi.printerx.printer.command.sendEscCommand" -> {
                printer!!.commandApi().sendEscCommand(data)
                CommandResult.Success()
            }
            "sunmi.printerx.printer.command.sendTsplCommand" -> {
                printer!!.commandApi().sendTsplCommand(data)
                CommandResult.Success()
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
