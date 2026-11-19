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
 * Two quirks of the scale are absorbed here:
 * - at zero it always reports ounces, whatever it is set to — a zero reading keeps the last unit seen
 *   with a non-zero weight, so the unit doesn't flip at every return to zero;
 * - it streams a report every ~130–260 ms, so silence for [offlineMs] means it is off or another app
 *   took the USB interface over: the reading is dropped and the status becomes `offline` until
 *   reports resume.
 *
 * Emissions:
 * - [Emission.Weight] when the weight moves (stable=false), when it settles (stable=true), when the
 *   scale's status changes and when the displayed unit changes;
 * - [Emission.Status] when the status changes (including to and from `offline`).
 *
 * Pure and clock-injected so it can be unit tested; not thread-safe (the plugin calls it from its
 * single read loop and reads [snapshot] under its own lock).
 */
class ScaleState(
    private val settleMs: Long = SETTLE_MS,
    private val thresholdGrams: Double = THRESHOLD_GRAMS,
    private val offlineMs: Long = OFFLINE_MS
) {
    /** A reading plus the computed stability. */
    data class Snapshot(val reading: ScaleReading, val stable: Boolean)

    sealed class Emission {
        data class Weight(val snapshot: Snapshot) : Emission()
        data class Status(val status: ScaleStatus, val previous: ScaleStatus) : Emission()
    }

    private var reading: ScaleReading? = null
    /** Status of the last reading, or `offline`; survives the reading being dropped. */
    private var lastStatus: ScaleStatus? = null
    /** Unit of the last non-zero reading. */
    private var knownUnit: ScaleUnit? = null
    private var stable = false
    private var lastEmittedGrams: Double? = null
    private var lastChangeAt = 0L
    private var lastReportAt = 0L

    /** The latest reading with its stability, or null before the first one / while offline. */
    fun snapshot(): Snapshot? = reading?.let { Snapshot(it, stable) }

    /** Whether the scale went silent (see [offlineMs]). */
    val isOffline: Boolean get() = lastStatus == ScaleStatus.OFFLINE

    fun update(raw: ScaleReading, nowMs: Long): List<Emission> {
        val next = normalizeUnit(raw)
        val prev = reading
        val prevStatus = prev?.status ?: lastStatus
        reading = next
        lastStatus = next.status
        lastReportAt = nowMs
        val out = mutableListOf<Emission>()

        val statusChanged = prevStatus != null && prevStatus != next.status
        val unitChanged = prev != null && prev.unit != next.unit
        val emitted = lastEmittedGrams
        val moved = emitted == null || abs(next.grams - emitted) >= thresholdGrams

        if (moved || (statusChanged && (next.status.isError || prevStatus!!.isError))) {
            stable = false
            lastChangeAt = nowMs
        }
        if (next.status.isError) stable = false

        if (statusChanged) out += Emission.Status(next.status, prevStatus!!)
        if (moved || statusChanged || unitChanged) out += emitWeight()

        out += tick(nowMs)
        return out
    }

    /**
     * Call periodically (also when no report arrives): emits the settle event once it is due, and
     * `offline` once the scale has been silent for [offlineMs].
     */
    fun tick(nowMs: Long): List<Emission> {
        val r = reading ?: return emptyList()
        if (nowMs - lastReportAt >= offlineMs) {
            reading = null
            stable = false
            lastEmittedGrams = null
            lastStatus = ScaleStatus.OFFLINE
            return listOf(Emission.Status(ScaleStatus.OFFLINE, r.status))
        }
        if (stable || r.status.isError || nowMs - lastChangeAt < settleMs) return emptyList()
        stable = true
        return listOf(emitWeight())
    }

    /** Forget everything (device detached / closed). */
    fun clear() {
        reading = null
        lastStatus = null
        stable = false
        lastEmittedGrams = null
        lastChangeAt = 0L
        lastReportAt = 0L
    }

    private fun normalizeUnit(r: ScaleReading): ScaleReading {
        if (r.value != 0.0) {
            knownUnit = r.unit
            return r
        }
        val unit = knownUnit ?: return r
        return if (unit == r.unit) r else r.copy(unit = unit, value = 0.0)
    }

    private fun emitWeight(): Emission.Weight {
        val r = reading!!
        lastEmittedGrams = r.grams
        return Emission.Weight(Snapshot(r, stable))
    }

    companion object {
        const val SETTLE_MS = 500L
        const val THRESHOLD_GRAMS = 1.0
        const val OFFLINE_MS = 3_000L
    }
}
