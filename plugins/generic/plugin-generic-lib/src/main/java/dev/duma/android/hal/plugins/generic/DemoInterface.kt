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
 * Definer for a hardware-free `demo` interface used to exercise the interface layer end-to-end
 * (multiple providers, provider selection/order/enable, methods AND events) without any device.
 * Providers: [DemoAlphaPlugin] and [DemoBetaPlugin], both always available.
 */
class DemoInterface : HalPlugin {

    override val pluginId = "interface.demo"
    override val version = 1

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[Interface] Demo",
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        definesInterfaces = listOf(DEMO_CONTRACT)
    )

    override fun initialize(pluginContext: PluginContext) {}

    override suspend fun execute(method: String, params: String): CommandResult =
        CommandResult.unsupportedMethod(method)

    override fun setEventCallback(callback: HalPluginEventCallback?) {}

    companion object {
        private const val PERMISSION = "demo"

        val DEMO_CONTRACT = InterfaceContract(
            interfaceId = "demo",
            version = 1,
            methods = listOf(
                MethodDescriptor(
                    "demo.echo",
                    "Echo text back. Each provider transforms it differently (Alpha uppercases, Beta reverses).",
                    PERMISSION,
                    exampleParameters = """{"text":"Hello"}""",
                    exampleOutput = """{"result":"HELLO","provider":"demo.alpha"}"""
                ),
                MethodDescriptor(
                    "demo.ping",
                    "Ping the active provider; returns which provider answered.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{"pong":true,"provider":"demo.alpha"}"""
                ),
                MethodDescriptor(
                    "demo.emit",
                    "Ask the provider to emit a demo.notice event (to test event subscriptions).",
                    PERMISSION,
                    exampleParameters = """{"message":"ping"}""",
                    exampleOutput = """{"emitted":true,"provider":"demo.alpha"}"""
                )
            ),
            events = listOf(
                EventDescriptor(
                    "demo.notice",
                    "Notice emitted by a demo provider in response to demo.emit.",
                    PERMISSION,
                    exampleEvent = """{"message":"ping","provider":"demo.alpha"}"""
                )
            ),
            features = listOf(
                InterfaceFeature("uppercase", "Provider uppercases echoed text."),
                InterfaceFeature("reverse", "Provider reverses echoed text.")
            )
        )
    }
}
