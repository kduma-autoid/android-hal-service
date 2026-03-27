package dev.duma.android.hal.contract

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EventBusLoopProtectionTest {
    @Test
    fun `plugin does not receive own events`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<String>()

        bus.addPluginListener(
            listenerPluginId = "scanner",
            pattern = "*",
            callback = { event, _ -> received.add(event) }
        )

        bus.emit("scanner.barcode", "{}", sourcePluginId = "scanner")
        bus.emit("printer.done", "{}", sourcePluginId = "printer")

        assertEquals(listOf("printer.done"), received)
    }

    @Test
    fun `different plugin receives events`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<String>()

        bus.addPluginListener(
            listenerPluginId = "generic.scanner",
            pattern = "sunmi.scanner.*",
            callback = { event, _ -> received.add(event) }
        )

        bus.emit("sunmi.scanner.barcode", "{}", sourcePluginId = "sunmi.scanner")

        assertEquals(listOf("sunmi.scanner.barcode"), received)
    }
}
