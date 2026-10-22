package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SsiCodecTest {
    @Test
    fun `decodes literal ACK A4 and C6 frames captured from scanner logs`() {
        val ack = SsiCodec.decode(hex("04 D0 00 00 FF 2C"))
        assertEquals(SsiCodec.ACK, ack.opcode)
        assertEquals(SsiCodec.SOURCE_SCANNER, ack.source)

        val revisionFrame = byteArrayOf(0x60, 0xA4.toByte(), 0x00, 0x00) +
            "Product Name:SS2222 Product ID:2502D57G02104 Hardware version:V3.0.A Firmware version:2.4.94"
                .encodeToByteArray() + hex("E2 43")
        val revision = SsiCodec.decode(revisionFrame)
        assertEquals(SsiCodec.REPLY_REVISION, revision.opcode)
        assertEquals("Product Name:SS2222", revision.data.decodeToString().substringBefore(" Product ID:"))

        val parameter = SsiCodec.decode(hex("08 C6 00 00 FF F2 02 00 FD 3F"))
        assertEquals(SsiCodec.PARAM_SEND, parameter.opcode)
        assertContentEquals(byteArrayOf(0), SsiCodec.parameterReplyValue(parameter, 0xF202))
    }

    @Test
    fun `SSI packet round trips and rejects corruption`() {
        val encoded = SsiCodec.encode(0xC7, byteArrayOf(0xF2.toByte(), 0x02))
        val decoded = SsiCodec.decode(encoded)

        assertEquals(0xC7, decoded.opcode)
        assertEquals(SsiCodec.SOURCE_HOST, decoded.source)
        assertContentEquals(byteArrayOf(0xF2.toByte(), 0x02), decoded.data)

        encoded[4] = (encoded[4].toInt() xor 1).toByte()
        assertNull(SsiCodec.decodeOrNull(encoded))
    }

    @Test
    fun `SSI payload length is bounded by one byte header`() {
        SsiCodec.encode(0xC7, ByteArray(251))
        assertFailsWith<IllegalArgumentException> { SsiCodec.encode(0xC7, ByteArray(252)) }
    }

    @Test
    fun `parameter ids are encoded without ambiguous F0 short form`() {
        assertContentEquals(byteArrayOf(0x38), SsiCodec.parameterBytes(0x38))
        assertContentEquals(byteArrayOf(0xF2.toByte(), 0x02), SsiCodec.parameterBytes(0xF202))
        assertFailsWith<IllegalArgumentException> { SsiCodec.parameterBytes(0xF0) }
    }

    @Test
    fun `parameter reply must contain requested id`() {
        val packet = SsiCodec.decode(
            SsiCodec.encode(
                SsiCodec.PARAM_SEND,
                byteArrayOf(0xFF.toByte(), 0xF2.toByte(), 0x02, 0x00),
                source = SsiCodec.SOURCE_SCANNER,
            ),
        )
        assertContentEquals(byteArrayOf(0), SsiCodec.parameterReplyValue(packet, 0xF202))
        assertNull(SsiCodec.parameterReplyValue(packet, 0xF203))
    }

    @Test
    fun `ACK NAK revision and parameter replies require canonical shape`() {
        assertEquals(false, SsiCodec.isAck(SsiCodec.decode(hex("05 D0 00 00 01 FF 2A"))))
        assertEquals(false, SsiCodec.isAck(SsiCodec.decode(hex("04 D0 00 01 FF 2B"))))

        val malformedNak = SsiCodec.decode(
            SsiCodec.encode(SsiCodec.NAK, byteArrayOf(1, 2), source = SsiCodec.SOURCE_SCANNER),
        )
        assertEquals(false, SsiCodec.isNak(malformedNak))
        assertNull(SsiCodec.nakReason(malformedNak))

        val emptyRevision = SsiCodec.decode(
            SsiCodec.encode(SsiCodec.REPLY_REVISION, source = SsiCodec.SOURCE_SCANNER),
        )
        assertEquals(false, SsiCodec.isRevisionReply(emptyRevision))

        val wrongStatusParameter = SsiCodec.decode(
            SsiCodec.encode(
                SsiCodec.PARAM_SEND,
                hex("FF F2 02 00"),
                status = 8,
                source = SsiCodec.SOURCE_SCANNER,
            ),
        )
        assertNull(SsiCodec.parameterReplyValue(wrongStatusParameter, 0xF202))
    }

    private fun hex(value: String): ByteArray = value.split(' ')
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
