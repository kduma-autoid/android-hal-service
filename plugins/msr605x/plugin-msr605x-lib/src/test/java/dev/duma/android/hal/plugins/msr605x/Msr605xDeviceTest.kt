package dev.duma.android.hal.plugins.msr605x

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Msr605xDeviceTest {

    /** Records what was sent; replies are queued packets handed out by [read]. */
    private class FakeTransport : Msr605xTransport {
        val written = mutableListOf<ByteArray>()
        val features = mutableListOf<ByteArray>()
        val replies = LinkedBlockingQueue<ByteArray>()

        fun reply(data: ByteArray) = Msr605xProtocol.framePackets(data).forEach(replies::add)

        override fun write(packet: ByteArray) { written += packet }
        override fun sendFeature(packet: ByteArray) { features += packet }
        override fun read(timeoutMs: Int): ByteArray? = replies.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        override fun close() {}
    }

    private val esc: Byte = 0x1B

    @Test
    fun `read sends the command and parses the swipe`() = runBlocking {
        val t = FakeTransport()
        val block = byteArrayOf(esc, 's'.code.toByte(), esc, 1) + "%ABC?".toByteArray() +
            byteArrayOf(esc, 2) + ";123?".toByteArray() + byteArrayOf(esc, 3, esc, '+'.code.toByte()) +
            byteArrayOf('?'.code.toByte(), 0x1C, esc, '0'.code.toByte())
        t.reply(block)

        val r = Msr605xDevice(t).read(timeoutMs = 2_000)

        assertEquals(0xC2, t.written.single()[0].toInt() and 0xFF)
        assertEquals('r'.code.toByte(), t.written.single()[2])
        assertTrue(r.ok)
        assertEquals("ABC", r.track1)
        assertEquals("123", r.track2)
        assertEquals("", r.track3)
    }

    @Test
    fun `swipe timeout hard-resets the device`() = runBlocking {
        val t = FakeTransport()
        assertFailsWith<Msr605xTimeoutException> { Msr605xDevice(t).read(timeoutMs = 300) }
        assertContentEquals(
            Msr605xProtocol.featurePacket(Msr605xProtocol.FEATURE_HARD_RESET),
            t.features.single()
        )
    }

    @Test
    fun `cancelling a pending swipe hard-resets the device`() = runBlocking {
        val t = FakeTransport()
        val job = async(Dispatchers.IO) { Msr605xDevice(t).read(timeoutMs = 10_000) }
        delay(300)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, t.features.size)
    }

    @Test
    fun `listen waits past any deadline for the swipe`() = runBlocking {
        val t = FakeTransport()
        val job = async(Dispatchers.IO) { Msr605xDevice(t).listen() }
        delay(600) // several poll slices with nothing on the line
        t.reply(
            byteArrayOf(esc, 's'.code.toByte(), esc, 1, esc, '+'.code.toByte(), esc, 2) + ";42?".toByteArray() +
                byteArrayOf(esc, 3, esc, '+'.code.toByte(), '?'.code.toByte(), 0x1C, esc, '0'.code.toByte())
        )
        val r = job.await()
        assertEquals('r'.code.toByte(), t.written.single()[2])
        assertEquals("42", r.track2)
        assertTrue(t.features.isEmpty())
    }

    @Test
    fun `cancelling listen hard-resets the device`() = runBlocking {
        val t = FakeTransport()
        val job = async(Dispatchers.IO) { Msr605xDevice(t).listen() }
        delay(300)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, t.features.size)
    }

    @Test
    fun `short commands parse their replies`() = runBlocking {
        val t = FakeTransport()
        val device = Msr605xDevice(t)

        t.reply(byteArrayOf(esc, 'h'.code.toByte()))
        assertEquals("hi", device.getCoercivity())

        t.reply(byteArrayOf(esc, '0'.code.toByte(), 8, 8, 8))
        assertEquals(Triple(8, 8, 8), device.setBpc(8, 8, 8))

        t.reply(byteArrayOf(esc, '0'.code.toByte()))
        assertEquals('0', device.write("ABC", null, null, timeoutMs = 1_000))
    }
}
