package dev.duma.android.hal.plugins.sunmi.printerx.printer.handler

import com.sunmi.printerx.enums.DividingLine
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.awaitPrintResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.base64ToBitmap
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import org.json.JSONObject

internal class LineApiHandler {

    suspend fun handle(method: String, json: JSONObject): CommandResult {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val line = printer!!.lineApi()

        val operation = method.removePrefix("sunmi.printerx.printer.line.")
        return when (operation) {
            "initLine" -> {
                val style = buildBaseStyle(json)
                line.initLine(style)
                CommandResult.Success()
            }
            "addText" -> {
                val text = json.getString("text")
                val style = if (json.has("style")) buildTextStyle(json.getJSONObject("style")) else buildTextStyle(JSONObject())
                line.addText(text, style)
                CommandResult.Success()
            }
            "printText" -> {
                val text = json.getString("text")
                val style = if (json.has("style")) buildTextStyle(json.getJSONObject("style")) else buildTextStyle(JSONObject())
                line.printText(text, style)
                CommandResult.Success()
            }
            "printTexts" -> {
                val textsArr = json.getJSONArray("texts")
                val colsWidthArr = json.getJSONArray("colsWidth")
                val stylesArr = json.optJSONArray("styles")

                val texts = Array(textsArr.length()) { textsArr.getString(it) }
                val colsWidth = IntArray(colsWidthArr.length()) { colsWidthArr.getInt(it) }
                val styles = if (stylesArr != null) {
                    Array(stylesArr.length()) { buildTextStyle(stylesArr.getJSONObject(it)) }
                } else {
                    Array(texts.size) { buildTextStyle(JSONObject()) }
                }
                line.printTexts(texts, colsWidth, styles)
                CommandResult.Success()
            }
            "printBarCode" -> {
                val code = json.getString("code")
                val style = if (json.has("style")) buildBarcodeStyle(json.getJSONObject("style")) else buildBarcodeStyle(JSONObject())
                line.printBarCode(code, style)
                CommandResult.Success()
            }
            "printQrCode" -> {
                val code = json.getString("code")
                val style = if (json.has("style")) buildQrStyle(json.getJSONObject("style")) else buildQrStyle(JSONObject())
                line.printQrCode(code, style)
                CommandResult.Success()
            }
            "printBitmap" -> {
                val bitmap = base64ToBitmap(json.getString("bitmap"))
                val style = if (json.has("style")) buildBitmapStyle(json.getJSONObject("style")) else buildBitmapStyle(JSONObject())
                line.printBitmap(bitmap, style)
                CommandResult.Success()
            }
            "printDividingLine" -> {
                val dividingLine = DividingLine.valueOf(json.getString("style"))
                val offset = json.optInt("offset", 0)
                line.printDividingLine(dividingLine, offset)
                CommandResult.Success()
            }
            "autoOut" -> {
                line.autoOut()
                CommandResult.Success()
            }
            "enableTransMode" -> {
                line.enableTransMode(json.getBoolean("enable"))
                CommandResult.Success()
            }
            "printTrans" -> {
                // Synchronous: wait for PrintResult
                awaitPrintResult { result ->
                    line.printTrans(result)
                }
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
