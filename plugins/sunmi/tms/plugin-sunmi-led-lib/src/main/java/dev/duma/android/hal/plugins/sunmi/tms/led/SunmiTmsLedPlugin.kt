package dev.duma.android.hal.plugins.sunmi.tms.led

import android.content.Context
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.tms.base.BaseTmsPlugin
import dev.duma.android.hal.plugins.sunmi.tms.led.handler.RgbLedHandler
import dev.duma.android.hal.plugins.sunmi.tms.support.RgbLedSupport

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

    override fun initialize(pluginContext: PluginContext) {
        // Pessimistic until the TMS connection confirms the device actually has an RGB LED.
        pluginContext.setPluginAvailable(false)
        super.initialize(pluginContext)
    }

    override fun onTmsConnected() {
        val supported = try {
            RgbLedSupport.isSupported(tmsApi.deviceManager)
        } catch (_: Exception) {
            false
        }
        pluginContext?.setPluginAvailable(supported)
    }

    override fun onTmsDisconnected() {
        pluginContext?.setPluginAvailable(false)
    }

    override suspend fun execute(method: String, params: String): CommandResult = guardedExecute {
        rgbLedHandler.handle(method, params)
    }

    private fun buildLedMethods() = listOf(
        MethodDescriptor("sunmi.tms.led.on",
            "Turns on the RGB LED with a steady color. color: 1-7 or red/green/blue/yellow/cyan/magenta/white. timeoutMs>0 auto-releases LED control.",
            "sunmi.tms.led",
            exampleParameters = """{"color":"green","timeoutMs":0}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.led.flash",
            "Blinks the RGB LED in a single color. color: 1-7 or a color name. onMs/offMs are the blink timings. timeoutMs>0 auto-releases LED control.",
            "sunmi.tms.led",
            exampleParameters = """{"color":"green","onMs":500,"offMs":500,"timeoutMs":0}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.led.off", "Turns off the RGB LED indicator", "sunmi.tms.led",
            exampleParameters = "{}",
            exampleOutput = """{}"""),
    )
}
