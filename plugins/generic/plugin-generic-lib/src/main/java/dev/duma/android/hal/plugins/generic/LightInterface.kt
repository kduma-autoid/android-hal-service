package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.InterfaceFeature
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Definer for the `light` interface — the unified indicator-light control surface shared by the
 * CPad built-in RGB LED (`sunmi.tms.led`) and the FLEX status light (`sunmi.statuslight`).
 *
 * This plugin only *registers* the contract (it provides no hardware and exposes no capabilities);
 * providers opt in via [dev.duma.android.hal.contract.InterfaceBinding]. Because the interface must
 * be explicitly registered, its methods are callable only while this definer is loaded.
 *
 * Mirrors the client-side `ILight` / `LightCapabilities` surface so a client can drive `light.*`
 * directly instead of hand-rolling a per-backend facade.
 */
class LightInterface : HalPlugin {

    override val pluginId = "interface.light"
    override val version = 1

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[Interface] Light",
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        definesInterfaces = listOf(LIGHT_CONTRACT)
    )

    override fun initialize(pluginContext: PluginContext) {}

    // Never routed here — interface methods run on the resolved provider plugin.
    override suspend fun execute(method: String, params: String): CommandResult =
        CommandResult.unsupportedMethod(method)

    override fun setEventCallback(callback: HalPluginEventCallback?) {}

    companion object {
        private const val PERMISSION = "light"

        val LIGHT_CONTRACT = InterfaceContract(
            interfaceId = "light",
            version = 1,
            methods = listOf(
                MethodDescriptor(
                    "light.on",
                    "Turns the light on with a steady color. Colors: red, green, blue, yellow, cyan, magenta, white. " +
                        "timeoutMs>0 auto-releases the light (only on providers advertising the 'timeout' feature).",
                    PERMISSION,
                    exampleParameters = """{"color":"green","timeoutMs":0}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "light.off",
                    "Turns the light off.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "light.flash",
                    "Blinks the light in a single color. onMs/offMs are the blink timings. " +
                        "timeoutMs>0 auto-releases (only on providers advertising the 'timeout' feature).",
                    PERMISSION,
                    exampleParameters = """{"color":"green","onMs":500,"offMs":500,"timeoutMs":0}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "light.multiFlash",
                    "Cycles the light through multiple colors. Accepts {steps:[{color,onMs,offMs}]} or " +
                        "{colors:[...],onMs,offMs}. Only on providers advertising the 'multiFlash' feature.",
                    PERMISSION,
                    exampleParameters = """{"steps":[{"color":"red","onMs":500,"offMs":300},{"color":"green","onMs":500,"offMs":300}]}""",
                    exampleOutput = """{}"""
                )
            ),
            events = emptyList<EventDescriptor>(),
            features = listOf(
                InterfaceFeature(
                    "multiFlash",
                    "Supports light.multiFlash (cycling through multiple colors).",
                    methods = listOf("light.multiFlash")
                ),
                InterfaceFeature(
                    "timeout",
                    "Supports the timeoutMs option on light.on / light.flash (auto-release)."
                )
            )
        )
    }
}
