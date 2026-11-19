package dev.duma.android.hal.plugins.dymo.m10

import kotlin.math.pow
import kotlin.math.roundToLong

/** Status byte of a Dymo M10 data report (byte 1). [json] is the name used in the HAL API. */
enum class ScaleStatus(val json: String, val isError: Boolean = false) {
    UNKNOWN("unknown", isError = true),
    FAULT("fault", isError = true),
    ZERO("zero"),
    IN_MOTION("inMotion"),
    STABLE("stable"),
    UNDER_ZERO("underZero"),
    OVER_WEIGHT("overWeight", isError = true),
    NEED_CALIBRATION("needCalibration", isError = true),
    NEED_ZEROING("needZeroing", isError = true),

    /** Not in the protocol: set by [ScaleState] when the scale stopped sending reports. */
    OFFLINE("offline", isError = true);
}

/** Unit the scale is set to (its g/oz button). */
enum class ScaleUnit(val json: String) { GRAMS("g"), OUNCES("oz") }

/**
 * One parsed data report.
 *
 * @property value weight as shown on the display, in [unit]
 * @property grams the same weight in grams (0.1 g resolution)
 */
data class ScaleReading(
    val grams: Double,
    val value: Double,
    val unit: ScaleUnit,
    val status: ScaleStatus
)

/**
 * The Dymo M10 USB HID protocol (VID 0x0922, PID 0x8003). Read-only: the scale streams 6-byte input
 * reports on its interrupt IN endpoint and takes no commands. Ported from `ScaleReport.cs` in
 * kduma-OSS/CS-usb-scale-counter.
 *
 * ```
 * [0] report id (3 = data report; anything else is ignored)
 * [1] status   (see [mapStatus])
 * [2] unit     (2 = grams, 11 = ounces)
 * [3] signed scaling exponent
 * [4] weight LSB
 * [5] weight MSB
 * ```
 * weight = (LSB + MSB·256) · 10^exponent, in the given unit; negative when the status is underZero.
 *
 * Observed on hardware: the scale streams a report every ~130–260 ms even when nothing changes; at
 * zero it always reports ounces (`03 02 0b ff 00 00`), whatever unit it is set to (see [ScaleState]);
 * and it says `stable` (4) even while the weight is still changing — `inMotion` never shows up.
 */
object DymoM10Protocol {
    const val VENDOR_ID = 0x0922
    const val PRODUCT_ID = 0x8003

    const val REPORT_SIZE = 6
    const val DATA_REPORT_ID: Byte = 3
    const val UNIT_GRAMS = 2
    const val UNIT_OUNCES = 11

    const val GRAMS_PER_OUNCE = 28.349523125

    /** Parses one input report; null for short buffers and non-data reports. */
    fun parse(report: ByteArray?): ScaleReading? {
        if (report == null || report.size < REPORT_SIZE || report[0] != DATA_REPORT_ID) return null

        val exponent = report[3].toInt() // signed byte
        val raw = (report[4].toInt() and 0xFF) + (report[5].toInt() and 0xFF) * 256
        val status = mapStatus(report[1].toInt() and 0xFF)
        // The weight is an unsigned magnitude: below zero the M10 sends e.g. 90 g with status
        // underZero (verified on hardware), so the sign comes from the status.
        val sign = if (status == ScaleStatus.UNDER_ZERO) -1 else 1
        val value = sign * round(raw * 10.0.pow(exponent), decimals = maxOf(0, -exponent))

        val unit = if ((report[2].toInt() and 0xFF) == UNIT_OUNCES) ScaleUnit.OUNCES else ScaleUnit.GRAMS
        val grams = if (unit == ScaleUnit.OUNCES) round(value * GRAMS_PER_OUNCE, 1) else round(value, 1)

        return ScaleReading(grams, value, unit, status)
    }

    fun mapStatus(status: Int): ScaleStatus = when (status) {
        1 -> ScaleStatus.FAULT
        2 -> ScaleStatus.ZERO
        3 -> ScaleStatus.IN_MOTION
        4 -> ScaleStatus.STABLE
        5 -> ScaleStatus.UNDER_ZERO
        6 -> ScaleStatus.OVER_WEIGHT
        7 -> ScaleStatus.NEED_CALIBRATION
        8 -> ScaleStatus.NEED_ZEROING
        else -> ScaleStatus.UNKNOWN
    }

    /** Rounds away binary noise from `raw · 10^exp` (e.g. 12.299999… → 12.3). */
    private fun round(v: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return (v * f).roundToLong() / f
    }
}
