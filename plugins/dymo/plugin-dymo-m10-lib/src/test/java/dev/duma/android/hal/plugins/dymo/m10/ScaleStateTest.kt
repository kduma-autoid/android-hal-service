package dev.duma.android.hal.plugins.dymo.m10

import dev.duma.android.hal.plugins.dymo.m10.ScaleState.Emission
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScaleStateTest {

    private fun g(grams: Double, status: ScaleStatus = ScaleStatus.STABLE) =
        ScaleReading(grams, grams, ScaleUnit.GRAMS, status)

    /** Offline detection off, so the settle tests can use long gaps between reports. */
    private fun state() = ScaleState(offlineMs = Long.MAX_VALUE)

    private fun List<Emission>.weights() = filterIsInstance<Emission.Weight>().map { it.snapshot }
    private fun List<Emission>.statuses() = filterIsInstance<Emission.Status>()

    @Test
    fun `first reading is emitted, unstable`() {
        val s = state()
        val out = s.update(g(100.0), 0)
        assertEquals(listOf(ScaleState.Snapshot(g(100.0), false)), out.weights())
        assertTrue(out.statuses().isEmpty())
    }

    @Test
    fun `changes below 1 g are not emitted`() {
        val s = state()
        s.update(g(100.0), 0)
        assertTrue(s.update(g(100.9), 100).isEmpty())
        assertEquals(100.9, s.snapshot()?.reading?.grams) // the snapshot still follows the scale
        assertEquals(listOf(101.0), s.update(g(101.0), 200).weights().map { it.reading.grams })
    }

    @Test
    fun `the threshold is measured from the last emitted reading`() {
        val s = state()
        s.update(g(100.0), 0)
        s.update(g(100.6), 10)
        assertEquals(1, s.update(g(101.2), 20).weights().size)
    }

    @Test
    fun `settles after 500 ms without a change`() {
        val s = state()
        s.update(g(100.0), 0)
        assertTrue(s.tick(499).isEmpty())
        assertEquals(listOf(ScaleState.Snapshot(g(100.0), true)), s.tick(500).weights())
        assertTrue(s.tick(1_000).isEmpty(), "settles only once")
        assertEquals(true, s.snapshot()?.stable)
    }

    @Test
    fun `settling is also detected inside update`() {
        val s = state()
        s.update(g(100.0), 0)
        assertEquals(listOf(true), s.update(g(100.2), 600).weights().map { it.stable })
    }

    @Test
    fun `a change before 500 ms restarts the timer`() {
        val s = state()
        s.update(g(100.0), 0)
        s.update(g(150.0), 400)
        assertTrue(s.tick(800).isEmpty())
        assertEquals(listOf(150.0), s.tick(900).weights().map { it.reading.grams })
    }

    @Test
    fun `a change after settling makes the reading unstable again`() {
        val s = state()
        s.update(g(100.0), 0)
        s.tick(500)
        val out = s.update(g(200.0), 600)
        assertEquals(listOf(false), out.weights().map { it.stable })
        assertEquals(false, s.snapshot()?.stable)
    }

    @Test
    fun `raw status stable does not make the reading stable`() {
        val s = state()
        // The M10 says "stable" while the weight is still moving.
        s.update(g(10.0), 0)
        s.update(g(50.0), 100)
        s.update(g(90.0), 200)
        assertEquals(false, s.snapshot()?.stable)
    }

    @Test
    fun `raw status inMotion does not prevent settling`() {
        val s = state()
        s.update(g(100.0, ScaleStatus.IN_MOTION), 0)
        assertEquals(listOf(true), s.tick(500).weights().map { it.stable })
    }

    @Test
    fun `error status keeps the reading unstable`() {
        val s = state()
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
        val s = state()
        s.update(g(100.0, ScaleStatus.FAULT), 0)
        s.update(g(100.0), 1_000)
        assertTrue(s.tick(1_400).isEmpty())
        assertEquals(listOf(true), s.tick(1_500).weights().map { it.stable })
    }

    @Test
    fun `status change emits status and weight with the previous status`() {
        val s = state()
        s.update(g(0.0, ScaleStatus.ZERO), 0)
        val out = s.update(g(0.4, ScaleStatus.STABLE), 100)
        assertEquals(listOf(Emission.Status(ScaleStatus.STABLE, ScaleStatus.ZERO)), out.statuses())
        assertEquals(1, out.weights().size)
    }

    @Test
    fun `unit change emits the reading without breaking stability`() {
        val s = state()
        s.update(g(283.5), 0)
        s.tick(500)
        val out = s.update(ScaleReading(283.5, 10.0, ScaleUnit.OUNCES, ScaleStatus.STABLE), 600)
        assertEquals(listOf(ScaleState.Snapshot(ScaleReading(283.5, 10.0, ScaleUnit.OUNCES, ScaleStatus.STABLE), true)), out.weights())
    }

    @Test
    fun `clear forgets the reading`() {
        val s = state()
        s.update(g(100.0), 0)
        s.tick(500)
        s.clear()
        assertNull(s.snapshot())
        assertTrue(s.tick(10_000).isEmpty())
        assertEquals(listOf(false), s.update(g(100.0), 20_000).weights().map { it.stable })
    }

    @Test
    fun `underZero readings stay negative`() {
        val s = state()
        val out = s.update(g(-90.0, ScaleStatus.UNDER_ZERO), 0)
        assertEquals(listOf(-90.0), out.weights().map { it.reading.grams })
        assertEquals(listOf(true), s.tick(500).weights().map { it.stable })
    }

    @Test
    fun `zero keeps the unit of the last non-zero reading`() {
        val s = state()
        s.update(g(122.0), 0)
        // The M10 reports zero in ounces even in gram mode.
        val out = s.update(ScaleReading(0.0, 0.0, ScaleUnit.OUNCES, ScaleStatus.ZERO), 100)
        assertEquals(ScaleUnit.GRAMS, s.snapshot()?.reading?.unit)
        assertEquals(listOf(ScaleUnit.GRAMS), out.weights().map { it.reading.unit })
        // A real switch to ounces still shows once there is weight.
        s.update(ScaleReading(122.0, 4.3, ScaleUnit.OUNCES, ScaleStatus.STABLE), 200)
        assertEquals(ScaleUnit.OUNCES, s.snapshot()?.reading?.unit)
    }

    @Test
    fun `zero before any weight keeps the reported unit`() {
        val s = state()
        s.update(ScaleReading(0.0, 0.0, ScaleUnit.OUNCES, ScaleStatus.ZERO), 0)
        assertEquals(ScaleUnit.OUNCES, s.snapshot()?.reading?.unit)
    }

    @Test
    fun `silence goes offline and drops the reading`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        s.tick(500)
        assertTrue(s.tick(2_999).isEmpty())
        assertEquals(listOf(Emission.Status(ScaleStatus.OFFLINE, ScaleStatus.STABLE)), s.tick(3_000))
        assertNull(s.snapshot())
        assertTrue(s.isOffline)
        assertTrue(s.tick(10_000).isEmpty(), "offline only once")
    }

    @Test
    fun `reports resuming after offline announce the status and a fresh unstable reading`() {
        val s = ScaleState()
        s.update(g(100.0), 0)
        s.tick(3_000)
        val out = s.update(g(100.0), 4_000)
        assertEquals(listOf(Emission.Status(ScaleStatus.STABLE, ScaleStatus.OFFLINE)), out.statuses())
        assertEquals(listOf(false), out.weights().map { it.stable })
        assertEquals(listOf(true), s.tick(4_500).weights().map { it.stable })
    }

    @Test
    fun `regular reports keep the scale online`() {
        val s = ScaleState()
        for (t in 0L..10_000L step 250) s.update(g(100.0), t)
        assertEquals(false, s.isOffline)
        assertEquals(true, s.snapshot()?.stable)
    }
}
