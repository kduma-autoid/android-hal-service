package dev.duma.android.hal.service.plugin

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.MethodDescriptor
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.generic.PrinterInterface
import dev.duma.android.hal.plugins.generic.BarcodeScannerInterface
import dev.duma.android.hal.service.config.InterfacePreferenceConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // The resolved handler is reported in the response header.
        assertEquals("p.high", result.provider)
    }

    @Test
    fun `explicit provider overrides default`() = runTest {
        val registry = registryWithProviders()
        val result = registry.executeInterface("light", "p.low", "light.on", "{}")
        assertEquals("""{"who":"low"}""", (result as CommandResult.Success).body)
        assertEquals("p.low", result.provider)
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

    private fun freshConfig() = InterfacePreferenceConfig(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `user order overrides priority and default`() = runTest {
        val registry = registryWithProviders()
        val config = freshConfig()
        registry.interfacePreferenceConfig = config
        config.setOrder("light", listOf("p.low", "p.high"))

        val providers = registry.getInterfaceProviders("light")
        assertEquals(listOf("p.low", "p.high"), providers.map { it.pluginId })
        assertTrue(providers.first().isDefault)
        val result = registry.executeInterface("light", null, "light.on", "{}")
        assertEquals("""{"who":"low"}""", (result as CommandResult.Success).body)
    }

    @Test
    fun `disabled provider is excluded from resolution and not routable`() = runTest {
        val registry = registryWithProviders()
        val config = freshConfig()
        registry.interfacePreferenceConfig = config
        config.setEnabled("light", "p.high", false)

        assertEquals(listOf("p.low"), registry.getInterfaceProviders("light").map { it.pluginId })
        val default = registry.executeInterface("light", null, "light.on", "{}")
        assertEquals("""{"who":"low"}""", (default as CommandResult.Success).body)
        // Explicit selection of a disabled provider is rejected.
        assertTrue(registry.executeInterface("light", "p.high", "light.on", "{}") is CommandResult.Failure)
    }

    @Test
    fun `all implementors lists disabled providers with flags`() = runTest {
        val registry = registryWithProviders()
        val config = freshConfig()
        registry.interfacePreferenceConfig = config
        config.setEnabled("light", "p.low", false)

        val all = registry.getAllInterfaceImplementors("light").associateBy { it.pluginId }
        assertEquals(setOf("p.high", "p.low"), all.keys)
        assertTrue(all.getValue("p.high").enabled)
        assertTrue(!all.getValue("p.low").enabled)
        // p.high is the only enabled+available implementor, so it is the effective default.
        assertTrue(all.getValue("p.high").isDefault)
    }

    // --- Real `printer` / `scanner` definers (replace the former generic wrappers) ---

    @Test
    fun `printer and scanner definers register real contracts`() = runTest {
        val registry = PluginRegistry()
        registry.registerBuiltIn(PrinterInterface())
        registry.registerBuiltIn(BarcodeScannerInterface())

        val printer = registry.getInterfaceContract("printer")
        assertNotNull(printer)
        assertEquals(
            listOf("printer.printEscPos", "printer.printTspl", "printer.printZpl", "printer.printImage", "printer.cut"),
            printer!!.methods.map { it.name }
        )
        // Every printer method is feature-gated (feature.methods is non-empty).
        assertEquals(
            setOf("escpos", "tspl", "zpl", "image", "cut"),
            printer.features.map { it.key }.toSet()
        )

        val scanner = registry.getInterfaceContract("barcodeScanner")
        assertNotNull(scanner)
        assertEquals(listOf("barcodeScanner.trigger", "barcodeScanner.stop"), scanner!!.methods.map { it.name })
        assertEquals(listOf("barcodeScanner.onScan"), scanner.events.map { it.name })
    }

    @Test
    fun `printer resolves to sunmi provider and feature-gates zpl`() = runTest {
        val registry = PluginRegistry()
        registry.registerBuiltIn(PrinterInterface())
        // Mirrors SunmiPrinterXPrinterPlugin's binding: everything except ZPL.
        registry.registerBuiltIn(
            FakeProvider(
                "sunmi.printerx.printer",
                InterfaceBinding("printer", priority = 100, features = listOf("escpos", "tspl", "image", "cut")),
                body = "{}"
            )
        )

        assertEquals(listOf("sunmi.printerx.printer"), registry.getInterfaceProviders("printer").map { it.pluginId })
        assertFalse("zpl" in registry.getInterfaceProviders("printer").first().features)

        val escpos = registry.executeInterface("printer", null, "printer.printEscPos", "{}")
        assertTrue(escpos is CommandResult.Success)
        assertEquals("sunmi.printerx.printer", (escpos as CommandResult.Success).provider)

        // No provider advertises `zpl`, so the method-level feature gate rejects it.
        val zpl = registry.executeInterface("printer", null, "printer.printZpl", "{}")
        assertTrue(zpl is CommandResult.Failure)
        assertEquals("unavailable", (zpl as CommandResult.Failure).code)
    }

    @Test
    fun `scanner resolves to inner scanner by priority`() = runTest {
        val registry = PluginRegistry()
        registry.registerBuiltIn(BarcodeScannerInterface())
        registry.registerBuiltIn(FakeProvider("sunmi.scanner.inner", InterfaceBinding("barcodeScanner", priority = 100), body = """{"status":"scanning"}"""))
        registry.registerBuiltIn(FakeProvider("sunmi.scanner.camera", InterfaceBinding("barcodeScanner", priority = 40), body = """{"status":"scanning"}"""))

        val providers = registry.getInterfaceProviders("barcodeScanner")
        assertEquals(listOf("sunmi.scanner.inner", "sunmi.scanner.camera"), providers.map { it.pluginId })
        assertTrue(providers.first().isDefault)

        val result = registry.executeInterface("barcodeScanner", null, "barcodeScanner.trigger", "{}")
        assertTrue(result is CommandResult.Success)
        assertEquals("sunmi.scanner.inner", (result as CommandResult.Success).provider)
    }
}
