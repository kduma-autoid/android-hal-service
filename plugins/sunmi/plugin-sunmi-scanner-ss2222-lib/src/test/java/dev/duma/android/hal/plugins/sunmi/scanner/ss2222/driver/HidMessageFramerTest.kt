package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HidMessageFramerTest {
    @Test
    fun `messages across chunk boundaries round trip`() {
        for (size in listOf(1, 56, 57, 112, 113, 16 * 1024)) {
            val message = ByteArray(size) { (it and 0xFF).toByte() }
            val reports = HidReportCodec.encode(message)
            val assembler = HidMessageAssembler()
            var result: ByteArray? = null
            reports.forEachIndexed { index, report ->
                result = assembler.accept(report, index.toLong()) ?: result
            }
            assertContentEquals(message, result, "size=$size")
            assertEquals((size + 55) / 56, reports.size)
        }
    }

    @Test
    fun `assembler rejects invalid id length and continuation`() {
        val assembler = HidMessageAssembler()
        val invalidId = ByteArray(64).also { it[0] = 2 }
        assertFailsWith<FramingException> { assembler.accept(invalidId, 0) }

        val invalidLength = ByteArray(64).also { it[0] = 1; it[1] = 57 }
        assertFailsWith<FramingException> { assembler.accept(invalidLength, 0) }

        val invalidContinuation = ByteArray(64).also { it[0] = 1; it[63] = 2 }
        assertFailsWith<FramingException> { assembler.accept(invalidContinuation, 0) }

        val empty = ByteArray(64).also { it[0] = 1 }
        assertFailsWith<FramingException> { HidMessageAssembler().accept(empty, 0) }
    }

    @Test
    fun `fragment deadline resets partial message`() {
        val reports = HidReportCodec.encode(ByteArray(57) { 7 })
        val assembler = HidMessageAssembler(fragmentDeadlineMs = 1_000)
        assertNull(assembler.accept(reports[0], 100))
        assertFailsWith<FramingException> { assembler.accept(reports[1], 1_101) }

        val complete = HidReportCodec.encode(byteArrayOf(9)).single()
        assertContentEquals(byteArrayOf(9), assembler.accept(complete, 1_102))
    }

    @Test
    fun `message cap is enforced`() {
        val reports = HidReportCodec.encode(ByteArray(112))
        val assembler = HidMessageAssembler(maxMessageSize = 100)
        assertNull(assembler.accept(reports[0], 0))
        assertFailsWith<FramingException> { assembler.accept(reports[1], 1) }
    }

    @Test
    fun `short report discards trailing fragments through message boundary`() {
        val assembler = HidMessageAssembler()
        assertFailsWith<FramingException> { assembler.rejectShortReport(17) }

        val staleTail = HidReportCodec.encode(byteArrayOf(1, 2, 3)).single()
        assertNull(assembler.accept(staleTail, 1))

        val fresh = HidReportCodec.encode(byteArrayOf(9)).single()
        assertContentEquals(byteArrayOf(9), assembler.accept(fresh, 2))
    }

    @Test
    fun `malformed continuing report cannot turn its tail into a message`() {
        val assembler = HidMessageAssembler()
        val malformed = ByteArray(64).also {
            it[0] = 1
            it[1] = 57
            it[63] = 1
        }
        assertFailsWith<FramingException> { assembler.accept(malformed, 0) }

        val staleTail = HidReportCodec.encode(byteArrayOf(4, 5)).single()
        assertNull(assembler.accept(staleTail, 1))
        assertContentEquals(
            byteArrayOf(6),
            assembler.accept(HidReportCodec.encode(byteArrayOf(6)).single(), 2),
        )
    }

    @Test
    fun `completed message retains epoch of its first fragment`() {
        val reports = HidReportCodec.encode(ByteArray(57) { 7 })
        val assembler = HidMessageAssembler()

        assertNull(assembler.acceptTagged(reports[0], 0, epoch = 4))
        val completed = assembler.acceptTagged(reports[1], 1, epoch = 5)

        assertEquals(4, completed?.epoch)
    }
}
