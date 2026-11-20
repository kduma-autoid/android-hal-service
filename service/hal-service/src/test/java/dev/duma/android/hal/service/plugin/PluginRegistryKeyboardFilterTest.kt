package dev.duma.android.hal.service.plugin

import android.view.InputDevice
import android.view.KeyEvent
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.KeyOrigin
import dev.duma.android.hal.contract.KeyboardFilterRule
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

/** Keyboard filter rules declared by plugins and aggregated for HAL's single keyboard filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginRegistryKeyboardFilterTest {

    private val msr = KeyboardFilterRule.UsbDevice(0x1A86, 0x7523)
    private val scanner = KeyboardFilterRule.UsbDevice(0x324F, 0x0113)

    private class FakePlugin(
        override val pluginId: String,
        private val rules: () -> List<KeyboardFilterRule>,
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
        override fun getKeyboardFilterRules() = rules()
    }

    @Test
    fun devicesOfAllRegisteredPluginsAreAggregated() {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakePlugin("sunmi.card", { listOf(msr) }))
        registry.registerExternal(FakePlugin("sunmi.scanner", { listOf(scanner, scanner) }), "bundle")
        registry.registerBuiltIn(FakePlugin("plain", { emptyList() }))

        assertEquals(setOf(msr, scanner), registry.keyboardFilterRules)
        assertEquals(setOf(msr), registry.keyboardFilterRulesOf("sunmi.card"))
        assertEquals(setOf(scanner), registry.keyboardFilterRulesOf("sunmi.scanner"))
        assertEquals(emptySet<KeyboardFilterRule>(), registry.keyboardFilterRulesOf("plain"))
    }

    @Test
    fun unsupportedPluginContributesNothing() {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakePlugin("sunmi.card", { listOf(msr) }, supported = false))
        assertTrue(registry.keyboardFilterRules.isEmpty())
    }

    @Test
    fun failingPluginStillRegistersWithoutDevices() {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakePlugin("broken", { throw IllegalStateException("binder died") }))
        assertTrue("broken" in registry.getAllDescriptors().map { it.pluginId })
        assertTrue(registry.keyboardFilterRules.isEmpty())
    }

    @Test
    fun displacementAndDisconnectSwapTheDevices() {
        val registry = PluginRegistry()
        registry.registerBuiltIn(FakePlugin("sunmi.card", { listOf(msr) }))

        // The bundle's plugin under the same id takes over, with its own device list.
        val external = FakePlugin("sunmi.card", { listOf(scanner) })
        assertTrue(registry.registerExternal(external, "bundle"))
        assertEquals(setOf(scanner), registry.keyboardFilterRules)

        // When it goes away the displaced built-in returns, and its devices with it.
        registry.unregisterExternal(external)
        assertEquals(setOf(msr), registry.keyboardFilterRules)
    }

    @Test
    fun disconnectWithoutBuiltInClearsTheDevices() {
        val registry = PluginRegistry()
        val external = FakePlugin("sunmi.card", { listOf(msr) })
        registry.registerExternal(external, "bundle")
        registry.unregisterExternal(external)
        assertTrue(registry.keyboardFilterRules.isEmpty())
    }

    @Test
    fun filterConsumesOnlyDownAndUpMatchingARegisteredRule() {
        val marker = 0x98123456.toInt()
        val rules = setOf(msr, KeyboardFilterRule.EventSource(marker))
        val fromMsr = KeyOrigin(0x1A86, 0x7523, InputDevice.SOURCE_KEYBOARD)
        val injected = KeyOrigin(0, 0, marker)
        assertTrue(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_DOWN, fromMsr, rules))
        assertTrue(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_UP, fromMsr, rules))
        assertTrue(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_DOWN, injected, rules))
        assertTrue(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_UP, injected, rules))
        assertFalse(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_MULTIPLE, fromMsr, rules))
        assertFalse(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_DOWN, KeyOrigin(0x1A86, 0x0001, InputDevice.SOURCE_KEYBOARD), rules))
        assertFalse(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_DOWN, KeyOrigin(0, 0, InputDevice.SOURCE_KEYBOARD), rules))
        assertFalse(HalKeyboardFilterService.shouldConsume(KeyEvent.ACTION_DOWN, fromMsr, emptySet()))
    }
}
