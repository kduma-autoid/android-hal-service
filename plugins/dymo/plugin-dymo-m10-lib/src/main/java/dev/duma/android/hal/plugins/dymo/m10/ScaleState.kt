package dev.duma.android.hal.plugins.dymo.m10

import kotlin.math.abs

/**
 * Turns the stream of raw readings into HAL events and computes the `stable` flag.
 *
 * The M10 reports status `stable` nearly all the time, so — as the reference app does — stability is
 * decided here with a settle timer: every weight change of at least [thresholdGrams] (against the
 * last *emitted* reading) restarts it, and the reading becomes stable once [settleMs] pass without
 * one. An error status (see [ScaleStatus.isError]) keeps the reading unstable; leaving it restarts
 * the timer.
 *
 * Emissions:
 * - [Emission.Weight] when the weight moves (stable=false), when it settles (stable=true), when the
 *   scale's status changes and when the displayed unit changes;
 * - [Emission.Status] when the scale's status changes.
 *
 * Pure and clock-injected so it can be unit tested; not thread-safe (the plugin calls it from its
 * single read loop and reads [snapshot] under its own lock).
 */
class ScaleState(
    private val settleMs: Long = SETTLE_MS,
    private val thresholdGrams: Double = THRESHOLD_GRAMS
) {
    /** A reading plus the computed stability. */
    data class Snapshot(val reading: ScaleReading, val stable: Boolean)

    sealed class Emission {
        data class Weight(val snapshot: Snapshot) : Emission()
        data class Status(val status: ScaleStatus, val previous: ScaleStatus) : Emission()
    }

    private var reading: ScaleReading? = null
    private var stable = false
    private var lastEmittedGrams: Double? = null
    private var lastChangeAt = 0L

    /** The latest reading with its stability, or null before the first one / after [clear]. */
    fun snapshot(): Snapshot? = reading?.let { Snapshot(it, stable) }

    fun update(next: ScaleReading, nowMs: Long): List<Emission> {
        val prev = reading
        reading = next
        val out = mutableListOf<Emission>()

        val statusChanged = prev != null && prev.status != next.status
        val unitChanged = prev != null && prev.unit != next.unit
        val emitted = lastEmittedGrams
        val moved = emitted == null || abs(next.grams - emitted) >= thresholdGrams

        if (moved || (statusChanged && (next.status.isError || prev!!.status.isError))) {
            stable = false
            lastChangeAt = nowMs
        }
        if (next.status.isError) stable = false

        if (statusChanged) out += Emission.Status(next.status, prev!!.status)
        if (moved || statusChanged || unitChanged) out += emitWeight()

        out += tick(nowMs)
        return out
    }

    /** Call periodically (also when no report arrives): emits the settle event once it is due. */
    fun tick(nowMs: Long): List<Emission> {
        val r = reading ?: return emptyList()
        if (stable || r.status.isError || nowMs - lastChangeAt < settleMs) return emptyList()
        stable = true
        return listOf(emitWeight())
    }

    /** Forget everything (device detached / closed). */
    fun clear() {
        reading = null
        stable = false
        lastEmittedGrams = null
        lastChangeAt = 0L
    }

    private fun emitWeight(): Emission.Weight {
        val r = reading!!
        lastEmittedGrams = r.grams
        return Emission.Weight(Snapshot(r, stable))
    }

    companion object {
        const val SETTLE_MS = 500L
        const val THRESHOLD_GRAMS = 1.0
    }
}
