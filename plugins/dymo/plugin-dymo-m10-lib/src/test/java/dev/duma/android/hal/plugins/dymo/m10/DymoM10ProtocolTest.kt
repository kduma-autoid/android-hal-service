package dev.duma.android.hal.plugins.dymo.m10

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DymoM10ProtocolTest {

    private fun report(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `grams report`() {
        // 1250 g = 0x04E2, exponent 0, status stable
        val r = assertNotNull(DymoM10Protocol.parse(report(3, 4, 2, 0, 0xE2, 0x04)))
        assertEquals(1250.0, r.grams)
        assertEquals(1250.0, r.value)
        assertEquals(ScaleUnit.GRAMS, r.unit)
        assertEquals(ScaleStatus.STABLE, r.status)
    }

    @Test
    fun `ounces report with a negative exponent`() {
        // 12.3 oz = 123 · 10^-1, exponent byte 0xFF
        val r = assertNotNull(DymoM10Protocol.parse(report(3, 4, 11, 0xFF, 123, 0)))
        assertEquals(12.3, r.value)
        assertEquals(ScaleUnit.OUNCES, r.unit)
        assertEquals(348.7, r.grams) // 12.3 · 28.349523125 = 348.699…
    }

    @Test
    fun `high byte and unsigned low byte`() {
        // 0xFF + 0x01·256 = 511 — the low byte must not be read as -1
        assertEquals(511.0, DymoM10Protocol.parse(report(3, 4, 2, 0, 0xFF, 0x01))?.grams)
    }

    @Test
    fun `underZero weight is negative`() {
        // Captured from an M10: tared, then the load removed — 90 g below zero, sent unsigned.
        val r = assertNotNull(DymoM10Protocol.parse(report(3, 5, 2, 0, 0x5a, 0)))
        assertEquals(-90.0, r.grams)
        assertEquals(-90.0, r.value)
        assertEquals(ScaleStatus.UNDER_ZERO, r.status)
    }

    @Test
    fun `captured ounce report`() {
        // 122 g switched to ounces on the scale: 03 04 0b ff 2b 00 = 4.3 oz
        val r = assertNotNull(DymoM10Protocol.parse(report(3, 4, 11, 0xFF, 0x2b, 0)))
        assertEquals(4.3, r.value)
        assertEquals(121.9, r.grams)
    }

    @Test
    fun `zero reading`() {
        val r = assertNotNull(DymoM10Protocol.parse(report(3, 2, 2, 0, 0, 0)))
        assertEquals(0.0, r.grams)
        assertEquals(ScaleStatus.ZERO, r.status)
    }

    @Test
    fun `longer buffers are accepted`() {
        assertEquals(10.0, DymoM10Protocol.parse(report(3, 4, 2, 0, 10, 0, 0, 0))?.grams)
    }

    @Test
    fun `non-data reports and short buffers are ignored`() {
        assertNull(DymoM10Protocol.parse(report(4, 4, 2, 0, 10, 0)))
        assertNull(DymoM10Protocol.parse(report(3, 4, 2, 0, 10)))
        assertNull(DymoM10Protocol.parse(null))
    }

    @Test
    fun `unknown unit codes fall back to grams`() {
        assertEquals(ScaleUnit.GRAMS, DymoM10Protocol.parse(report(3, 4, 7, 0, 10, 0))?.unit)
    }

    @Test
    fun `status mapping`() {
        val expected = listOf(
            0 to ScaleStatus.UNKNOWN,
            1 to ScaleStatus.FAULT,
            2 to ScaleStatus.ZERO,
            3 to ScaleStatus.IN_MOTION,
            4 to ScaleStatus.STABLE,
            5 to ScaleStatus.UNDER_ZERO,
            6 to ScaleStatus.OVER_WEIGHT,
            7 to ScaleStatus.NEED_CALIBRATION,
            8 to ScaleStatus.NEED_ZEROING,
            9 to ScaleStatus.UNKNOWN
        )
        for ((byte, status) in expected) assertEquals(status, DymoM10Protocol.mapStatus(byte), "byte $byte")
        assertEquals(
            listOf("unknown", "fault", "zero", "inMotion", "stable", "underZero", "overWeight", "needCalibration", "needZeroing", "offline"),
            ScaleStatus.entries.map { it.json }
        )
    }
}
