package dev.duma.android.hal.service.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
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

    private fun handlerFor(permissions: String): ServiceCommandHandler {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakeDefiner(lightContract))
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
}
