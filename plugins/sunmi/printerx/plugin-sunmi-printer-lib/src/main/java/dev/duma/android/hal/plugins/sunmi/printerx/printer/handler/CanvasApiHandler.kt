package dev.duma.android.hal.plugins.sunmi.printerx.printer.handler

import com.sunmi.printerx.enums.Shape
import com.sunmi.printerx.style.AreaStyle
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.awaitPrintResult
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.base64ToBitmap
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import org.json.JSONObject

internal class CanvasApiHandler {

    suspend fun handle(method: String, json: JSONObject): CommandResult {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val canvas = printer!!.canvasApi()

        val operation = method.removePrefix("sunmi.printerx.printer.canvas.")
        return when (operation) {
            "initCanvas" -> {
                val style = if (json.has("label") && json.getBoolean("label")) {
                    buildLabelStyle(json)
                } else {
                    buildBaseStyle(json)
                }
                canvas.initCanvas(style)
                CommandResult.Success()
            }
            "renderText" -> {
                val text = json.getString("text")
                val style = if (json.has("style")) buildTextStyle(json.getJSONObject("style")) else buildTextStyle(JSONObject())
                canvas.renderText(text, style)
                CommandResult.Success()
            }
            "renderBarCode" -> {
                val code = json.getString("code")
                val style = if (json.has("style")) buildBarcodeStyle(json.getJSONObject("style")) else buildBarcodeStyle(JSONObject())
                canvas.renderBarCode(code, style)
                CommandResult.Success()
            }
            "renderQrCode" -> {
                val code = json.getString("code")
                val style = if (json.has("style")) buildQrStyle(json.getJSONObject("style")) else buildQrStyle(JSONObject())
                canvas.renderQrCode(code, style)
                CommandResult.Success()
            }
            "renderBitmap" -> {
                val bitmap = base64ToBitmap(json.getString("bitmap"))
                val style = if (json.has("style")) buildBitmapStyle(json.getJSONObject("style")) else buildBitmapStyle(JSONObject())
                canvas.renderBitmap(bitmap, style)
                CommandResult.Success()
            }
            "renderArea" -> {
                val styleJson = json.optJSONObject("style") ?: json
                val areaStyle = AreaStyle.getStyle()
                if (styleJson.has("shape")) areaStyle.setStyle(Shape.valueOf(styleJson.getString("shape")))
                if (styleJson.has("posX")) areaStyle.setPosX(styleJson.getInt("posX"))
                if (styleJson.has("posY")) areaStyle.setPosY(styleJson.getInt("posY"))
                if (styleJson.has("width")) areaStyle.setWidth(styleJson.getInt("width"))
                if (styleJson.has("height")) areaStyle.setHeight(styleJson.getInt("height"))
                if (styleJson.has("endX")) areaStyle.setEndX(styleJson.getInt("endX"))
                if (styleJson.has("endY")) areaStyle.setEndY(styleJson.getInt("endY"))
                if (styleJson.has("thick")) areaStyle.setThick(styleJson.getInt("thick"))
                canvas.renderArea(areaStyle)
                CommandResult.Success()
            }
            "printCanvas" -> {
                val count = json.optInt("count", 1)
                // Synchronous: wait for PrintResult
                awaitPrintResult { result ->
                    canvas.printCanvas(count, result)
                }
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
