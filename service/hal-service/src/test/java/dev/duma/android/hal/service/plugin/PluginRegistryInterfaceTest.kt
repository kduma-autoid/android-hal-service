package dev.duma.android.hal.service.plugin

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.InterfaceFeature
import dev.duma.android.hal.contract.MethodDescriptor
import android.content.Context
import android.os.DeadObjectException
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.stripExperimental
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

    private class FakeDefiner(
        private val contract: InterfaceContract,
        idOverride: String? = null
    ) : HalPlugin {
        override val pluginId = idOverride ?: "interface.${contract.interfaceId}"
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
        private val body: String,
        private val experimental: Boolean = false,
        private val supported: Boolean = true
    ) : HalPlugin {
        override fun isSupported() = supported
        override fun getCapabilities(): List<String> = listOf(pluginId)
        override fun getDescriptor() = PluginDescriptor(
            pluginId = pluginId, name = pluginId, version = version,
            experimental = experimental,
            capabilities = getCapabilities(), groups = emptyList(),
            interfaces = listOf(binding)
        )
        override fun initialize(pluginContext: PluginContext) {}
        override suspend fun execute(method: String, params: String) = CommandResult.Success(body)
        override fun setEventCallback(callback: HalPluginEventCallback?) {}
    }

    private fun registryWithProviders(contract: InterfaceContract = lightContract): PluginRegistry {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(contract))
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

    // --- Feature-gated methods without a selector ----------------------------------------------
    // Same providers as registryWithProviders(): the default p.high advertises only `timeout`, the
    // lower-priority p.low advertises `multiFlash` — the CPad LED / FLEX status light split.

    private val multiFlashContract = lightContract.copy(
        methods = lightContract.methods + MethodDescriptor(
            "light.multiFlash", "multiFlash", "light", exampleParameters = "{}", exampleOutput = "{}"
        ),
        features = listOf(InterfaceFeature("multiFlash", "cycle colors", methods = listOf("light.multiFlash")))
    )

    @Test
    fun `feature-gated method without a selector falls back to a provider with the feature`() = runTest {
        val registry = registryWithProviders(multiFlashContract)

        val result = registry.executeInterface("light", null, "light.multiFlash", "{}")
        assertTrue(result is CommandResult.Success)
        assertEquals("p.low", (result as CommandResult.Success).provider)
        // The fallback is per method: ungated methods still go to the default.
        val on = registry.executeInterface("light", null, "light.on", "{}")
        assertEquals("p.high", (on as CommandResult.Success).provider)
    }

    @Test
    fun `an explicit selector is not rerouted to a provider with the feature`() = runTest {
        val registry = registryWithProviders(multiFlashContract)

        val pinned = registry.executeInterface("light", "p.high", "light.multiFlash", "{}")
        assertTrue(pinned is CommandResult.Failure)
        assertEquals("unavailable", (pinned as CommandResult.Failure).code)
    }

    @Test
    fun `feature fallback never picks a provider the user disabled`() = runTest {
        val registry = registryWithProviders(multiFlashContract)
        val config = freshConfig()
        registry.interfacePreferenceConfig = config
        config.setEnabled("light", "p.low", false)

        val result = registry.executeInterface("light", null, "light.multiFlash", "{}")
        assertTrue(result is CommandResult.Failure)
        assertEquals("unavailable", (result as CommandResult.Failure).code)
    }

    // --- Experimental providers -------------------------------------------------------------
    // An experimental provider is excluded from the interface until the user enables it in settings
    // or the caller holds experimental access. `experimentalConfig` is left null here, so the only
    // way in is the caller flag.

    private fun registryWithExperimentalProvider(): PluginRegistry {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(lightContract))
        registry.registerBuiltIn(
            FakeProvider("p.stable", InterfaceBinding("light", priority = 10), body = """{"who":"stable"}""")
        )
        registry.registerBuiltIn(
            FakeProvider(
                "p.exp", InterfaceBinding("light", priority = 100),
                body = """{"who":"exp"}""", experimental = true
            )
        )
        return registry
    }

    @Test
    fun `experimental provider is hidden and never becomes the default`() {
        val registry = registryWithExperimentalProvider()
        // Despite the higher priority, the experimental provider is not part of the interface.
        assertEquals(listOf("p.stable"), registry.getInterfaceProviders("light").map { it.pluginId })
        assertTrue(registry.getInterfaceProviders("light").first().isDefault)
    }

    @Test
    fun `experimental provider appears and wins for a caller holding experimental access`() {
        val registry = registryWithExperimentalProvider()
        val providers = registry.getInterfaceProviders("light", callerHasExperimental = true)
        assertEquals(listOf("p.exp", "p.stable"), providers.map { it.pluginId })
        assertTrue(providers.first().isDefault)
        assertTrue(providers.first().experimental)
    }

    @Test
    fun `pinning an experimental provider without access is unavailable`() = runTest {
        val registry = registryWithExperimentalProvider()
        val denied = registry.executeInterface("light", "p.exp", "light.on", "{}")
        assertTrue(denied is CommandResult.Failure)

        val allowed = registry.executeInterface(
            "light", "p.exp", "light.on", "{}", callerHasExperimental = true
        )
        assertTrue(allowed is CommandResult.Success)
    }

    @Test
    fun `default routing skips the experimental provider without access`() = runTest {
        val registry = registryWithExperimentalProvider()
        val stable = registry.executeInterface("light", null, "light.on", "{}")
        assertEquals("p.stable", (stable as CommandResult.Success).provider)

        val exp = registry.executeInterface("light", null, "light.on", "{}", callerHasExperimental = true)
        assertEquals("p.exp", (exp as CommandResult.Success).provider)
    }

    @Test
    fun `definers and pure providers survive the empty-API skip`() {
        // Registration drops plugins with an empty API, but interface work never appears in `groups`:
        // a definer only carries definesInterfaces, and a provider only carries an InterfaceBinding
        // (it does not redeclare the contract's method descriptors). Judging either by methods/events
        // alone unregisters them and takes the whole interface layer down silently.
        val registry = registryWithProviders()
        assertNotNull(registry.getInterfaceContract("light"))
        assertEquals(listOf("p.high", "p.low"), registry.getInterfaceProviders("light").map { it.pluginId })
    }

    @Test
    fun `a wholly experimental plugin keeps no interface surface in a stable build`() {
        // stripExperimental() clears the `experimental` flag, so nothing downstream can tell what the
        // plugin was: the InterfaceBinding has to go with the methods. Left behind, the emptiness
        // check sees a reason to register and the runtime gate sees an ordinary provider, so an
        // experimental backend joins the interface in a stable build as a normal one.
        val descriptor = PluginDescriptor(
            pluginId = "p.exp", name = "p.exp", version = 1,
            experimental = true,
            capabilities = listOf("p.exp"), groups = emptyList(),
            definesInterfaces = listOf(lightContract),
            interfaces = listOf(InterfaceBinding("light", priority = 100))
        )
        val stable = descriptor.stripExperimental()
        assertFalse(stable.experimental)
        assertTrue(stable.definesInterfaces.isEmpty())
        assertTrue(stable.interfaces.isEmpty())
    }

    // --- Contract ownership ------------------------------------------------------------------
    // The contract carries the interface's method signatures and their requiredPermission, so a
    // definer replacing one silently re-specifies the API for everybody.

    /** Same interface as [lightContract], re-specified: another version and another permission. */
    private val rivalLightContract = InterfaceContract(
        interfaceId = "light",
        version = 99,
        methods = listOf(
            MethodDescriptor("light.on", "on", "rival.permission", exampleParameters = "{}", exampleOutput = "{}")
        )
    )

    /** The built-in [lightContract] is the registered one, held by its own definer. */
    private fun assertBuiltInLightContract(registry: PluginRegistry) {
        val registered = registry.getInterfaceContract("light")
        assertNotNull(registered)
        assertEquals(1, registered!!.version)
        assertEquals("light", registered.methods.single().requiredPermission)
        assertEquals("interface.light", registry.definerForInterface("light"))
    }

    @Test
    fun `a second definer does not replace an already registered contract`() {
        // First definer wins.
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(lightContract))
        registry.registerBuiltIn(FakeDefiner(rivalLightContract, idOverride = "interface.light.rival"))

        assertBuiltInLightContract(registry)
    }

    @Test
    fun `an external plugin displacing a built-in definer does not take the interface down`() = runTest {
        val registry = registryWithProviders()

        // Same pluginId as the built-in definer: the external plugin wins the slot, as plugins do...
        val rival = FakeDefiner(rivalLightContract, idOverride = "interface.light")
        assertTrue(registry.registerExternal(rival, "com.evil"))
        assertEquals(PluginRegistry.PluginSource.EXTERNAL, registry.getPluginInfo("interface.light")?.source)
        // ...but neither replaces the contract nor unregisters it. Before the displaced built-in's
        // contract was kept, the interface vanished here and every call was not_found.
        assertBuiltInLightContract(registry)
        val during = registry.executeInterface("light", null, "light.on", "{}")
        assertEquals("p.high", (during as CommandResult.Success).provider)

        // Disconnecting restores the built-in, which picks its own contract back up.
        registry.unregisterExternal(rival)
        assertEquals(PluginRegistry.PluginSource.BUILT_IN, registry.getPluginInfo("interface.light")?.source)
        assertBuiltInLightContract(registry)
        val after = registry.executeInterface("light", null, "light.on", "{}")
        assertEquals("p.high", (after as CommandResult.Success).provider)
    }

    @Test
    fun `an external definer under another pluginId cannot redefine a built-in interface`() {
        val registry = registryWithProviders()

        // The plugin itself registers; only its contract is refused.
        val rival = FakeDefiner(rivalLightContract, idOverride = "com.evil.light")
        assertTrue(registry.registerExternal(rival, "com.evil"))
        assertBuiltInLightContract(registry)

        // It never owned the interface, so its departure changes nothing.
        registry.unregisterExternal(rival)
        assertBuiltInLightContract(registry)
    }

    private val fanContract = InterfaceContract(
        interfaceId = "fan",
        methods = listOf(MethodDescriptor("fan.spin", "spin", "fan", exampleParameters = "{}", exampleOutput = "{}"))
    )

    @Test
    fun `an external definer registers an interface no built-in defines`() {
        val registry = PluginRegistry()
        val vendor = FakeDefiner(fanContract, idOverride = "com.vendor.fan")

        assertTrue(registry.registerExternal(vendor, "com.vendor"))
        assertEquals("com.vendor.fan", registry.definerForInterface("fan"))
        assertEquals("fan", registry.interfaceIdForMethod("fan.spin"))

        // No other definer to hand it to, so the interface goes with its only definer.
        registry.unregisterExternal(vendor)
        assertNull(registry.getInterfaceContract("fan"))
        assertNull(registry.definerForInterface("fan"))
    }

    @Test
    fun `a built-in definer takes an interface over from an external one`() {
        val registry = PluginRegistry()
        val vendor = FakeDefiner(fanContract, idOverride = "com.vendor.fan")
        registry.registerExternal(vendor, "com.vendor")

        registry.registerBuiltIn(FakeDefiner(fanContract.copy(version = 2), idOverride = "builtin.fan"))
        assertEquals("builtin.fan", registry.definerForInterface("fan"))
        assertEquals(2, registry.getInterfaceContract("fan")!!.version)

        // The external definer no longer owns it, so disconnecting leaves the built-in's contract.
        registry.unregisterExternal(vendor)
        assertEquals("builtin.fan", registry.definerForInterface("fan"))
        assertEquals(2, registry.getInterfaceContract("fan")!!.version)
    }

    // --- External plugins going away ----------------------------------------------------------

    /**
     * An external plugin as the registry sees it through [dev.duma.android.hal.contract.AidlPluginAdapter]:
     * every member is a binder call, so after [die] — the state `onServiceDisconnected` runs in —
     * each one throws. Provides `light`, so it is not skipped as an empty API. Its `dispose()` is a
     * binder call too (the real adapter's is a no-op today), so a disconnect that disposes it goes
     * through `safeDispose`'s failure path and must not read the id from the dead plugin there.
     */
    private class FakeRemote(
        private val id: String,
        private val ver: Int = 1,
        private val supported: Boolean = true,
        private val tag: String = id
    ) : HalPlugin {
        private var dead = false
        fun die() { dead = true }
        private fun <T> call(value: () -> T): T = if (dead) throw DeadObjectException() else value()

        override val pluginId: String get() = call { id }
        override val version: Int get() = call { ver }
        override fun isSupported() = call { supported }
        override fun getCapabilities(): List<String> = call { listOf(id) }
        override fun getDescriptor() = call {
            PluginDescriptor(
                pluginId = id, name = id, version = ver,
                capabilities = listOf(id), groups = emptyList(),
                interfaces = listOf(InterfaceBinding("light", priority = 50))
            )
        }
        override fun initialize(pluginContext: PluginContext) = call {}
        override suspend fun execute(method: String, params: String): CommandResult =
            call { CommandResult.Success("""{"who":"$tag"}""") }
        override fun setEventCallback(callback: HalPluginEventCallback?) = call {}
        override fun dispose() = call {}
    }

    @Test
    fun `an external plugin that lost its slot does not unregister the winner`() = runTest {
        val registry = registryWithProviders()
        val winner = FakeRemote("ext.light", ver = 2, tag = "winner")
        val loser = FakeRemote("ext.light", ver = 1, tag = "loser")
        assertTrue(registry.registerExternal(winner, "com.vendor.a"))
        assertFalse(registry.registerExternal(loser, "com.vendor.b"))

        loser.die()
        registry.unregisterExternal(loser)
        assertEquals(PluginRegistry.PluginInfo(PluginRegistry.PluginSource.EXTERNAL, "com.vendor.a"), registry.getPluginInfo("ext.light"))
        val routed = registry.executeInterface("light", "ext.light", "light.on", "{}")
        assertEquals("""{"who":"winner"}""", (routed as CommandResult.Success).body)

        // The winner's own disconnect does remove it — without a single call into the dead binder
        // escaping (dispose() throws, and safeDispose logs with the id it was handed).
        winner.die()
        registry.unregisterExternal(winner)
        assertNull(registry.getPluginInfo("ext.light"))
        assertFalse("ext.light" in registry.allCapabilities())
        assertEquals(listOf("p.high", "p.low"), registry.getInterfaceProviders("light").map { it.pluginId })
    }

    @Test
    fun `an unsupported external plugin does not relabel a built-in under the same id`() = runTest {
        val registry = registryWithProviders()
        val ghost = FakeRemote("p.high", supported = false)

        assertFalse(registry.registerExternal(ghost, "com.evil"))
        assertEquals(PluginRegistry.PluginSource.BUILT_IN, registry.getPluginInfo("p.high")?.source)
        assertFalse("p.high" in registry.getUnsupportedPluginIds())

        ghost.die()
        registry.unregisterExternal(ghost)
        assertEquals(PluginRegistry.PluginSource.BUILT_IN, registry.getPluginInfo("p.high")?.source)
        val result = registry.executeInterface("light", null, "light.on", "{}")
        assertEquals("p.high", (result as CommandResult.Success).provider)
    }

    @Test
    fun `an unsupported external plugin is listed until it disconnects`() {
        val registry = PluginRegistry()
        val unsupported = FakeRemote("ext.unsupported", supported = false)

        assertFalse(registry.registerExternal(unsupported, "com.vendor"))
        assertTrue("ext.unsupported" in registry.getUnsupportedPluginIds())
        assertEquals(PluginRegistry.PluginSource.EXTERNAL, registry.getPluginInfo("ext.unsupported")?.source)

        // Removed by instance — before, the disconnect left it listed with a dead binder behind it.
        unsupported.die()
        registry.unregisterExternal(unsupported)
        assertFalse("ext.unsupported" in registry.getUnsupportedPluginIds())
        assertNull(registry.getPluginInfo("ext.unsupported"))
    }

    @Test
    fun `an unsupported built-in does not relabel an external plugin under the same id`() {
        // Not reachable with HalService's startup order (every built-in registers synchronously in
        // onCreate, before any onServiceConnected can run on the main thread), but registerBuiltIn is
        // public and the rule is the same as for an unsupported external plugin.
        val registry = PluginRegistry()
        assertTrue(registry.registerExternal(FakeRemote("x"), "com.vendor"))

        registry.registerBuiltIn(FakeProvider("x", InterfaceBinding("light"), body = "{}", supported = false))
        assertEquals(PluginRegistry.PluginSource.EXTERNAL, registry.getPluginInfo("x")?.source)
        assertFalse("x" in registry.getUnsupportedPluginIds())
    }

    // --- Built-ins arriving after an external plugin took their id ------------------------------
    // HalService registers built-ins before external discovery, but the registry must not depend on
    // that: the late built-in ends up exactly where it would be had it come first and been displaced.

    @Test
    fun `a built-in arriving after an external plugin took its id waits in reserve`() = runTest {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(lightContract))
        val external = FakeRemote("p.late", tag = "external")
        assertTrue(registry.registerExternal(external, "com.vendor"))

        registry.registerBuiltIn(FakeProvider("p.late", InterfaceBinding("light"), body = """{"who":"built-in"}"""))
        assertEquals(PluginRegistry.PluginSource.EXTERNAL, registry.getPluginInfo("p.late")?.source)
        val during = registry.executeInterface("light", "p.late", "light.on", "{}")
        assertEquals("""{"who":"external"}""", (during as CommandResult.Success).body)

        // Before, the late built-in was dropped, and the slot stayed empty once the external one left.
        external.die()
        registry.unregisterExternal(external)
        assertEquals(PluginRegistry.PluginSource.BUILT_IN, registry.getPluginInfo("p.late")?.source)
        val after = registry.executeInterface("light", "p.late", "light.on", "{}")
        assertEquals("""{"who":"built-in"}""", (after as CommandResult.Success).body)
    }

    @Test
    fun `a late built-in definer holds its contract as if it had come first`() {
        val registry = PluginRegistry()
        val rival = FakeDefiner(rivalLightContract, idOverride = "interface.light")
        registry.registerExternal(rival, "com.evil")
        assertEquals(99, registry.getInterfaceContract("light")!!.version)

        // Same end state as `an external plugin displacing a built-in definer ...`, reached the other way round.
        registry.registerBuiltIn(FakeDefiner(lightContract))
        assertEquals(PluginRegistry.PluginSource.EXTERNAL, registry.getPluginInfo("interface.light")?.source)
        assertBuiltInLightContract(registry)
        // The interface is a built-in's now: another external definer is refused.
        registry.registerExternal(FakeDefiner(rivalLightContract, idOverride = "com.evil.light"), "com.evil")
        assertBuiltInLightContract(registry)

        registry.unregisterExternal(rival)
        assertEquals(PluginRegistry.PluginSource.BUILT_IN, registry.getPluginInfo("interface.light")?.source)
        assertBuiltInLightContract(registry)
    }

    @Test
    fun `only one built-in waits for a slot`() = runTest {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(lightContract))
        val external = FakeRemote("p.late")
        registry.registerExternal(external, "com.vendor")
        registry.registerBuiltIn(FakeProvider("p.late", InterfaceBinding("light"), body = """{"who":"first"}"""))
        registry.registerBuiltIn(FakeProvider("p.late", InterfaceBinding("light"), version = 5, body = """{"who":"second"}"""))

        external.die()
        registry.unregisterExternal(external)
        val result = registry.executeInterface("light", "p.late", "light.on", "{}")
        assertEquals("""{"who":"first"}""", (result as CommandResult.Success).body)
    }

    @Test
    fun `a built-in superseded by a newer built-in leaves no contract behind`() {
        // Only an external plugin displaces into reserve. A superseded built-in used to be held there
        // too, and kept lending its `light` contract after the newer one — which defines nothing —
        // took the id: the interface stayed registered with no definer.
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(lightContract))
        registry.registerBuiltIn(FakeProvider("interface.light", InterfaceBinding("fan"), version = 2, body = "{}"))

        assertNull(registry.getInterfaceContract("light"))
        assertNull(registry.definerForInterface("light"))
    }
}
