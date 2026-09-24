package dev.duma.android.hal.service.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.service.auth.AuthManager
import dev.duma.android.hal.service.auth.TokenEntity
import dev.duma.android.hal.service.auth.TokenManager
import dev.duma.android.hal.service.config.ExperimentalConfig
import dev.duma.android.hal.service.plugin.PluginRegistry
import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.TransportRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The permission gates [ServiceCommandHandler] owns: which events a token may subscribe to, and who
 * may rewrite an interface's provider order. Both are new controls, and the subscription one has
 * already regressed once (it cut off `system.*` for every token without `*`), so they are covered
 * here rather than only through a transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceCommandHandlerGateTest {

    private val caller = CallerContext(transport = "test")

    private val lightContract = InterfaceContract(
        interfaceId = "light",
        methods = listOf(
            MethodDescriptor("light.on", "on", "light", exampleParameters = "{}", exampleOutput = "{}")
        )
    )

    private class FakeDefiner(
        private val contract: InterfaceContract,
        override val pluginId: String = "interface.${contract.interfaceId}"
    ) : HalPlugin {
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

    /** A provider with no native API: it is listed for its interfaces alone. */
    private class FakeProvider(
        override val pluginId: String,
        private val interfaceIds: List<String>
    ) : HalPlugin {
        override val version = 1
        override fun isSupported() = true
        override fun getCapabilities(): List<String> = listOf(pluginId)
        override fun getDescriptor() = PluginDescriptor(
            pluginId = pluginId, name = pluginId, version = version,
            capabilities = getCapabilities(), groups = emptyList(),
            interfaces = interfaceIds.map { InterfaceBinding(it) }
        )
        override fun initialize(pluginContext: PluginContext) {}
        override suspend fun execute(method: String, params: String) = CommandResult.Success("{}")
        override fun setEventCallback(callback: HalPluginEventCallback?) {}
    }

    private fun handlerFor(permissions: String, setup: (PluginRegistry) -> Unit = {}): ServiceCommandHandler {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(lightContract))
        setup(registry)
        val tokenManager = mockk<TokenManager>()
        coEvery { tokenManager.validateToken(any(), any()) } returns TokenEntity(
            token = "t",
            clientId = "c",
            clientType = "test",
            permissions = permissions,
            grantedBy = "test",
            grantedAt = 0L,
            expiresAt = null,
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )
        return ServiceCommandHandler(
            authManager = mockk<AuthManager>(relaxed = true),
            tokenManager = tokenManager,
            pluginRegistry = registry,
            transportRegistry = TransportRegistry(),
            experimentalConfig = ExperimentalConfig(context)
        )
    }

    // --- subscription gate ---

    @Test
    fun `service events are subscribable by any valid token`() = runTest {
        // Regression: deriving the permission from the name turned system.plugins.changed into the
        // capability `system.plugins`, which no ordinary token holds — and every facade's
        // onChanged() subscribes to both of these.
        val result = handlerFor("light")
            .subscribe("t", "system.plugins.changed,system.interfaces.changed", caller)
        assertTrue(result is CommandResult.Success)
    }

    @Test
    fun `subscribing to an event the token has no permission for is denied`() = runTest {
        val result = handlerFor("light").subscribe("t", "barcodeScanner.onScan", caller)
        assertTrue(result is CommandResult.Failure)
    }

    @Test
    fun `one denied entry rejects the whole subscribe`() = runTest {
        val result = handlerFor("light").subscribe("t", "light.changed,barcodeScanner.onScan", caller)
        assertTrue(result is CommandResult.Failure)
    }

    @Test
    fun `the source filter does not widen a subscription`() = runTest {
        val handler = handlerFor("barcodeScanner")
        assertTrue(
            handler.subscribe("t", "barcodeScanner.onScan@sunmi.scanner.inner", caller)
                is CommandResult.Success
        )
        assertTrue(handler.subscribe("t", "light.changed@anything", caller) is CommandResult.Failure)
    }

    @Test
    fun `a wildcard token subscribes to anything`() = runTest {
        val result = handlerFor("*").subscribe("t", "barcodeScanner.onScan,light.changed", caller)
        assertTrue(result is CommandResult.Success)
    }

    // --- interface configuration gate ---

    @Test
    fun `setOrder requires the permission the interface declares`() = runTest {
        val params = """{"interfaceId":"light","order":["a","b"]}"""
        assertTrue(
            handlerFor("demo").execute("t", "system.interface.setOrder", params, caller)
                is CommandResult.Failure
        )
        assertTrue(
            handlerFor("light").execute("t", "system.interface.setOrder", params, caller)
                is CommandResult.Success
        )
    }

    @Test
    fun `setEnabled requires the permission the interface declares`() = runTest {
        val params = """{"interfaceId":"light","pluginId":"p.one","enabled":false}"""
        assertTrue(
            handlerFor("demo").execute("t", "system.interface.setEnabled", params, caller)
                is CommandResult.Failure
        )
        assertTrue(
            handlerFor("light").execute("t", "system.interface.setEnabled", params, caller)
                is CommandResult.Success
        )
    }

    @Test
    fun `configuring an unregistered interface is not found`() = runTest {
        val params = """{"interfaceId":"nope","order":["a"]}"""
        assertTrue(
            handlerFor("light").execute("t", "system.interface.setOrder", params, caller)
                is CommandResult.Failure
        )
    }

    // --- describe ---

    @Test
    fun `describe does not advertise a contract the registry refused`() = runTest {
        // An external plugin trying to redefine a built-in interface: refused, but its descriptor
        // still claims `light`, and describe used to repeat that claim.
        val rival = lightContract.copy(
            version = 99,
            methods = listOf(MethodDescriptor("light.on", "on", "rival", exampleParameters = "{}", exampleOutput = "{}"))
        )
        for (permissions in listOf("light", "*")) {
            val handler = handlerFor(permissions) {
                it.registerExternal(FakeDefiner(rival, pluginId = "com.evil.light"), "com.evil")
            }
            val body = (handler.execute("t", "system.describe", "{}", caller) as CommandResult.Success).body!!
            val plugins = Json.parseToJsonElement(body).jsonObject["plugins"]!!.jsonArray.map { it.jsonObject }

            // It defines nothing in effect and offers nothing else, so it is not listed at all.
            assertFalse(plugins.any { it["pluginId"]!!.jsonPrimitive.content == "com.evil.light" })
            val definer = plugins.single { it["pluginId"]!!.jsonPrimitive.content == "interface.light" }
            assertEquals(listOf("light"), definer["definesInterfaces"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    // --- a token with no permissions ---

    @Test
    fun `a token with no permissions is not unrestricted`() = runTest {
        // Minted from an empty list it stores "", and "".split(",") is [""]: an empty entry that
        // startsWith-matches every required permission. The event gate filtered it; execute and
        // describe did not, so the same token was permissionless for events and unrestricted else.
        val handler = handlerFor("")

        val call = handler.execute("t", "light.on", "{}", caller)
        assertEquals("forbidden", (call as CommandResult.Failure).code)

        val body = (handler.execute("t", "system.describe", "{}", caller) as CommandResult.Success).body!!
        assertTrue(Json.parseToJsonElement(body).jsonObject["plugins"]!!.jsonArray.isEmpty())

        assertTrue(handler.subscribe("t", "light.changed", caller) is CommandResult.Failure)
        // Service methods and events stay open to any valid token, as for every other one.
        assertTrue(handler.subscribe("t", "system.plugins.changed", caller) is CommandResult.Success)
    }

    @Test
    fun `describe cross-references only the interfaces the token sees`() = runTest {
        // A token scoped to `light` looking at a provider of both `light` and `printer`: listing every
        // binding told it `printer` is wired there too.
        val printerContract = InterfaceContract(
            interfaceId = "printer",
            methods = listOf(MethodDescriptor("printer.cut", "cut", "printer", exampleParameters = "{}", exampleOutput = "{}"))
        )
        val handler = handlerFor("light") {
            it.registerBuiltIn(FakeDefiner(printerContract))
            it.registerBuiltIn(FakeProvider("p.dual", listOf("light", "printer")))
        }
        val body = (handler.execute("t", "system.describe", "{}", caller) as CommandResult.Success).body!!
        val json = Json.parseToJsonElement(body).jsonObject
        val plugins = json["plugins"]!!.jsonArray.map { it.jsonObject }

        val dual = plugins.single { it["pluginId"]!!.jsonPrimitive.content == "p.dual" }
        assertEquals(listOf("light"), dual["providesInterfaces"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The printer definer offers nothing this token may use, so it is not listed either.
        assertFalse(plugins.any { it["pluginId"]!!.jsonPrimitive.content == "interface.printer" })
        assertEquals(
            listOf("light"),
            json["interfaces"]!!.jsonArray.map { it.jsonObject["interfaceId"]!!.jsonPrimitive.content }
        )
    }
}
