package dev.duma.android.hal.plugins.sunmi.printerx.printer.handler

import com.sunmi.printerx.enums.FileDuplex
import com.sunmi.printerx.enums.Rotate
import com.sunmi.printerx.style.FileStyle
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.awaitPrintResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import org.json.JSONObject

internal class FileApiHandler {

    suspend fun handle(method: String, json: JSONObject): CommandResult {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val file = printer!!.fileApi()
        val path = json.getString("path")

        return when (method) {
            "sunmi.printerx.printer.file.printFile" -> {
                val fileStyle = FileStyle.getStyle()
                if (json.has("copies")) fileStyle.setFileCopies(json.getInt("copies"))
                if (json.has("duplex")) fileStyle.setFileDuplex(FileDuplex.valueOf(json.getString("duplex")))
                if (json.has("rotate")) fileStyle.setFileRotate(Rotate.valueOf(json.getString("rotate")))
                if (json.has("collate")) fileStyle.setFileCollate(json.getBoolean("collate"))
                if (json.has("pageStart")) fileStyle.setFileStart(json.getInt("pageStart"))
                if (json.has("pageEnd")) fileStyle.setFileEnd(json.getInt("pageEnd"))

                // Synchronous: wait for PrintResult
                awaitPrintResult { result ->
                    file.printFile(path, fileStyle, result)
                }
            }
            "sunmi.printerx.printer.file.printFileSimple" -> {
                // Synchronous: wait for PrintResult, no FileStyle
                awaitPrintResult { result ->
                    file.printFile(path, result)
                }
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
