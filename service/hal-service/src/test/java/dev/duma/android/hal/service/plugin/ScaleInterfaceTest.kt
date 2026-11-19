package dev.duma.android.hal.service.plugin

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.generic.ScaleInterface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The `scale` interface with a USB scale provider (`dymo.m10`) that comes and goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaleInterfaceTest {

    private class FakeScale : HalPlugin {
        override val pluginId = "dymo.m10"
        override val version = 1
        override fun isSupported() = true
        override fun getCapabilities(): List<String> = listOf(pluginId)
        override fun getDescriptor() = PluginDescriptor(
            pluginId = pluginId, name = pluginId, version = version,
            capabilities = getCapabilities(), groups = emptyList(),
            interfaces = listOf(InterfaceBinding("scale", priority = 100))
        )
        override fun initialize(pluginContext: PluginContext) {}
        override suspend fun execute(method: String, params: String) =
            CommandResult.Success("""{"method":"$method"}""")
        override fun setEventCallback(callback: HalPluginEventCallback?) {}
    }

    private fun registry(): PluginRegistry = PluginRegistry().apply {
        registerBuiltIn(ScaleInterface())
        registerBuiltIn(FakeScale())
    }

    @Test
    fun `definer registers the contract`() {
        val contract = registry().getInterfaceContract("scale")
        assertNotNull(contract)
        assertEquals(listOf("scale.getInfo", "scale.getWeight"), contract!!.methods.map { it.name })
        assertEquals(listOf("scale.onWeight", "scale.onStatus"), contract.events.map { it.name })
        assertEquals(emptyList<String>(), contract.features.map { it.key })
    }

    @Test
    fun `getWeight is routed to the scale`() = runTest {
        val result = registry().executeInterface("scale", null, "scale.getWeight", "{}")
        assertEquals("dymo.m10", (result as CommandResult.Success).provider)
        assertEquals("""{"method":"scale.getWeight"}""", result.body)
    }

    @Test
    fun `an unplugged scale makes the interface unavailable`() = runTest {
        val registry = registry()
        registry.setPluginAvailability("dymo.m10", false)
        val result = registry.executeInterface("scale", null, "scale.getWeight", "{}")
        assertEquals("unavailable", (result as CommandResult.Failure).code)
    }
}
