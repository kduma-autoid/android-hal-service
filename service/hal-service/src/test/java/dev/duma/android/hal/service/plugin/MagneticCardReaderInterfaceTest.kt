package dev.duma.android.hal.service.plugin

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.generic.MagneticCardReaderInterface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `magneticCardReader` interface with its two kinds of provider: an active reader/writer
 * (`msr605x`, features `read` + `write`) and a passive swipe reader (`sunmi.card`, no features).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MagneticCardReaderInterfaceTest {

    /** Echoes the method it was called with, so routing is observable. */
    private class FakeProvider(
        override val pluginId: String,
        private val binding: InterfaceBinding
    ) : HalPlugin {
        override val version = 1
        override fun isSupported() = true
        override fun getCapabilities(): List<String> = listOf(pluginId)
        override fun getDescriptor() = PluginDescriptor(
            pluginId = pluginId, name = pluginId, version = version,
            capabilities = getCapabilities(), groups = emptyList(),
            interfaces = listOf(binding)
        )
        override fun initialize(pluginContext: PluginContext) {}
        override suspend fun execute(method: String, params: String) =
            CommandResult.Success("""{"method":"$method"}""")
        override fun setEventCallback(callback: HalPluginEventCallback?) {}
    }

    private fun registry(): PluginRegistry = PluginRegistry().apply {
        registerBuiltIn(MagneticCardReaderInterface())
        registerBuiltIn(
            FakeProvider("msr605x", InterfaceBinding("magneticCardReader", priority = 100, features = listOf("read", "write")))
        )
        registerBuiltIn(FakeProvider("sunmi.card", InterfaceBinding("magneticCardReader", priority = 50)))
    }

    @Test
    fun `definer registers the contract with read and write features`() {
        val contract = registry().getInterfaceContract("magneticCardReader")
        assertNotNull(contract)
        assertEquals(
            listOf(
                "magneticCardReader.getInfo",
                "magneticCardReader.read",
                "magneticCardReader.cancel",
                "magneticCardReader.write"
            ),
            contract!!.methods.map { it.name }
        )
        assertEquals(listOf("magneticCardReader.onSwipe"), contract.events.map { it.name })
        assertEquals(listOf("read", "write"), contract.features.map { it.key })
    }

    @Test
    fun `active reader is the default provider`() = runTest {
        val result = registry().executeInterface("magneticCardReader", null, "magneticCardReader.read", "{}")
        assertEquals("msr605x", (result as CommandResult.Success).provider)
        assertEquals(
            listOf("msr605x", "sunmi.card"),
            registry().getInterfaceProviders("magneticCardReader").map { it.pluginId }
        )
    }

    @Test
    fun `passive reader serves getInfo but not read or write`() = runTest {
        val registry = registry()
        val info = registry.executeInterface("magneticCardReader", "sunmi.card", "magneticCardReader.getInfo", "{}")
        assertEquals("sunmi.card", (info as CommandResult.Success).provider)

        for (method in listOf("magneticCardReader.read", "magneticCardReader.cancel", "magneticCardReader.write")) {
            val gated = registry.executeInterface("magneticCardReader", "sunmi.card", method, "{}")
            assertTrue(method, gated is CommandResult.Failure)
            assertEquals(method, "unavailable", (gated as CommandResult.Failure).code)
        }
    }

    @Test
    fun `with the active reader unplugged, read is unavailable and getInfo falls to the passive one`() = runTest {
        val registry = registry()
        registry.setPluginAvailability("msr605x", false)

        val read = registry.executeInterface("magneticCardReader", null, "magneticCardReader.read", "{}")
        assertEquals("unavailable", (read as CommandResult.Failure).code)

        val info = registry.executeInterface("magneticCardReader", null, "magneticCardReader.getInfo", "{}")
        assertEquals("sunmi.card", (info as CommandResult.Success).provider)
    }
}
