package dev.duma.android.hal.plugins.sunmi.tms.led

import android.content.Context
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.tms.base.BaseTmsPlugin
import dev.duma.android.hal.plugins.sunmi.tms.led.handler.RgbLedHandler

/**
 * HAL plugin for the CPad built-in RGB LED indicator.
 *
 * Backed by the Sunmi Customer API (TMS) SDK's [com.sunmi.tmsmaster.aidl.devicemanager.IDeviceManager]
 * RGB LED methods (available from SDK 1.3.48). Currently compatible with CPad running Android 14.
 */
class SunmiTmsLedPlugin(context: Context? = null) : BaseTmsPlugin(context) {

    override val pluginId = "sunmi.tms.led"
    override val version = 1

    private val rgbLedHandler by lazy { RgbLedHandler(tmsApi) }

    override fun getCapabilities() = listOf("sunmi.tms.led")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi: TMS (LED)",
        version = version,
        capabilities = getCapabilities(),
        groups = listOf(
            DescriptorGroup(
                name = "RGB LED",
                methods = buildLedMethods(),
            ),
        )
    )

    override suspend fun execute(method: String, params: String): CommandResult = guardedExecute {
        rgbLedHandler.handle(method, params)
    }

    private fun buildLedMethods() = listOf(
        MethodDescriptor("sunmi.tms.led.isSupported", "Checks whether the device supports the RGB LED indicator", "sunmi.tms.led",
            exampleParameters = "{}",
            exampleOutput = """{"result":true}"""),
        MethodDescriptor("sunmi.tms.led.open",
            "Turns on the RGB LED. color: 1-7 or red/green/blue/yellow/cyan/magenta/white. lightMode: 0=steady, 1=blink (onMs/offMs apply). timeoutMs>0 auto-releases LED control.",
            "sunmi.tms.led",
            exampleParameters = """{"color":"green","lightMode":1,"onMs":500,"offMs":500,"timeoutMs":0}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.led.close", "Turns off the RGB LED indicator", "sunmi.tms.led",
            exampleParameters = "{}",
            exampleOutput = """{}"""),
    )
}
