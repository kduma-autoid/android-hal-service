package dev.duma.android.hal.service.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventBus
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PluginInitializationGenerationTest {

    private class LifecyclePlugin(
        override val pluginId: String,
        override val version: Int = 1
    ) : HalPlugin {
        val contexts = mutableListOf<PluginContext>()
        val callbacks = mutableListOf<HalPluginEventCallback?>()

        override fun isSupported() = true
        override fun getCapabilities() = listOf(pluginId)
        override fun getDescriptor() = PluginDescriptor.withFlatLists(
            pluginId = pluginId,
            name = pluginId,
            version = version,
            capabilities = listOf(pluginId),
            methods = listOf(
                MethodDescriptor(
                    name = "$pluginId.ping",
                    description = "ping",
                    requiredPermission = "test",
                    exampleParameters = "{}",
                    exampleOutput = "{}"
                )
            )
        )
        override fun initialize(pluginContext: PluginContext) { contexts += pluginContext }
        override suspend fun execute(method: String, params: String) = CommandResult.Success("{}")
        override fun setEventCallback(callback: HalPluginEventCallback?) { callbacks += callback }
    }

    private fun initialize(registry: PluginRegistry, eventBus: EventBus): Context =
        ApplicationProvider.getApplicationContext<Context>().also {
            registry.initializeAll(it, eventBus)
        }

    @Test
    fun `old context cannot change availability after same instance is reinitialized`() {
        val registry = PluginRegistry()
        val plugin = LifecyclePlugin("test.generation")
        registry.registerBuiltIn(plugin)
        val bus = EventBus()
        val appContext = initialize(registry, bus)
        initialize(registry, bus)

        plugin.contexts[0].setPluginAvailable(false)
        assertFalse("test.generation" in registry.getUnavailablePluginIds())

        plugin.contexts[1].setPluginAvailable(false)
        assertTrue("test.generation" in registry.getUnavailablePluginIds())

        // Re-register the same object after a full disconnect. Generation numbers must not reset,
        // otherwise context[1] could collide with the next active context for this same instance.
        registry.disconnectAll(appContext)
        registry.registerBuiltIn(plugin)
        initialize(registry, bus)
        plugin.contexts[1].setPluginAvailable(false)
        assertFalse("test.generation" in registry.getUnavailablePluginIds())
        plugin.contexts[2].setPluginAvailable(false)
        assertTrue("test.generation" in registry.getUnavailablePluginIds())
    }

    @Test
    fun `old displaced instance cannot change replacement or emit events`() = runTest {
        val registry = PluginRegistry()
        val old = LifecyclePlugin("test.same-id")
        registry.registerBuiltIn(old)
        val bus = EventBus()
        initialize(registry, bus)

        val replacement = LifecyclePlugin("test.same-id", version = 2)
        assertTrue(registry.registerExternal(replacement, "vendor.test"))
        val received = mutableListOf<EventBus.EventEnvelope>()
        val events = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.take(1).toList(received)
        }

        old.contexts.single().setPluginAvailable(false)
        assertFalse("test.same-id" in registry.getUnavailablePluginIds())
        old.contexts.single().emitEvent("test.stale", "{}")
        assertTrue(events.isActive)

        replacement.contexts.single().emitEvent("test.current", "{}")
        events.join()
        assertEquals("test.current", received.single().eventName)
    }

    @Test
    fun `old initialize event callback is ignored after reinitialize`() {
        val registry = PluginRegistry()
        val plugin = LifecyclePlugin("test.callback")
        val emitter = LifecyclePlugin("test.emitter")
        registry.registerBuiltIn(plugin)
        registry.registerBuiltIn(emitter)
        val bus = EventBus()
        initialize(registry, bus)
        var received = 0
        plugin.contexts[0].onEvent("test.*") { _, _ -> received++ }
        initialize(registry, bus)

        // The old subscription remains in EventBus, but its generation gate rejects delivery.
        emitter.contexts[1].emitEvent("test.stale", "{}")
        assertEquals(0, received)

        plugin.contexts[1].onEvent("test.*") { _, _ -> received++ }
        emitter.contexts[1].emitEvent("test.current", "{}")
        assertEquals(1, received)

        // The old plugin Binder/event callback is gated by the same generation token.
        val events = mutableListOf<EventBus.EventEnvelope>()
        bus.addPluginListener("other", "test.*") { name, data ->
            events += EventBus.EventEnvelope(name, data, "test.callback")
        }
        plugin.callbacks[0]!!.onEvent("test.late", "{}")
        assertEquals(0, events.size)
        plugin.callbacks[1]!!.onEvent("test.now", "{}")
        assertEquals(1, events.size)
    }
}
