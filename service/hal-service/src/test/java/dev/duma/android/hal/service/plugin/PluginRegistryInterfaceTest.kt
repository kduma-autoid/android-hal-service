package dev.duma.android.hal.service.plugin

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the interface layer in [PluginRegistry]: registration gate, provider resolution
 * (default vs explicit), dynamic-availability filtering, and provider metadata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginRegistryInterfaceTest {

    private val lightContract = InterfaceContract(
        interfaceId = "light",
        version = 1,
        methods = listOf(
            MethodDescriptor("light.on", "on", "light", exampleParameters = "{}", exampleOutput = "{}")
        )
    )

    private class FakeDefiner(private val contract: InterfaceContract) : HalPlugin {
        override val pluginId = "interface.${contract.interfaceId}"
        override val version = 1
        override fun isSupported() = true
        override fun getCapabilities(): List<String> = emptyList()
        override fun getDescriptor() = PluginDescriptor(
            pluginId = pluginId, name = "definer", version = version,
            capabilities = emptyList(), groups = emptyList(),
            definesInterfaces = listOf(contract)
        )
        override fun initialize(pluginContext: PluginContext) {}
        override suspend fun execute(method: String, params: String) = CommandResult.unsupportedMethod(method)
        override fun setEventCallback(callback: HalPluginEventCallback?) {}
    }

    private class FakeProvider(
        override val pluginId: String,
        private val binding: InterfaceBinding,
        override val version: Int = 1,
        private val body: String
    ) : HalPlugin {
        override fun isSupported() = true
        override fun getCapabilities(): List<String> = listOf(pluginId)
        override fun getDescriptor() = PluginDescriptor(
            pluginId = pluginId, name = pluginId, version = version,
            capabilities = getCapabilities(), groups = emptyList(),
            interfaces = listOf(binding)
        )
        override fun initialize(pluginContext: PluginContext) {}
        override suspend fun execute(method: String, params: String) = CommandResult.Success(body)
        override fun setEventCallback(callback: HalPluginEventCallback?) {}
    }

    private fun registryWithProviders(): PluginRegistry {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(lightContract))
        registry.registerBuiltIn(
            FakeProvider("p.high", InterfaceBinding("light", priority = 100, features = listOf("timeout")), body = """{"who":"high"}""")
        )
        registry.registerBuiltIn(
            FakeProvider("p.low", InterfaceBinding("light", priority = 10, features = listOf("multiFlash")), body = """{"who":"low"}""")
        )
        return registry
    }

    @Test
    fun `default provider is highest priority`() = runTest {
        val registry = registryWithProviders()
        assertEquals("light", registry.interfaceIdForMethod("light.on"))
        val result = registry.executeInterface("light", null, "light.on", "{}")
        assertTrue(result is CommandResult.Success)
        assertEquals("""{"who":"high"}""", (result as CommandResult.Success).body)
    }

    @Test
    fun `explicit provider overrides default`() = runTest {
        val registry = registryWithProviders()
        val result = registry.executeInterface("light", "p.low", "light.on", "{}")
        assertEquals("""{"who":"low"}""", (result as CommandResult.Success).body)
    }

    @Test
    fun `unregistered interface is not callable even with a provider`() = runTest {
        val registry = PluginRegistry()
        // Provider present, but no definer registers the contract.
        registry.registerBuiltIn(
            FakeProvider("p.high", InterfaceBinding("light", priority = 100), body = "{}")
        )
        assertNull(registry.interfaceIdForMethod("light.on"))
        val result = registry.executeInterface("light", null, "light.on", "{}")
        assertTrue(result is CommandResult.Failure)
    }

    @Test
    fun `unavailable provider is excluded from resolution`() = runTest {
        val registry = registryWithProviders()
        registry.setPluginAvailability("p.high", false)
        assertEquals(listOf("p.low"), registry.getInterfaceProviders("light").map { it.pluginId })
        val result = registry.executeInterface("light", null, "light.on", "{}")
        assertEquals("""{"who":"low"}""", (result as CommandResult.Success).body)
    }

    @Test
    fun `provider list exposes features and default flag`() = runTest {
        val providers = registryWithProviders().getInterfaceProviders("light")
        assertEquals(listOf("p.high", "p.low"), providers.map { it.pluginId })
        assertTrue(providers.first().isDefault)
        assertEquals(listOf("timeout"), providers.first().features)
    }
}
