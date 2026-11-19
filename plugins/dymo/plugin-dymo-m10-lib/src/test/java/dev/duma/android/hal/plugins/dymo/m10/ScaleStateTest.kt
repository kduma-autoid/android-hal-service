package dev.duma.android.hal.plugins.dymo.m10

import dev.duma.android.hal.plugins.dymo.m10.ScaleState.Emission
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScaleStateTest {

    private fun g(grams: Double, status: ScaleStatus = ScaleStatus.STABLE) =
        ScaleReading(grams, grams, ScaleUnit.GRAMS, status)

    private fun List<Emission>.weights() = filterIsInstance<Emission.Weight>().map { it.snapshot }
    private fun List<Emission>.statuses() = filterIsInstance<Emission.Status>()

    @Test
    fun `first reading is emitted, unstable`() {
        val s = ScaleState()
        val out = s.update(g(100.0), 0)
        assertEquals(listOf(ScaleState.Snapshot(g(100.0), false)), out.weights())
        assertTrue(out.statuses().isEmpty())
    }

    @Test
    fun `changes below 1 g are not emitted`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        assertTrue(s.update(g(100.9), 100).isEmpty())
        assertEquals(100.9, s.snapshot()?.reading?.grams) // the snapshot still follows the scale
        assertEquals(listOf(101.0), s.update(g(101.0), 200).weights().map { it.reading.grams })
    }

    @Test
    fun `the threshold is measured from the last emitted reading`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        s.update(g(100.6), 10)
        assertEquals(1, s.update(g(101.2), 20).weights().size)
    }

    @Test
    fun `settles after 500 ms without a change`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        assertTrue(s.tick(499).isEmpty())
        assertEquals(listOf(ScaleState.Snapshot(g(100.0), true)), s.tick(500).weights())
        assertTrue(s.tick(1_000).isEmpty(), "settles only once")
        assertEquals(true, s.snapshot()?.stable)
    }

    @Test
    fun `settling is also detected inside update`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        assertEquals(listOf(true), s.update(g(100.2), 600).weights().map { it.stable })
    }

    @Test
    fun `a change before 500 ms restarts the timer`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        s.update(g(150.0), 400)
        assertTrue(s.tick(800).isEmpty())
        assertEquals(listOf(150.0), s.tick(900).weights().map { it.reading.grams })
    }

    @Test
    fun `a change after settling makes the reading unstable again`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        s.tick(500)
        val out = s.update(g(200.0), 600)
        assertEquals(listOf(false), out.weights().map { it.stable })
        assertEquals(false, s.snapshot()?.stable)
    }

    @Test
    fun `raw status stable does not make the reading stable`() {
        val s = ScaleState()
        // The M10 says "stable" while the weight is still moving.
        s.update(g(10.0), 0)
        s.update(g(50.0), 100)
        s.update(g(90.0), 200)
        assertEquals(false, s.snapshot()?.stable)
    }

    @Test
    fun `raw status inMotion does not prevent settling`() {
        val s = ScaleState()
        s.update(g(100.0, ScaleStatus.IN_MOTION), 0)
        assertEquals(listOf(true), s.tick(500).weights().map { it.stable })
    }

    @Test
    fun `error status keeps the reading unstable`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        s.tick(500)
        val out = s.update(g(100.0, ScaleStatus.OVER_WEIGHT), 600)
        assertEquals(listOf(Emission.Status(ScaleStatus.OVER_WEIGHT, ScaleStatus.STABLE)), out.statuses())
        assertEquals(listOf(false), out.weights().map { it.stable })
        assertTrue(s.tick(5_000).isEmpty())
        assertEquals(false, s.snapshot()?.stable)
    }

    @Test
    fun `leaving an error state restarts the settle timer`() {
        val s = ScaleState()
        s.update(g(100.0, ScaleStatus.FAULT), 0)
        s.update(g(100.0), 1_000)
        assertTrue(s.tick(1_400).isEmpty())
        assertEquals(listOf(true), s.tick(1_500).weights().map { it.stable })
    }

    @Test
    fun `status change emits status and weight with the previous status`() {
        val s = ScaleState()
        s.update(g(0.0, ScaleStatus.ZERO), 0)
        val out = s.update(g(0.4, ScaleStatus.STABLE), 100)
        assertEquals(listOf(Emission.Status(ScaleStatus.STABLE, ScaleStatus.ZERO)), out.statuses())
        assertEquals(1, out.weights().size)
    }

    @Test
    fun `unit change emits the reading without breaking stability`() {
        val s = ScaleState()
        s.update(g(283.5), 0)
        s.tick(500)
        val out = s.update(ScaleReading(283.5, 10.0, ScaleUnit.OUNCES, ScaleStatus.STABLE), 600)
        assertEquals(listOf(ScaleState.Snapshot(ScaleReading(283.5, 10.0, ScaleUnit.OUNCES, ScaleStatus.STABLE), true)), out.weights())
    }

    @Test
    fun `clear forgets the reading`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        s.tick(500)
        s.clear()
        assertNull(s.snapshot())
        assertTrue(s.tick(10_000).isEmpty())
        assertEquals(listOf(false), s.update(g(100.0), 20_000).weights().map { it.stable })
    }
}
