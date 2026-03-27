package dev.duma.android.hal.contract

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusFlowTest {
    @Test
    fun `events delivered to flow collectors`() = runTest {
        val bus = EventBus()
        val collected = mutableListOf<EventBus.EventEnvelope>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.take(2).toList(collected)
        }

        bus.emit("a", "{}", "p1")
        bus.emit("b", "{}", "p2")
        job.join()

        assertEquals(2, collected.size)
        assertEquals("a", collected[0].eventName)
        assertEquals("b", collected[1].eventName)
    }
}
