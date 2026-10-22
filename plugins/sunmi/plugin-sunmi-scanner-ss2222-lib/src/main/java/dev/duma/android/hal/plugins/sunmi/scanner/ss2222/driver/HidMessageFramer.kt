package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import java.io.ByteArrayOutputStream

internal object HidReportCodec {
    const val REPORT_ID = 0x01
    const val REPORT_SIZE = 64
    const val CHUNK_SIZE = 56

    fun encode(message: ByteArray): List<ByteArray> {
        require(message.isNotEmpty()) { "HID message must not be empty" }
        val reports = ArrayList<ByteArray>()
        var offset = 0
        while (offset < message.size) {
            val count = minOf(CHUNK_SIZE, message.size - offset)
            val report = ByteArray(REPORT_SIZE)
            report[0] = REPORT_ID.toByte()
            report[1] = count.toByte()
            message.copyInto(report, destinationOffset = 2, startIndex = offset, endIndex = offset + count)
            offset += count
            report[REPORT_SIZE - 1] = if (offset < message.size) 1 else 0
            reports += report
        }
        return reports
    }
}

internal class HidMessageAssembler(
    private val maxMessageSize: Int = 16 * 1024,
    private val fragmentDeadlineMs: Long = 1_000,
) {
    data class CompletedMessage(val bytes: ByteArray, val epoch: Long)

    private val message = ByteArrayOutputStream()
    private var startedAtMs: Long? = null
    private var startedEpoch: Long? = null
    private var discarding = false

    @Synchronized
    fun accept(report: ByteArray, nowMs: Long): ByteArray? =
        acceptTagged(report, nowMs, 0)?.bytes

    @Synchronized
    fun acceptTagged(report: ByteArray, nowMs: Long, epoch: Long): CompletedMessage? {
        val started = startedAtMs
        if (started != null && nowMs - started > fragmentDeadlineMs) {
            fail("HID fragment deadline exceeded", report)
        }
        if (report.size != HidReportCodec.REPORT_SIZE) {
            rejectShortReport(report.size)
        }
        if ((report[0].toInt() and 0xFF) != HidReportCodec.REPORT_ID) {
            fail("Unexpected HID report id", report)
        }
        val length = report[1].toInt() and 0xFF
        if (length > HidReportCodec.CHUNK_SIZE) {
            fail("Invalid HID fragment length $length", report)
        }
        val more = report[HidReportCodec.REPORT_SIZE - 1].toInt() and 0xFF
        if (more !in 0..1) {
            fail("Invalid HID continuation marker $more", report, boundaryKnown = false)
        }
        if (discarding) {
            if (more == 0) discarding = false
            return null
        }
        if (more == 1 && length == 0) {
            fail("Empty continuing HID fragment", report)
        }
        if (more == 0 && length == 0 && message.size() == 0) {
            fail("Empty HID message", report)
        }
        if (startedAtMs == null) {
            startedAtMs = nowMs
            startedEpoch = epoch
        }
        if (message.size() + length > maxMessageSize) {
            fail("HID message exceeds $maxMessageSize bytes", report)
        }
        message.write(report, 2, length)
        if (more == 1) return null

        val completed = message.toByteArray()
        val completedEpoch = checkNotNull(startedEpoch)
        reset()
        return CompletedMessage(completed, completedEpoch)
    }

    /** A positive short USB read has consumed an unknown part of the current HID message. */
    @Synchronized
    fun rejectShortReport(actualSize: Int): Nothing {
        resetPartial()
        discarding = true
        throw FramingException("Expected 64-byte HID report, got $actualSize")
    }

    @Synchronized
    fun reset() {
        resetPartial()
        discarding = false
    }

    private fun resetPartial() {
        message.reset()
        startedAtMs = null
        startedEpoch = null
    }

    private fun fail(
        reason: String,
        report: ByteArray,
        boundaryKnown: Boolean = true,
    ): Nothing {
        resetPartial()
        val more = report.getOrNull(HidReportCodec.REPORT_SIZE - 1)?.toInt()?.and(0xFF)
        discarding = !boundaryKnown || more != 0
        throw FramingException(reason)
    }
}
