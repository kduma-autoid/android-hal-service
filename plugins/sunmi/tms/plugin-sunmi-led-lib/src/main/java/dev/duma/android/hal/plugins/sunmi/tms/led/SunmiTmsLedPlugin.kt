package dev.duma.android.hal.plugins.sunmi.tms.led

import android.content.Context
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.tms.base.BaseTmsPlugin
import dev.duma.android.hal.plugins.sunmi.tms.led.handler.RgbLedHandler
import dev.duma.android.hal.plugins.sunmi.tms.support.RgbLedSupport

/**
 * HAL plugin for the CPad built-in RGB LED indicator.
 *
 * Backed by the Sunmi Customer API (TMS) SDK's [com.sunmi.tmsmaster.aidl.devicemanager.IDeviceManager]
 * RGB LED methods (available from SDK 1.3.48). Currently compatible with CPad running Android 14.
 *
 * The built-in RGB LED is a fixed hardware attribute, so support is a static registration gate
 * ([isSupported]) rather than dynamic availability — a device either has the LED or it never will.
 */
class SunmiTmsLedPlugin(context: Context? = null) : BaseTmsPlugin(context) {

    override val pluginId = "sunmi.tms.led"
    override val version = 1

    private val rgbLedHandler by lazy { RgbLedHandler(tmsApi) }

    override fun isSupported(): Boolean = super.isSupported() && RgbLedSupport.hasBuiltinLedByProperty()

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
        ),
        // Provides the unified `light` interface. Preferred over the FLEX status light (higher
        // priority). Supports the timeoutMs option (auto-release) but not multiFlash.
        interfaces = listOf(
            InterfaceBinding(interfaceId = "light", priority = 100, features = listOf("timeout"))
        )
    )

    override suspend fun onExecute(method: String, params: String): CommandResult = guardedExecute {
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
