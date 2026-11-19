package dev.duma.android.hal.service.plugin

import android.view.KeyEvent
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.KeyboardDeviceId
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.service.keyboardfilter.HalKeyboardFilterService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keyboard devices declared by plugins and aggregated for HAL's single keyboard filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginRegistryKeyboardFilterTest {

    private val msr = KeyboardDeviceId(0x1A86, 0x7523)
    private val scanner = KeyboardDeviceId(0x324F, 0x0113)

    private class FakePlugin(
        override val pluginId: String,
        private val devices: () -> List<KeyboardDeviceId>,
        override val version: Int = 1,
        private val supported: Boolean = true,
    ) : HalPlugin {
        override fun isSupported() = supported
        override fun getCapabilities() = listOf(pluginId)
        override fun getDescriptor() = PluginDescriptor.withFlatLists(
            pluginId = pluginId, name = pluginId, version = version, capabilities = getCapabilities(),
            methods = listOf(
                MethodDescriptor("$pluginId.get", "get", pluginId, exampleParameters = "{}", exampleOutput = "{}"),
            ),
        )
        override fun initialize(pluginContext: PluginContext) {}
        override suspend fun execute(method: String, params: String) = CommandResult.unsupportedMethod(method)
        override fun setEventCallback(callback: HalPluginEventCallback?) {}
        override fun getKeyboardFilterDevices() = devices()
    }

    @Test
    fun devicesOfAllRegisteredPluginsAreAggregated() {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakePlugin("sunmi.card", { listOf(msr) }))
        registry.registerExternal(FakePlugin("sunmi.scanner", { listOf(scanner, scanner) }), "bundle")
        registry.registerBuiltIn(FakePlugin("plain", { emptyList() }))

        assertEquals(setOf(msr, scanner), registry.keyboardFilterDevices)
        assertEquals(setOf(msr), registry.keyboardFilterDevicesOf("sunmi.card"))
        assertEquals(setOf(scanner), registry.keyboardFilterDevicesOf("sunmi.scanner"))
        assertEquals(emptySet<KeyboardDeviceId>(), registry.keyboardFilterDevicesOf("plain"))
    }

    @Test
    fun unsupportedPluginContributesNothing() {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakePlugin("sunmi.card", { listOf(msr) }, supported = false))
        assertTrue(registry.keyboardFilterDevices.isEmpty())
    }

    @Test
    fun failingPluginStillRegistersWithoutDevices() {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakePlugin("broken", { throw IllegalStateException("binder died") }))
        assertTrue("broken" in registry.getAllDescriptors().map { it.pluginId })
        assertTrue(registry.keyboardFilterDevices.isEmpty())
    }

    @Test
    fun displacementAndDisconnectSwapTheDevices() {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakePlugin("sunmi.card", { listOf(msr) }))

        // The bundle's plugin under the same id takes over, with its own device list.
        val external = FakePlugin("sunmi.card", { listOf(scanner) })
        assertTrue(registry.registerExternal(external, "bundle"))
        assertEquals(setOf(scanner), registry.keyboardFilterDevices)

        // When it goes away the displaced built-in returns, and its devices with it.
        registry.unregisterExternal(external)
        assertEquals(setOf(msr), registry.keyboardFilterDevices)
    }

    @Test
    fun disconnectWithoutBuiltInClearsTheDevices() {
        val registry = PluginRegistry()
        val external = FakePlugin("sunmi.card", { listOf(msr) })
        registry.registerExternal(external, "bundle")
        registry.unregisterExternal(external)
        assertTrue(registry.keyboardFilterDevices.isEmpty())
    }

    @Test
    fun filterConsumesOnlyDownAndUpFromRegisteredDevices() {
        val devices = setOf(msr)
        assertTrue(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_DOWN, 0x1A86, 0x7523, devices))
        assertTrue(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_UP, 0x1A86, 0x7523, devices))
        assertFalse(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_MULTIPLE, 0x1A86, 0x7523, devices))
        assertFalse(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_DOWN, 0x1A86, 0x0001, devices))
        assertFalse(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_DOWN, 0x1A86, 0x7523, emptySet()))
    }
}
