package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Ss2222ControllerTest {
    @Test
    fun `single shot keeps first pre-ACK barcode and disables after delivery`() = runTest {
        val events = RecordingEvents()
        lateinit var controller: Ss2222Controller
        lateinit var transport: FakeTransport
        transport = FakeTransport(
            beforeReply = { opcode ->
                if (opcode == SsiCodec.SCAN_ENABLE) {
                    controller.onMessage(transport, "FIRST\r".encodeToByteArray())
                    controller.onMessage(transport, "SECOND\r".encodeToByteArray())
                }
            },
        )
        controller = Ss2222Controller(this, events, commandTimeoutMs = 100)
        events.controller = controller
        transport.deliver = { controller.onMessage(transport, it) }
        controller.initialize(transport)

        controller.start(singleShot = true)
        advanceUntilIdle()

        assertEquals(listOf("FIRST\r"), events.barcodes.map { it.decodeToString() })
        assertEquals(Ss2222Driver.State.IDLE, controller.state)
        assertTrue(transport.opcodes.takeLast(2).containsAll(listOf(SsiCodec.SCAN_ENABLE, SsiCodec.SCAN_DISABLE)))
    }

    @Test
    fun `continuous emits all scans until explicit stop and ignores stale idle data`() = runTest {
        val fixture = fixture(this)
        fixture.controller.initialize(fixture.transport)
        fixture.controller.onMessage(fixture.transport, "STALE\r".encodeToByteArray())
        fixture.controller.start(singleShot = false)
        fixture.controller.onMessage(fixture.transport, "A\r".encodeToByteArray())
        fixture.controller.onMessage(fixture.transport, "B\r".encodeToByteArray())
        fixture.controller.stop()
        fixture.controller.onMessage(fixture.transport, "LATE\r".encodeToByteArray())

        assertEquals(listOf("A\r", "B\r"), fixture.events.barcodes.map { it.decodeToString() })
        assertEquals(Ss2222Driver.State.IDLE, fixture.controller.state)
    }

    @Test
    fun `timeout invalidates transport and late ACK cannot start session`() = runTest {
        val fixture = fixture(this, commandTimeoutMs = 50)
        fixture.controller.initialize(fixture.transport)
        fixture.transport.suppress += SsiCodec.SCAN_ENABLE

        val failure = assertFailsWith<DriverException> { fixture.controller.start(singleShot = true) }
        assertEquals("command_timeout", failure.code)
        assertEquals(Ss2222Driver.State.ERROR, fixture.controller.state)
        assertTrue(fixture.transport.closed)

        fixture.controller.onMessage(fixture.transport, ackPacket())
        assertEquals(Ss2222Driver.State.ERROR, fixture.controller.state)
        assertTrue(fixture.events.barcodes.isEmpty())
    }

    @Test
    fun `controlled close restores all snapshots and enables scanner`() = runTest {
        val fixture = fixture(this)
        fixture.transport.parameters[0x38] = 1
        fixture.controller.initialize(fixture.transport)
        fixture.controller.writeParams(mapOf(0x38 to 0))
        assertEquals(0, fixture.transport.parameters[0x38])

        fixture.controller.controlledClose()

        assertEquals(1, fixture.transport.parameters[0x38])
        assertEquals(0x11, fixture.transport.parameters[0x8A])
        assertEquals(0, fixture.transport.parameters[0xF202])
        assertEquals(SsiCodec.SCAN_ENABLE, fixture.transport.opcodes.last())
        assertTrue(fixture.transport.closed)
    }

    @Test
    fun `close enables scanning even if synchronized parameter restoration is rejected`() = runTest {
        val fixture = fixture(this)
        fixture.controller.initialize(fixture.transport)
        fixture.transport.nakNext = SsiCodec.PARAM_SEND

        fixture.controller.controlledClose()

        assertEquals(SsiCodec.SCAN_ENABLE, fixture.transport.opcodes.last())
        assertTrue(fixture.events.errors.any { it.first == "restore_failed" })
        assertTrue(fixture.transport.closed)
    }

    @Test
    fun `readback mismatch rolls parameter back`() = runTest {
        val fixture = fixture(this)
        fixture.transport.parameters[0x38] = 1
        fixture.controller.initialize(fixture.transport)
        fixture.transport.wrongReadbackAfterWriteFor = 0x38

        val failure = assertFailsWith<DriverException> {
            fixture.controller.writeParams(mapOf(0x38 to 0))
        }

        assertEquals("readback_mismatch", failure.code)
        assertEquals(1, fixture.transport.parameters[0x38])
    }

    @Test
    fun `failed transaction rolls back to immediate previous value not session snapshot`() = runTest {
        val fixture = fixture(this)
        fixture.transport.parameters[0x38] = 1
        fixture.controller.initialize(fixture.transport)
        fixture.controller.writeParams(mapOf(0x38 to 0))
        fixture.transport.wrongReadbackAfterWriteFor = 0x38

        assertFailsWith<DriverException> {
            fixture.controller.writeParams(mapOf(0x38 to 2))
        }

        assertEquals(0, fixture.transport.parameters[0x38])
        fixture.controller.controlledClose()
        assertEquals(1, fixture.transport.parameters[0x38])
    }

    @Test
    fun `NAK after partial parameter application is rolled back`() = runTest {
        val fixture = fixture(this)
        fixture.transport.parameters[0x38] = 1
        fixture.controller.initialize(fixture.transport)
        fixture.transport.partialWriteThenNakFor = 0x38

        val failure = assertFailsWith<DriverException> {
            fixture.controller.writeParams(mapOf(0x38 to 0))
        }

        assertEquals("nak", failure.code)
        assertEquals(1, fixture.transport.parameters[0x38])
    }

    @Test
    fun `concurrent stop callers share one disable operation`() = runTest {
        val fixture = fixture(this, commandTimeoutMs = 1_000)
        fixture.controller.initialize(fixture.transport)
        fixture.controller.start(singleShot = false)
        fixture.transport.blockNext = SsiCodec.SCAN_DISABLE

        val first = async { fixture.controller.stop() }
        runCurrent()
        assertEquals(Ss2222Driver.State.STOPPING, fixture.controller.state)
        val second = async { fixture.controller.stop() }
        runCurrent()
        assertTrue(!second.isCompleted)

        fixture.transport.releaseBlocked.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, fixture.transport.opcodes.count { it == SsiCodec.SCAN_DISABLE })
    }

    @Test
    fun `NAK for stop invalidates transport and later stop cannot report success`() = runTest {
        val fixture = fixture(this)
        fixture.controller.initialize(fixture.transport)
        fixture.controller.start(singleShot = false)
        fixture.transport.nakNext = SsiCodec.SCAN_DISABLE

        assertFailsWith<DriverException> { fixture.controller.stop() }
        assertEquals(Ss2222Driver.State.ERROR, fixture.controller.state)
        assertTrue(fixture.transport.closed)

        val second = assertFailsWith<DriverException> { fixture.controller.stop() }
        assertEquals("invalid_state", second.code)
    }

    @Test
    fun `close interrupts initialization and releases transport before return`() = runTest {
        val fixture = fixture(this, commandTimeoutMs = 10_000)
        fixture.transport.suppress += SsiCodec.SCAN_DISABLE
        val initialization = async { runCatching { fixture.controller.initialize(fixture.transport) } }
        runCurrent()

        fixture.controller.controlledClose()

        assertTrue(initialization.await().isFailure)
        assertTrue(fixture.transport.closed)
        assertEquals(Ss2222Driver.State.DISCONNECTED, fixture.controller.state)
    }

    @Test
    fun `close waits for active ACK before issuing restore commands`() = runTest {
        val fixture = fixture(this, commandTimeoutMs = 1_000)
        fixture.controller.initialize(fixture.transport)
        fixture.transport.blockNext = SsiCodec.SCAN_ENABLE
        val start = async { runCatching { fixture.controller.start(singleShot = false) } }
        runCurrent()
        val close = async { fixture.controller.controlledClose() }
        runCurrent()

        val commandsAtClose = fixture.transport.opcodes.size
        assertTrue(!close.isCompleted)
        assertEquals(SsiCodec.SCAN_ENABLE, fixture.transport.opcodes.last())

        fixture.transport.releaseBlocked.complete(Unit)
        start.await()
        close.await()

        assertTrue(fixture.transport.opcodes.size > commandsAtClose)
        assertTrue(fixture.transport.closed)
        assertEquals(Ss2222Driver.State.DISCONNECTED, fixture.controller.state)
    }

    @Test
    fun `detach interrupts pending command and leaves disconnected`() = runTest {
        val fixture = fixture(this, commandTimeoutMs = 10_000)
        fixture.controller.initialize(fixture.transport)
        fixture.transport.suppress += SsiCodec.SCAN_ENABLE
        val operation = async { runCatching { fixture.controller.start(singleShot = false) } }
        runCurrent()

        fixture.controller.onDetached(fixture.transport)

        val failure = operation.await().exceptionOrNull() as DriverException
        assertEquals("device_disconnected", failure.code)
        assertEquals(Ss2222Driver.State.DISCONNECTED, fixture.controller.state)
    }

    @Test
    fun `old transport ACK and scan cannot enter reconnected session`() = runTest {
        val events = RecordingEvents()
        val controller = Ss2222Controller(this, events, commandTimeoutMs = 1_000)
        events.controller = controller
        val old = FakeTransport().also { transport ->
            transport.deliver = { controller.onMessage(transport, it) }
        }
        controller.initialize(old)
        controller.onDetached(old)

        val current = FakeTransport().also { transport ->
            transport.deliver = { controller.onMessage(transport, it) }
        }
        controller.initialize(current)
        current.suppress += SsiCodec.SCAN_ENABLE
        val start = async { runCatching { controller.start(singleShot = false) } }
        runCurrent()

        controller.onMessage(old, ackPacket())
        controller.onMessage(old, "OLD\r".encodeToByteArray())
        runCurrent()
        assertTrue(!start.isCompleted)
        assertTrue(events.barcodes.isEmpty())

        controller.onMessage(current, ackPacket())
        start.await().getOrThrow()
        controller.onMessage(current, "NEW\r".encodeToByteArray())
        assertEquals(listOf("NEW\r"), events.barcodes.map { it.decodeToString() })
    }

    @Test
    fun `revision parser extracts exact fields`() {
        val info = Ss2222Driver.parseRevision(REVISION.encodeToByteArray())
        assertEquals("SS2222", info.model)
        assertEquals("2502D57G02104", info.productId)
        assertEquals("V3.0.A", info.hardwareVersion)
        assertEquals("2.4.94", info.firmwareVersion)
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        commandTimeoutMs: Long = 100,
    ): Fixture {
        val events = RecordingEvents()
        val transport = FakeTransport()
        val controller = Ss2222Controller(scope, events, commandTimeoutMs)
        events.controller = controller
        transport.deliver = { controller.onMessage(transport, it) }
        return Fixture(controller, transport, events)
    }

    private data class Fixture(
        val controller: Ss2222Controller,
        val transport: FakeTransport,
        val events: RecordingEvents,
    )

    private class RecordingEvents : ControllerEvents {
        lateinit var controller: Ss2222Controller
        val states = mutableListOf<Ss2222Driver.State>()
        val barcodes = mutableListOf<ByteArray>()
        val errors = mutableListOf<Pair<String, String>>()
        override fun onStateChanged(state: Ss2222Driver.State) { states += state }
        override fun onBarcode(source: MessageTransport, raw: ByteArray) { barcodes += raw }
        override fun onError(code: String, message: String) { errors += code to message }
        override fun onRevision(packet: SsiCodec.Packet) = Unit
        override fun onTransportInvalidated(transport: MessageTransport) = Unit
    }

    private class FakeTransport(
        val parameters: MutableMap<Int, Int> = mutableMapOf(0x8A to 0x11, 0xF202 to 0),
        private val beforeReply: (Int) -> Unit = {},
    ) : MessageTransport {
        var deliver: (ByteArray) -> Unit = {}
        val opcodes = mutableListOf<Int>()
        val suppress = mutableSetOf<Int>()
        var wrongReadbackFor: Int? = null
        var wrongReadbackAfterWriteFor: Int? = null
        var partialWriteThenNakFor: Int? = null
        var nakNext: Int? = null
        var blockNext: Int? = null
        var releaseBlocked = CompletableDeferred<Unit>()
        var closed = false
        var scanSessions = 0

        override suspend fun send(message: ByteArray) {
            val packet = SsiCodec.decode(message)
            opcodes += packet.opcode
            beforeReply(packet.opcode)
            if (packet.opcode in suppress) return
            if (blockNext == packet.opcode) {
                blockNext = null
                releaseBlocked.await()
            }
            if (nakNext == packet.opcode) {
                nakNext = null
                deliver(nakPacket(reason = 6))
                return
            }
            when (packet.opcode) {
                SsiCodec.REQUEST_REVISION -> deliver(
                    SsiCodec.encode(
                        SsiCodec.REPLY_REVISION,
                        REVISION.encodeToByteArray(),
                        source = SsiCodec.SOURCE_SCANNER,
                    ),
                )
                SsiCodec.PARAM_REQUEST -> {
                    val id = decodeParameterId(packet.data)
                    val actual = parameters[id] ?: 0
                    val value = if (wrongReadbackFor == id) {
                        wrongReadbackFor = null
                        actual xor 1
                    } else actual
                    deliver(
                        SsiCodec.encode(
                            SsiCodec.PARAM_SEND,
                            byteArrayOf(0xFF.toByte()) + SsiCodec.parameterBytes(id) + value.toByte(),
                            source = SsiCodec.SOURCE_SCANNER,
                        ),
                    )
                }
                SsiCodec.PARAM_SEND -> {
                    val writes = decodeParameterWrites(packet.data)
                    writes.forEach { (id, value) -> parameters[id] = value }
                    val nakId = partialWriteThenNakFor
                    if (nakId != null && nakId in writes) {
                        partialWriteThenNakFor = null
                        deliver(nakPacket(reason = 6))
                    } else {
                        val faultId = wrongReadbackAfterWriteFor
                        if (faultId != null && faultId in writes) {
                            wrongReadbackAfterWriteFor = null
                            wrongReadbackFor = faultId
                        }
                        deliver(ackPacket())
                    }
                }
                else -> deliver(ackPacket())
            }
        }

        override suspend fun close() { closed = true }
        override fun beginScanSession() { scanSessions++ }

        private fun decodeParameterId(data: ByteArray): Int =
            if ((data[0].toInt() and 0xFF) >= 0xF0) {
                ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            } else data[0].toInt() and 0xFF

        private fun decodeParameterWrites(data: ByteArray): Map<Int, Int> {
            val result = linkedMapOf<Int, Int>()
            var offset = 1
            while (offset < data.size) {
                val first = data[offset].toInt() and 0xFF
                if (first >= 0xF0) {
                    val id = (first shl 8) or (data[offset + 1].toInt() and 0xFF)
                    result[id] = data[offset + 2].toInt() and 0xFF
                    offset += 3
                } else {
                    result[first] = data[offset + 1].toInt() and 0xFF
                    offset += 2
                }
            }
            return result
        }
    }

    private companion object {
        const val REVISION = "Product Name:SS2222 Product ID:2502D57G02104 Hardware version:V3.0.A Firmware version:2.4.94"
        fun ackPacket(): ByteArray = SsiCodec.encode(
            SsiCodec.ACK,
            source = SsiCodec.SOURCE_SCANNER,
        )
        fun nakPacket(reason: Int): ByteArray = SsiCodec.encode(
            SsiCodec.NAK,
            byteArrayOf(reason.toByte()),
            source = SsiCodec.SOURCE_SCANNER,
        )
    }
}
