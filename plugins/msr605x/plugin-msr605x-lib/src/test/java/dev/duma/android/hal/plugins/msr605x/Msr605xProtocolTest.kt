package dev.duma.android.hal.plugins.msr605x

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Msr605xProtocolTest {

    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) when (p) {
            is String -> out.write(p.toByteArray(Charsets.ISO_8859_1))
            is Int -> out.write(p)
            is ByteArray -> out.write(p)
            else -> error("unsupported $p")
        }
        return out.toByteArray()
    }

    private val esc = 0x1B
    private val fs = 0x1C

    // --- Framing ---

    @Test
    fun `short command fits one packet with first and last bits`() {
        val packets = Msr605xProtocol.framePackets(Msr605xProtocol.CMD_READ)
        assertEquals(1, packets.size)
        assertEquals(64, packets[0].size)
        assertEquals(0xC2, packets[0][0].toInt() and 0xFF)
        assertEquals(esc.toByte(), packets[0][1])
        assertEquals('r'.code.toByte(), packets[0][2])
        assertTrue(packets[0].drop(3).all { it == 0.toByte() })
    }

    @Test
    fun `long payload is split into 62-byte chunks`() {
        val data = ByteArray(130) { it.toByte() }
        val packets = Msr605xProtocol.framePackets(data)
        assertEquals(3, packets.size)
        assertTrue(packets.all { it.size == 64 })
        assertEquals(0x80 or 62, packets[0][0].toInt() and 0xFF)
        assertEquals(62, packets[1][0].toInt() and 0xFF)
        assertEquals(0x40 or 6, packets[2][0].toInt() and 0xFF)

        val assembler = Msr605xProtocol.PacketAssembler()
        packets.forEach(assembler::add)
        assertTrue(assembler.complete)
        assertContentEquals(data, assembler.bytes())
    }

    @Test
    fun `assembler drops padding and tracks completeness`() {
        val assembler = Msr605xProtocol.PacketAssembler()
        assembler.add(ByteArray(64).also { it[0] = 0x82.toByte(); it[1] = esc.toByte(); it[2] = '0'.code.toByte() })
        assertFalse(assembler.complete)
        assertContentEquals(bytes(esc, "0"), assembler.bytes())
        assembler.add(ByteArray(64).also { it[0] = 0x41; it[1] = 7 })
        assertTrue(assembler.complete)
        assertContentEquals(bytes(esc, "0", 7), assembler.bytes())
    }

    // --- ISO read ---

    @Test
    fun `iso read strips sentinels and maps empty and bad tracks`() {
        val response = bytes(
            esc, "s",
            esc, 1, "%B4111111111111111^DOE/JOHN^2512101123400001?",
            esc, 2, ";4111111111111111=25121011234000010001?",
            esc, 3, esc, "+",
            "?", fs, esc, "0"
        )
        val r = Msr605xProtocol.parseIsoRead(response)
        assertTrue(r.ok)
        assertEquals("B4111111111111111^DOE/JOHN^2512101123400001", r.track1)
        assertEquals("4111111111111111=25121011234000010001", r.track2)
        assertEquals("", r.track3)

        val bad = Msr605xProtocol.parseIsoRead(
            bytes(esc, "s", esc, 1, esc, "*", esc, 2, ";123?", esc, 3, ";456?", "?", fs, esc, "1")
        )
        assertEquals('1', bad.status)
        assertFalse(bad.ok)
        assertNull(bad.track1)
        assertEquals("123", bad.track2)
        assertEquals("456", bad.track3)
    }

    @Test
    fun `status is a bitmask of failed tracks`() {
        assertEquals(emptyList(), Msr605xProtocol.failedTracks('0'))
        assertEquals(listOf(1), Msr605xProtocol.failedTracks('1'))
        assertEquals(listOf(3), Msr605xProtocol.failedTracks('4'))
        assertEquals(listOf(1, 3), Msr605xProtocol.failedTracks('5'))
        assertEquals(listOf(1, 2, 3), Msr605xProtocol.failedTracks('7'))
        assertNull(Msr605xProtocol.failedTracks('9'))
        assertNull(Msr605xProtocol.failedTracks(null))

        assertEquals("ok", Msr605xProtocol.describeStatus('0'))
        assertEquals("failed on track 3", Msr605xProtocol.describeStatus('4'))
        assertEquals("failed on track 1+2+3", Msr605xProtocol.describeStatus('7'))
        assertEquals("invalid card swipe in write mode", Msr605xProtocol.describeStatus('9'))
        assertEquals("no response", Msr605xProtocol.describeStatus(null))
    }

    @Test
    fun `non data-block response yields null status`() {
        val r = Msr605xProtocol.parseIsoRead(bytes(esc, "A"))
        assertNull(r.status)
        assertNull(r.track1)
    }

    // --- Raw read ---

    @Test
    fun `raw read takes length minus one bytes per track like the Python driver`() {
        val response = bytes(
            esc, "s",
            esc, 1, 4, 0xAA, 0xBB, 0xCC,
            esc, 2, 1,
            esc, 3, 2, 0x11,
            "?", fs, esc, "0"
        )
        val r = Msr605xProtocol.parseRawRead(response)
        assertTrue(r.ok)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), r.track1)
        assertContentEquals(ByteArray(0), r.track2)
        assertContentEquals(byteArrayOf(0x11), r.track3)
    }

    // --- Builders ---

    @Test
    fun `iso write block`() {
        assertContentEquals(
            bytes(esc, "w", esc, "s", esc, 1, "ABC", esc, 2, "123", esc, 3, "?", fs),
            Msr605xProtocol.buildIsoWrite("ABC", "123", null)
        )
    }

    @Test
    fun `raw write reverses bits and prefixes lengths`() {
        assertContentEquals(
            bytes(esc, "n", esc, "s", esc, 1, 2, 0x80, 0x0F, esc, 2, 0, esc, 3, 1, 0xFF, "?", fs),
            Msr605xProtocol.buildRawWrite(byteArrayOf(0x01, 0xF0.toByte()), null, byteArrayOf(0xFF.toByte()))
        )
        assertEquals(0x2C.toByte(), Msr605xProtocol.reverseBits(0x34))
    }

    @Test
    fun `erase mask bpi and bpc`() {
        assertContentEquals(bytes(esc, "c", 0x05), Msr605xProtocol.buildErase(true, false, true))
        assertContentEquals(bytes(esc, "b", 0xA1), Msr605xProtocol.buildBpi(1, 210))
        assertContentEquals(bytes(esc, "b", 0x4B), Msr605xProtocol.buildBpi(2, 75))
        assertContentEquals(bytes(esc, "b", 0xC0), Msr605xProtocol.buildBpi(3, 75))
        assertContentEquals(bytes(esc, "o", 7, 5, 5), Msr605xProtocol.buildBpc(7, 5, 5))
        assertFailsWith<IllegalArgumentException> { Msr605xProtocol.buildBpi(1, 100) }
        assertFailsWith<IllegalArgumentException> { Msr605xProtocol.buildBpc(4, 5, 5) }
    }

    @Test
    fun `iso validation`() {
        assertTrue(Msr605xProtocol.isValidIso(1, "B4111^DOE/JOHN^25"))
        assertFalse(Msr605xProtocol.isValidIso(1, "lowercase"))
        assertFalse(Msr605xProtocol.isValidIso(1, "%SENTINEL"))
        assertTrue(Msr605xProtocol.isValidIso(2, "4111=2512"))
        assertFalse(Msr605xProtocol.isValidIso(2, "4111A"))
        assertFalse(Msr605xProtocol.isValidIso(2, "1".repeat(38)))
        assertTrue(Msr605xProtocol.isValidIso(3, "1".repeat(104)))
    }

    // --- Short replies ---

    @Test
    fun `short reply parsers`() {
        assertTrue(Msr605xProtocol.isOk(bytes(esc, "0")))
        assertEquals("3", Msr605xProtocol.parseModel(bytes(esc, "3", "S")))
        assertEquals("REVH1.21", Msr605xProtocol.parseFirmware(bytes(esc, "REVH1.21")))
        assertEquals("hi", Msr605xProtocol.parseCoercivity(bytes(esc, "h")))
        assertEquals("lo", Msr605xProtocol.parseCoercivity(bytes(esc, "l")))
        assertEquals(61 to 22, Msr605xProtocol.parseLeadingZeros(bytes(esc, 61, 22)))
        assertEquals(Triple(8, 8, 8), Msr605xProtocol.parseBpc(bytes(esc, "0", 8, 8, 8)))
        assertNull(Msr605xProtocol.parseBpc(bytes(esc, "A")))
        assertTrue(Msr605xProtocol.isCommTestOk(bytes(esc, "y")))
    }
}
