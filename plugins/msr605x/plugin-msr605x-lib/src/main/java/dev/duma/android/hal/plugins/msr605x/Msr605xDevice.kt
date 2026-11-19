package dev.duma.android.hal.plugins.msr605x

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** No card was swiped before the deadline. */
class Msr605xTimeoutException(message: String) : Exception(message)

/**
 * MSR605X command driver over a [Msr605xTransport] — a Kotlin port of the `MSR605X` class from
 * `msr605x.py`. Not thread-safe: callers serialise access (the plugin holds a mutex).
 *
 * Swipe-waiting operations ([read], [readRaw], [write], [writeRaw], [erase]) block (cooperatively)
 * until a card is swiped or the timeout passes. On timeout or coroutine cancellation the device is
 * hard-reset, which takes it out of its waiting-for-swipe state — the same recovery the Python
 * driver uses on interruption.
 */
class Msr605xDevice(private val transport: Msr605xTransport) {

    // --- Swipe operations ---

    suspend fun read(timeoutMs: Long): Msr605xProtocol.ReadResult<String> =
        Msr605xProtocol.parseIsoRead(awaitSwipe(Msr605xProtocol.CMD_READ, timeoutMs))

    /**
     * Arms the reader for one swipe and waits for it with no deadline — the background-listening
     * read. Cancel the coroutine to stop; the reader is then hard-reset out of its waiting state.
     */
    suspend fun listen(): Msr605xProtocol.ReadResult<String> =
        Msr605xProtocol.parseIsoRead(awaitSwipe(Msr605xProtocol.CMD_READ, timeoutMs = null))

    suspend fun readRaw(timeoutMs: Long): Msr605xProtocol.ReadResult<ByteArray> =
        Msr605xProtocol.parseRawRead(awaitSwipe(Msr605xProtocol.CMD_READ_RAW, timeoutMs))

    /** Writes ISO tracks (no sentinels). Returns the device status character (`'0'` = OK). */
    suspend fun write(track1: String?, track2: String?, track3: String?, timeoutMs: Long): Char? =
        Msr605xProtocol.parseStatus(awaitSwipe(Msr605xProtocol.buildIsoWrite(track1, track2, track3), timeoutMs))

    /** Writes raw tracks. Returns the device status character (`'0'` = OK). */
    suspend fun writeRaw(track1: ByteArray?, track2: ByteArray?, track3: ByteArray?, timeoutMs: Long): Char? =
        Msr605xProtocol.parseStatus(awaitSwipe(Msr605xProtocol.buildRawWrite(track1, track2, track3), timeoutMs))

    /** Erases the selected tracks. Returns the device status character (`'0'` = OK). */
    suspend fun erase(track1: Boolean, track2: Boolean, track3: Boolean, timeoutMs: Long): Char? =
        Msr605xProtocol.parseStatus(awaitSwipe(Msr605xProtocol.buildErase(track1, track2, track3), timeoutMs))

    // --- Settings & status ---

    suspend fun setBpi(track: Int, bpi: Int): Boolean = command(Msr605xProtocol.buildBpi(track, bpi)).let(Msr605xProtocol::isOk)

    suspend fun setBpc(track1: Int, track2: Int, track3: Int): Triple<Int, Int, Int>? =
        Msr605xProtocol.parseBpc(command(Msr605xProtocol.buildBpc(track1, track2, track3)))

    suspend fun setHiCo(): Boolean = Msr605xProtocol.isOk(command(Msr605xProtocol.CMD_SET_HICO))

    suspend fun setLoCo(): Boolean = Msr605xProtocol.isOk(command(Msr605xProtocol.CMD_SET_LOCO))

    suspend fun getCoercivity(): String? = Msr605xProtocol.parseCoercivity(command(Msr605xProtocol.CMD_GET_COERCIVITY))

    suspend fun setLeadingZeros(track13: Int, track2: Int): Boolean =
        Msr605xProtocol.isOk(command(Msr605xProtocol.buildLeadingZeros(track13, track2)))

    suspend fun getLeadingZeros(): Pair<Int, Int>? =
        Msr605xProtocol.parseLeadingZeros(command(Msr605xProtocol.CMD_LEAD_ZERO_CHECK))

    suspend fun getModel(): String? = Msr605xProtocol.parseModel(command(Msr605xProtocol.CMD_GET_MODEL))

    suspend fun getFirmware(): String? = Msr605xProtocol.parseFirmware(command(Msr605xProtocol.CMD_GET_FW_VERSION))

    suspend fun commTest(): Boolean = Msr605xProtocol.isCommTestOk(command(Msr605xProtocol.CMD_COMM_TEST))

    /** Sets the LEDs. The device's reply (if any) is ignored, as in the Python driver. */
    suspend fun setLed(command: ByteArray) {
        command(command, IDLE_MS.toLong())
    }

    suspend fun reset() {
        command(Msr605xProtocol.CMD_RESET, IDLE_MS.toLong())
    }

    /** Hard reset via feature report; works even while the device is waiting for a swipe. */
    suspend fun hardReset() {
        transport.sendFeature(Msr605xProtocol.featurePacket(Msr605xProtocol.FEATURE_HARD_RESET))
        receive(IDLE_MS.toLong())
    }

    // --- Plumbing ---

    private suspend fun command(data: ByteArray, waitMs: Long = RESPONSE_WAIT_MS): ByteArray {
        send(data)
        return receive(waitMs)
    }

    /** Sends a swipe-waiting command and waits for the card; [timeoutMs] null = no deadline. */
    private suspend fun awaitSwipe(data: ByteArray, timeoutMs: Long?): ByteArray {
        send(data)
        val response = try {
            receive(timeoutMs)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { hardReset() } }
            throw e
        }
        if (response.isEmpty()) {
            runCatching { hardReset() }
            throw Msr605xTimeoutException("No card swiped within ${timeoutMs}ms")
        }
        return response
    }

    private fun send(data: ByteArray) {
        Msr605xProtocol.framePackets(data).forEach(transport::write)
    }

    /**
     * Waits up to [firstWaitMs] (null = indefinitely) for the first packet, polling in short slices
     * so cancellation is honoured, then keeps reading until a packet with the "last" header bit
     * arrives or the line goes idle for [IDLE_MS]. Returns an empty array when nothing arrived.
     */
    private suspend fun receive(firstWaitMs: Long?): ByteArray {
        val assembler = Msr605xProtocol.PacketAssembler()
        val deadline = firstWaitMs?.let { System.currentTimeMillis() + it }
        while (!assembler.hasData) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline?.let { it - System.currentTimeMillis() } ?: POLL_SLICE_MS
            if (remaining <= 0) return ByteArray(0)
            transport.read(minOf(remaining, POLL_SLICE_MS).toInt().coerceAtLeast(1))?.let(assembler::add)
        }
        while (!assembler.complete) {
            val packet = transport.read(IDLE_MS) ?: break
            assembler.add(packet)
        }
        return assembler.bytes()
    }

    private companion object {
        /** Wait for the reply to a non-swipe command. */
        const val RESPONSE_WAIT_MS = 500L
        /** Inter-packet idle gap that ends a response (the Python driver's default timeout). */
        const val IDLE_MS = 100
        /** Max single blocking read while waiting for a swipe, so cancellation stays responsive. */
        const val POLL_SLICE_MS = 250L
    }
}
