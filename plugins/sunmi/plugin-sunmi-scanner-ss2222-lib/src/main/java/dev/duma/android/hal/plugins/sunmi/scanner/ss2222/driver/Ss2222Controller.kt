package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface MessageTransport {
    suspend fun send(message: ByteArray)
    suspend fun close()
    fun beginScanSession() = Unit
    fun diagnosticState(): String = "unavailable"
}

internal interface ControllerEvents {
    fun onStateChanged(state: Ss2222Driver.State)
    fun onBarcode(source: MessageTransport, raw: ByteArray)
    fun onError(code: String, message: String)
    fun onRevision(packet: SsiCodec.Packet)
    fun onTransportInvalidated(transport: MessageTransport)
    fun onDiagnostic(message: String) = Unit
}

internal class Ss2222Controller(
    private val scope: CoroutineScope,
    private val events: ControllerEvents,
    private val commandTimeoutMs: Long = 2_000,
    private val closeTimeoutMs: Long = 2_500,
) {
    private data class Pending(
        val opcode: Int,
        val source: MessageTransport,
        val generation: Long,
        val accepts: (SsiCodec.Packet) -> Boolean,
        val result: CompletableDeferred<SsiCodec.Packet>,
    )

    private val commandMutex = Mutex()
    private val operationMutex = Mutex()
    private val pending = AtomicReference<Pending?>()
    private val sessionLock = Any()
    private val snapshots = LinkedHashMap<Int, Int>()
    private val earlyBarcodes = ArrayDeque<ByteArray>()

    @Volatile
    private var transport: MessageTransport? = null
    @Volatile
    private var desynchronized = false
    @Volatile
    private var closing = false
    @Volatile
    private var restoring = false
    private var singleShot = false
    private var singleAccepted = false
    @Volatile
    private var generation = 0L

    @Volatile
    var state: Ss2222Driver.State = Ss2222Driver.State.DISCONNECTED
        private set

    suspend fun initialize(newTransport: MessageTransport): ByteArray = operationMutex.withLock {
            val myGeneration = synchronized(sessionLock) {
                if (transport != null) throw DriverException("already_open", "Scanner is already open")
                ++generation
                transport = newTransport
                desynchronized = false
                closing = false
                snapshots.clear()
                resetSessionLocked()
                setState(Ss2222Driver.State.CONNECTING)
                generation
            }

            try {
                commandExpectingAck(SsiCodec.SCAN_DISABLE)
                val revision = queryRevisionInternal()
                synchronized(sessionLock) {
                    checkGenerationLocked(myGeneration, newTransport)
                    events.onRevision(SsiCodec.decode(revision))
                }

                val triggerMode = oneByteParameter(PARAM_TRIGGER_MODE)
                val lightMode = oneByteParameter(PARAM_LIGHT_MODE)
                synchronized(sessionLock) {
                    snapshots[PARAM_TRIGGER_MODE] = triggerMode
                    snapshots[PARAM_LIGHT_MODE] = lightMode
                }
                writeAndVerify(
                    linkedMapOf(
                        PARAM_TRIGGER_MODE to TRIGGER_MODE_DEFAULT,
                        PARAM_LIGHT_MODE to LIGHT_MODE_FIRMWARE,
                    ),
                    captureSnapshots = false,
                )
                synchronized(sessionLock) {
                    checkGenerationLocked(myGeneration, newTransport)
                    setState(Ss2222Driver.State.IDLE)
                }
                revision
            } catch (e: Throwable) {
                val stale = synchronized(sessionLock) {
                    closing || generation != myGeneration || transport !== newTransport
                }
                if (stale) throw e
                if (e is DriverException && e.code == "device_disconnected") throw e
                bestEffortClose(newTransport)
                val publish = synchronized(sessionLock) {
                    if (generation != myGeneration || transport !== newTransport) false else {
                        transport = null
                        setState(Ss2222Driver.State.ERROR)
                        true
                    }
                }
                if (publish) report(e)
                throw e
            }
    }

    suspend fun start(singleShot: Boolean) = operationMutex.withLock {
        synchronized(sessionLock) {
            when (state) {
                Ss2222Driver.State.IDLE -> {
                    ensureConnected().beginScanSession()
                    this.singleShot = singleShot
                    singleAccepted = false
                    earlyBarcodes.clear()
                    setState(Ss2222Driver.State.STARTING)
                }
                Ss2222Driver.State.STARTING,
                Ss2222Driver.State.SCANNING,
                Ss2222Driver.State.STOPPING -> throw DriverException("busy", "Scanner is already active")
                else -> throw unavailable()
            }
        }

        try {
            commandExpectingAck(SsiCodec.SCAN_ENABLE)
        } catch (e: Throwable) {
            synchronized(sessionLock) {
                resetSessionLocked()
                if (state != Ss2222Driver.State.ERROR && state != Ss2222Driver.State.DISCONNECTED) {
                    setState(Ss2222Driver.State.IDLE)
                }
            }
            report(e)
            throw e
        }

        val buffered: List<ByteArray>
        val source: MessageTransport
        var autoStop = false
        synchronized(sessionLock) {
            if (state != Ss2222Driver.State.STARTING) return@withLock
            source = transport ?: return@withLock
            setState(Ss2222Driver.State.SCANNING)
            buffered = earlyBarcodes.toList()
            earlyBarcodes.clear()
            if (singleShot && buffered.isNotEmpty()) {
                singleAccepted = true
                autoStop = true
            }
        }
        if (singleShot) buffered.firstOrNull()?.let { events.onBarcode(source, it) }
        else buffered.forEach { events.onBarcode(source, it) }
        if (autoStop) scope.launch { stopAfterSingleRead() }
    }

    suspend fun stop() = operationMutex.withLock {
        stopInternal()
    }

    private suspend fun stopInternal() {
        val shouldStop = synchronized(sessionLock) {
            when (state) {
                Ss2222Driver.State.DISCONNECTED,
                Ss2222Driver.State.IDLE -> false
                Ss2222Driver.State.STOPPING -> false
                Ss2222Driver.State.STARTING,
                Ss2222Driver.State.SCANNING -> {
                    setState(Ss2222Driver.State.STOPPING)
                    true
                }
                else -> throw unavailable()
            }
        }
        if (!shouldStop) return

        try {
            commandExpectingAck(SsiCodec.SCAN_DISABLE)
            synchronized(sessionLock) {
                resetSessionLocked()
                if (state != Ss2222Driver.State.DISCONNECTED && state != Ss2222Driver.State.ERROR) {
                    setState(Ss2222Driver.State.IDLE)
                }
            }
        } catch (e: Throwable) {
            if (e is DriverException && e.code == "nak") {
                transport?.let { active ->
                    invalidate(active, DriverException("stop_rejected", "Scanner rejected SCAN_DISABLE", e))
                }
            }
            report(e)
            throw e
        }
    }

    suspend fun beep(error: Boolean) {
        operationMutex.withLock {
            ensureOperational()
            commandExpectingAck(SsiCodec.BEEP, byteArrayOf(if (error) 1 else 0))
        }
    }

    suspend fun readParam(id: Int): ByteArray = operationMutex.withLock {
        ensureOperational()
        readParamInternal(id)
    }

    private suspend fun readParamInternal(id: Int): ByteArray {
        val idBytes = try {
            SsiCodec.parameterBytes(id)
        } catch (e: IllegalArgumentException) {
            throw DriverException("invalid_parameter", e.message ?: "Invalid parameter", e)
        }
        val packet = command(SsiCodec.PARAM_REQUEST, idBytes) {
            SsiCodec.isNak(it) || SsiCodec.parameterReplyValue(it, id) != null
        }
        throwIfNak(packet, SsiCodec.PARAM_REQUEST)
        return SsiCodec.parameterReplyValue(packet, id)
            ?: throw DriverException("invalid_response", "PARAM_REQUEST reply did not contain parameter 0x${id.toString(16)}")
    }

    suspend fun writeParams(values: Map<Int, Int>) = operationMutex.withLock {
        if (state != Ss2222Driver.State.IDLE) {
            throw DriverException("invalid_state", "Parameters may only be changed while the scanner is idle")
        }
        ensureConnected()
        writeAndVerify(values, captureSnapshots = true)
    }

    suspend fun queryRevision(): ByteArray = operationMutex.withLock { queryRevisionInternal() }

    private suspend fun queryRevisionInternal(): ByteArray {
        ensureConnected()
        val packet = command(SsiCodec.REQUEST_REVISION) {
            SsiCodec.isRevisionReply(it) || SsiCodec.isNak(it)
        }
        throwIfNak(packet, SsiCodec.REQUEST_REVISION)
        if (!SsiCodec.isRevisionReply(packet)) {
            throw DriverException("invalid_response", "Expected REPLY_REVISION")
        }
        return packet.raw.copyOf()
    }

    /** Called by the active transport for each complete HID message. */
    fun onMessage(source: MessageTransport, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val packet = SsiCodec.decodeOrNull(bytes)
        if (packet != null) {
            var disposition = "stale_transport"
            var waitingOpcode: Int? = null
            val completed = synchronized(sessionLock) {
                if (source !== transport) return@synchronized null
                val request = pending.get()
                waitingOpcode = request?.opcode
                disposition = when {
                    packet.source != SsiCodec.SOURCE_SCANNER -> "wrong_source"
                    request == null -> "unsolicited"
                    request.source !== source || request.generation != generation -> "stale_request"
                    !request.accepts(packet) -> "unexpected_reply"
                    else -> "matched"
                }
                if (disposition == "matched" && request != null) {
                    if (pending.compareAndSet(request, null)) request else {
                        disposition = "pending_changed"
                        null
                    }
                } else null
            }
            completed?.result?.complete(packet)
            events.onDiagnostic(
                "SSI rx opcode=0x${packet.opcode.toString(16)} source=${packet.source} " +
                    "status=${packet.status} dataBytes=${packet.data.size} " +
                    "pending=${waitingOpcode?.toString(16)} result=$disposition",
            )
            return
        }
        if (SsiCodec.looksLikeScannerPacket(bytes)) {
            synchronized(sessionLock) {
                if (source !== transport) return
            }
            events.onDiagnostic("SSI malformed bytes=${bytes.size} state=$state")
            events.onError("invalid_response", "Discarded malformed SSI response")
            return
        }
        events.onDiagnostic("HID data bytes=${bytes.size} state=$state")
        acceptBarcode(source, bytes)
    }

    fun onDetached(source: MessageTransport) {
        synchronized(sessionLock) {
            if (source !== transport) return
            generation++
            transport = null
            desynchronized = false // a physical detach creates a new SSI epoch
            snapshots.clear()
            resetSessionLocked()
            pending.getAndSet(null)?.result?.completeExceptionally(
                DriverException("device_disconnected", "Scanner was disconnected"),
            )
            setState(Ss2222Driver.State.DISCONNECTED)
        }
    }

    suspend fun controlledClose() {
        synchronized(sessionLock) {
            closing = true
            // Keep the current SSI epoch until its pending ACK arrives. The closing flag
            // already prevents initialization and new public operations from proceeding.
            resetSessionLocked()
        }
        try {
            withTimeout(closeTimeoutMs) {
                operationMutex.withLock {
                    val active = transport ?: return@withLock
                    restoring = true
                    if (!desynchronized) {
                        try {
                            restoreSnapshots()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            report(DriverException("restore_failed", "Could not restore scanner parameters", e))
                        }
                        // A rejected restore still leaves SSI synchronized. Give scanning back to
                        // other apps even when a saved setting could not be restored.
                        if (!desynchronized && transport === active) {
                            commandExpectingAck(SsiCodec.SCAN_ENABLE)
                        }
                    }
                    bestEffortClose(active)
                    synchronized(sessionLock) {
                        if (transport === active) transport = null
                    }
                }
            }
        } catch (e: Throwable) {
            // The active command did not reach a known boundary. Never send restore commands in
            // this SSI epoch: a late response has no transaction id and could match them.
            synchronized(sessionLock) { desynchronized = true }
            report(DriverException("restore_failed", "Could not completely restore scanner state", e))
            val active = transport
            if (active != null) bestEffortClose(active)
            synchronized(sessionLock) {
                if (transport === active) transport = null
            }
        } finally {
            pending.getAndSet(null)?.result?.cancel()
            synchronized(sessionLock) {
                snapshots.clear()
                restoring = false
                desynchronized = false
                closing = false
                setState(Ss2222Driver.State.DISCONNECTED)
            }
        }
    }

    private suspend fun writeAndVerify(values: Map<Int, Int>, captureSnapshots: Boolean) {
        if (values.isEmpty()) throw DriverException("invalid_parameter", "No parameter values supplied")
        try {
            values.forEach { (id, value) ->
                SsiCodec.parameterBytes(id)
                if (value !in 0..0xFF) throw IllegalArgumentException("Value must fit in one byte")
            }
        } catch (e: IllegalArgumentException) {
            throw DriverException("invalid_parameter", e.message ?: "Invalid parameter", e)
        }

        val before = linkedMapOf<Int, Int>()
        for (id in values.keys) before[id] = oneByteParameter(id)
        if (captureSnapshots) synchronized(sessionLock) {
            before.forEach { (id, value) -> snapshots.putIfAbsent(id, value) }
        }
        val data = try {
            SsiCodec.parameterWriteData(values)
        } catch (e: IllegalArgumentException) {
            throw DriverException("invalid_parameter", e.message ?: "Invalid parameters", e)
        }
        try {
            val packet = command(SsiCodec.PARAM_SEND, data) {
                SsiCodec.isAck(it) || SsiCodec.isNak(it)
            }
            throwIfNak(packet, SsiCodec.PARAM_SEND)
            if (!SsiCodec.isAck(packet)) {
                throw DriverException("invalid_response", "Expected ACK for PARAM_SEND")
            }
            for ((id, expected) in values) {
                val actual = oneByteParameter(id)
                if (actual != expected) {
                    throw DriverException(
                        "readback_mismatch",
                        "Parameter 0x${id.toString(16)} read back as 0x${actual.toString(16)}, expected 0x${expected.toString(16)}",
                    )
                }
            }
        } catch (e: Throwable) {
            if (!desynchronized) {
                try {
                    rollbackTo(before)
                } catch (rollback: Throwable) {
                    e.addSuppressed(rollback)
                }
            }
            throw e
        }
    }

    private suspend fun rollbackTo(restore: Map<Int, Int>) {
        if (restore.isEmpty()) return
        val packet = command(SsiCodec.PARAM_SEND, SsiCodec.parameterWriteData(restore)) {
            SsiCodec.isAck(it) || SsiCodec.isNak(it)
        }
        throwIfNak(packet, SsiCodec.PARAM_SEND)
        for ((id, expected) in restore) {
            if (oneByteParameter(id) != expected) {
                throw DriverException("rollback_failed", "Rollback readback failed for parameter 0x${id.toString(16)}")
            }
        }
    }

    private suspend fun restoreSnapshots() {
        val restore = synchronized(sessionLock) { LinkedHashMap(snapshots) }
        if (restore.isEmpty()) return
        rollbackTo(restore)
    }

    private suspend fun oneByteParameter(id: Int): Int {
        val value = readParamInternal(id)
        if (value.size != 1) {
            throw DriverException("invalid_response", "Parameter 0x${id.toString(16)} is not a one-byte value")
        }
        return value[0].toInt() and 0xFF
    }

    private suspend fun commandExpectingAck(opcode: Int, data: ByteArray = ByteArray(0)) {
        val packet = command(opcode, data) { SsiCodec.isAck(it) || SsiCodec.isNak(it) }
        throwIfNak(packet, opcode)
        if (!SsiCodec.isAck(packet)) {
            throw DriverException("invalid_response", "Expected ACK for 0x${opcode.toString(16)}")
        }
    }

    private suspend fun command(
        opcode: Int,
        data: ByteArray = ByteArray(0),
        accepts: (SsiCodec.Packet) -> Boolean,
    ): SsiCodec.Packet = commandMutex.withLock {
        val active = ensureConnected()
        if (closing && !restoring) throw DriverException("closing", "Scanner driver is closing")
        if (desynchronized) throw DriverException("desynchronized", "SSI transport requires a physical hotplug")
        val request = synchronized(sessionLock) {
            if (transport !== active) throw DriverException("device_disconnected", "Scanner connection changed")
            Pending(opcode, active, generation, accepts, CompletableDeferred()).also {
                check(pending.compareAndSet(null, it)) { "Only one SSI request may be outstanding" }
            }
        }
        val startedAtNs = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAtNs) / 1_000_000
        events.onDiagnostic("SSI tx opcode=0x${opcode.toString(16)} state=$state ${active.diagnosticState()}")
        try {
            withTimeout(commandTimeoutMs) {
                active.send(SsiCodec.encode(opcode, data))
                events.onDiagnostic("SSI sent opcode=0x${opcode.toString(16)} elapsedMs=${elapsedMs()}")
                request.result.await().also {
                    events.onDiagnostic("SSI complete opcode=0x${opcode.toString(16)} elapsedMs=${elapsedMs()}")
                }
            }
        } catch (e: TimeoutCancellationException) {
            pending.compareAndSet(request, null)
            events.onDiagnostic(
                "SSI timeout opcode=0x${opcode.toString(16)} elapsedMs=${elapsedMs()} ${active.diagnosticState()}",
            )
            val failure = DriverException(
                "command_timeout",
                "No matching reply to SSI command 0x${opcode.toString(16)} within ${commandTimeoutMs}ms",
                e,
            )
            invalidate(active, failure)
            throw failure
        } catch (e: Throwable) {
            pending.compareAndSet(request, null)
            if (e is DriverException && e.code in NON_INVALIDATING_ERRORS) throw e
            val failure = if (e is DriverException) e else DriverException("usb_io", "SSI command failed", e)
            invalidate(active, failure)
            throw failure
        } finally {
            pending.compareAndSet(request, null)
        }
    }

    private suspend fun invalidate(active: MessageTransport, failure: DriverException) {
        synchronized(sessionLock) {
            if (transport !== active) return
            desynchronized = true
            resetSessionLocked()
            setState(Ss2222Driver.State.ERROR)
        }
        events.onError(failure.code, failure.message ?: failure.code)
        withContext(NonCancellable) {
            try {
                withTimeout(250) { active.send(SsiCodec.encode(SsiCodec.SCAN_DISABLE)) }
            } catch (_: Throwable) { }
            bestEffortClose(active)
        }
        synchronized(sessionLock) {
            if (transport === active) transport = null
        }
        events.onTransportInvalidated(active)
    }

    private fun acceptBarcode(source: MessageTransport, bytes: ByteArray) {
        val barcode: ByteArray
        var autoStop = false
        synchronized(sessionLock) {
            if (source !== transport || closing) return
            when (state) {
                Ss2222Driver.State.STARTING -> {
                    if (singleShot) {
                        if (earlyBarcodes.isEmpty()) earlyBarcodes += bytes.copyOf()
                    } else if (earlyBarcodes.size < MAX_EARLY_BARCODES) {
                        earlyBarcodes += bytes.copyOf()
                    }
                    return
                }
                Ss2222Driver.State.SCANNING -> {
                    if (singleShot && singleAccepted) return
                    barcode = bytes.copyOf()
                    if (singleShot) {
                        singleAccepted = true
                        autoStop = true
                    }
                }
                else -> return
            }
        }
        events.onBarcode(source, barcode)
        if (autoStop) scope.launch { stopAfterSingleRead() }
    }

    private suspend fun stopAfterSingleRead() {
        events.onDiagnostic("Auto-stop after single barcode")
        try {
            stop()
        } catch (_: Throwable) {
            // stop() already reports the terminal error through ControllerEvents.
        }
    }

    private fun resetSessionLocked() {
        singleShot = false
        singleAccepted = false
        earlyBarcodes.clear()
    }

    private fun ensureOperational() {
        if (state !in setOf(Ss2222Driver.State.IDLE, Ss2222Driver.State.SCANNING)) throw unavailable()
        ensureConnected()
    }

    private fun ensureConnected(): MessageTransport = transport
        ?: throw DriverException("not_connected", "Scanner is not connected")

    private fun unavailable() = DriverException("invalid_state", "Scanner is in state $state")

    private fun throwIfNak(packet: SsiCodec.Packet, opcode: Int) {
        if (packet.opcode == SsiCodec.NAK) {
            val reason = SsiCodec.nakReason(packet) ?: -1
            throw DriverException("nak", "Scanner rejected 0x${opcode.toString(16)} with NAK reason $reason")
        }
    }

    private fun setState(newState: Ss2222Driver.State) {
        if (state == newState) return
        state = newState
        events.onStateChanged(newState)
    }

    private fun report(error: Throwable) {
        val driver = error as? DriverException
        events.onError(driver?.code ?: "internal_error", error.message ?: "Unknown scanner error")
    }

    private fun checkGenerationLocked(expected: Long, target: MessageTransport) {
        if (generation != expected || transport !== target || closing) {
            throw DriverException("device_disconnected", "Scanner connection changed during initialization")
        }
    }

    private suspend fun bestEffortClose(target: MessageTransport) {
        try {
            withTimeout(500) { target.close() }
        } catch (_: Throwable) { }
    }

    private companion object {
        const val PARAM_TRIGGER_MODE = 0x8A
        const val PARAM_LIGHT_MODE = 0xF202
        const val TRIGGER_MODE_DEFAULT = 0x11
        const val LIGHT_MODE_FIRMWARE = 0x00
        const val MAX_EARLY_BARCODES = 32
        val NON_INVALIDATING_ERRORS = setOf("closing", "device_disconnected", "nak")
    }
}
