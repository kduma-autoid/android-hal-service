package dev.duma.android.hal.plugins.sunmi.printerx.lcd

import android.content.Context
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.printerx.lcd.handler.LcdApiHandler
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.BasePrinterXPlugin
import org.json.JSONObject

class SunmiPrinterXLcdPlugin(context: Context? = null) : BasePrinterXPlugin(context) {

    override val pluginId = "sunmi.printerx.lcd"
    override val version = 1

    private val lcdHandler by lazy { LcdApiHandler() }

    override fun getCapabilities() = listOf("sunmi.printerx.lcd")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi: LCD",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.printerx.lcd.config",
                "Controls LCD state.",
                "sunmi.printerx.lcd",
                exampleParameters = """{"command":"WAKE"}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.printerx.lcd.showText",
                "Shows text on 128x40 LCD. Call config(WAKE) first.",
                "sunmi.printerx.lcd",
                exampleParameters = """{"text":"Hello","size":32,"fill":false}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.printerx.lcd.showTexts",
                "Shows multiline text on 128x40 LCD.",
                "sunmi.printerx.lcd",
                exampleParameters = """{"texts":["Total","12.99"],"align":[1,2]}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.printerx.lcd.showBitmap",
                "Shows bitmap on 128x40 LCD.",
                "sunmi.printerx.lcd",
                exampleParameters = """{"bitmap":"iVBOR..."}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.printerx.lcd.showDigital",
                "Shows price on segment LCD (D3 MINI/D3 PRO). Max 7 chars.",
                "sunmi.printerx.lcd",
                experimental = true,
                exampleParameters = """{"digital":"12.99"}""",
                exampleOutput = """{"status":"ok"}"""
            )
        ),
        events = emptyList()
    )

    override suspend fun handleExecute(method: String, params: String, json: JSONObject): CommandResult =
        guardedExecute { lcdHandler.handle(method, json) }
}
