package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidUsbMessageTransport private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val controlInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    private val callbacks: Callbacks,
) : MessageTransport {
    interface Callbacks {
        fun onMessage(transport: AndroidUsbMessageTransport, message: ByteArray)
        fun onFramingError(transport: AndroidUsbMessageTransport, message: String)
    }

    val deviceKey: String = "${device.deviceId}:${device.deviceName}"
    private val running = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private val scanEpoch = AtomicLong(0)
    private val writeLock = Any()
    private val reader = Reader().also { it.start() }

    override suspend fun send(message: ByteArray) = withContext(Dispatchers.IO) {
        if (closed.get()) throw DriverException("device_disconnected", "USB transport is closed")
        val reports = try {
            HidReportCodec.encode(message)
        } catch (e: IllegalArgumentException) {
            throw DriverException("invalid_message", e.message ?: "Invalid HID message", e)
        }
        synchronized(writeLock) {
            for (report in reports) {
                if (closed.get()) throw DriverException("device_disconnected", "USB transport is closed")
                val written = connection.bulkTransfer(output, report, report.size, WRITE_TIMEOUT_MS)
                if (written != report.size) {
                    throw DriverException("usb_write_failed", "USB write returned $written of ${report.size} bytes")
                }
            }
        }
    }

    override fun beginScanSession() {
        scanEpoch.incrementAndGet()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        running.set(false)
        try {
            reader.join(READER_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            connection.releaseInterface(controlInterface)
        } catch (_: Throwable) { }
        connection.close()
        if (reader.isAlive) {
            try {
                reader.join(READER_JOIN_AFTER_CLOSE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private inner class Reader : Thread("ss2222-usb-reader") {
        override fun run() {
            val report = ByteArray(HidReportCodec.REPORT_SIZE)
            val assembler = HidMessageAssembler()
            var consecutiveErrors = 0
            while (running.get()) {
                val read = connection.bulkTransfer(input, report, report.size, READ_TIMEOUT_MS)
                if (!running.get()) break
                if (read != report.size) {
                    // Flex 3 reports zero-byte USB transfers between valid HID reports. No
                    // fragment was consumed, so keep the current message and its boundary.
                    // Only a positive short read has consumed a malformed/partial report.
                    if (read > 0) {
                        try {
                            assembler.rejectShortReport(read)
                        } catch (e: FramingException) {
                            callbacks.onFramingError(
                                this@AndroidUsbMessageTransport,
                                e.message ?: "Malformed HID report",
                            )
                        }
                    }
                    if (read <= 0 && ++consecutiveErrors >= ERROR_BACKOFF_THRESHOLD) {
                        try {
                            sleep(ERROR_BACKOFF_MS)
                        } catch (_: InterruptedException) {
                            interrupt()
                            break
                        }
                    }
                    continue
                }
                consecutiveErrors = 0
                try {
                    assembler.acceptTagged(
                        report.copyOf(),
                        SystemClock.elapsedRealtime(),
                        scanEpoch.get(),
                    )?.let { completed ->
                        if (completed.epoch == scanEpoch.get()) {
                            callbacks.onMessage(this@AndroidUsbMessageTransport, completed.bytes)
                        }
                    }
                } catch (e: FramingException) {
                    callbacks.onFramingError(
                        this@AndroidUsbMessageTransport,
                        e.message ?: "Malformed HID report",
                    )
                }
            }
            assembler.reset()
        }
    }

    companion object {
        const val CONTROL_INTERFACE_ID = 1
        const val INPUT_ENDPOINT_ADDRESS = 0x82
        const val OUTPUT_ENDPOINT_ADDRESS = 0x02

        fun open(
            usbManager: UsbManager,
            device: UsbDevice,
            callbacks: Callbacks,
        ): AndroidUsbMessageTransport {
            val controlInterface = (0 until device.interfaceCount)
                .map(device::getInterface)
                .singleOrNull { it.id == CONTROL_INTERFACE_ID }
                ?: throw DriverException("invalid_device", "SS2222 HID interface 1 was not found")
            if (controlInterface.interfaceClass != UsbConstants.USB_CLASS_HID) {
                throw DriverException("invalid_device", "Interface 1 is not HID")
            }
            val endpoints = (0 until controlInterface.endpointCount).map(controlInterface::getEndpoint)
            val input = endpoints.singleOrNull {
                it.address == INPUT_ENDPOINT_ADDRESS &&
                    it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    it.maxPacketSize == HidReportCodec.REPORT_SIZE
            } ?: throw DriverException("invalid_device", "Expected interrupt IN endpoint 0x82/64 was not found")
            val output = endpoints.singleOrNull {
                it.address == OUTPUT_ENDPOINT_ADDRESS &&
                    it.direction == UsbConstants.USB_DIR_OUT &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    it.maxPacketSize == HidReportCodec.REPORT_SIZE
            } ?: throw DriverException("invalid_device", "Expected interrupt OUT endpoint 0x02/64 was not found")

            val connection = usbManager.openDevice(device)
                ?: throw DriverException("usb_open_failed", "Could not open ${device.deviceName}")
            if (!connection.claimInterface(controlInterface, true)) {
                connection.close()
                throw DriverException("usb_claim_failed", "Could not claim SS2222 interface 1")
            }
            return AndroidUsbMessageTransport(
                device,
                connection,
                controlInterface,
                input,
                output,
                callbacks,
            )
        }

        private const val READ_TIMEOUT_MS = 300
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val READER_JOIN_MS = 400L
        private const val READER_JOIN_AFTER_CLOSE_MS = 200L
        private const val ERROR_BACKOFF_THRESHOLD = 4
        private const val ERROR_BACKOFF_MS = 25L
    }
}
