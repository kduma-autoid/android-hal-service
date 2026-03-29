package dev.duma.android.hal.plugins.sunmi.printerx.lcd.handler

import com.sunmi.printerx.enums.Command
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.base64ToBitmap
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.error
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.requirePrinter
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.success
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.unsupportedMethod
import org.json.JSONObject

internal class LcdApiHandler {

    suspend fun handle(method: String, json: JSONObject): String {
        val (printer, err) = requirePrinter(json)
        if (err != null) return err
        val lcd = printer!!.lcdApi()

        val operation = method.removePrefix("sunmi.printerx.lcd.")
        return when (operation) {
            "config" -> {
                val command = Command.valueOf(json.getString("command"))
                lcd.config(command)
                success()
            }
            "showText" -> {
                val text = json.getString("text")
                val size = json.optInt("size", 32)
                val fill = json.optBoolean("fill", false)
                lcd.showText(text, size, fill)
                success()
            }
            "showTexts" -> {
                val textsArr = json.getJSONArray("texts")
                val alignArr = json.getJSONArray("align")
                val texts = Array(textsArr.length()) { textsArr.getString(it) }
                val align = IntArray(alignArr.length()) { alignArr.getInt(it) }
                lcd.showTexts(texts, align)
                success()
            }
            "showBitmap" -> {
                val bitmap = base64ToBitmap(json.getString("bitmap"))
                lcd.showBitmap(bitmap)
                success()
            }
            "showDigital" -> {
                val digital = json.getString("digital")
                lcd.showDigital(digital)
                success()
            }
            else -> unsupportedMethod(method)
        }
    }
}
