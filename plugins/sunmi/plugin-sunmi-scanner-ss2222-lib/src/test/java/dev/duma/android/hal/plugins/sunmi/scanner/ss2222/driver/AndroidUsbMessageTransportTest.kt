package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUsbMessageTransportTest {

    @Test
    fun `valid descriptor opens and claims only interface 1 forcefully`() {
        val fixture = fixture()
        every { fixture.manager.openDevice(fixture.device) } returns fixture.connection
        every { fixture.connection.claimInterface(fixture.if1, true) } returns true

        val transport = AndroidUsbMessageTransport.open(fixture.manager, fixture.device, callbacks())

        verify(exactly = 1) { fixture.manager.openDevice(fixture.device) }
        verify(exactly = 1) { fixture.connection.claimInterface(fixture.if1, true) }
        verify(exactly = 0) { fixture.connection.claimInterface(fixture.if0, any()) }

        runBlocking { transport.close() }

        verify(exactly = 1) { fixture.connection.releaseInterface(fixture.if1) }
        verify(exactly = 0) { fixture.connection.releaseInterface(fixture.if0) }
        verify(exactly = 1) { fixture.connection.close() }
    }

    @Test
    fun `missing interface 1 is rejected before openDevice`() {
        val fixture = fixture(includeInterface1 = false)

        val failure = assertFailsWith<DriverException> {
            AndroidUsbMessageTransport.open(fixture.manager, fixture.device, callbacks())
        }

        assertEquals("invalid_device", failure.code)
        verify(exactly = 0) { fixture.manager.openDevice(fixture.device) }
    }

    @Test
    fun `non HID interface 1 is rejected before openDevice`() {
        val fixture = fixture(interfaceClass = UsbConstants.USB_CLASS_VENDOR_SPEC)

        val failure = assertFailsWith<DriverException> {
            AndroidUsbMessageTransport.open(fixture.manager, fixture.device, callbacks())
        }

        assertEquals("invalid_device", failure.code)
        verify(exactly = 0) { fixture.manager.openDevice(fixture.device) }
    }

    @Test
    fun `wrong or missing interrupt endpoint is rejected before openDevice`() {
        val invalidDescriptors = listOf(
            fixture(inputAddress = 0x81),
            fixture(inputDirection = UsbConstants.USB_DIR_OUT),
            fixture(inputType = UsbConstants.USB_ENDPOINT_XFER_BULK),
            fixture(inputPacketSize = 32),
            fixture(endpointCount = 1),
            fixture(outputAddress = 0x03),
            fixture(outputDirection = UsbConstants.USB_DIR_IN),
            fixture(outputType = UsbConstants.USB_ENDPOINT_XFER_BULK),
            fixture(outputPacketSize = 32),
        )

        invalidDescriptors.forEach { invalid ->
            val failure = assertFailsWith<DriverException> {
                AndroidUsbMessageTransport.open(invalid.manager, invalid.device, callbacks())
            }

            assertEquals("invalid_device", failure.code)
            verify(exactly = 0) { invalid.manager.openDevice(invalid.device) }
        }
    }

    @Test
    fun `null openDevice fails without claiming`() {
        val fixture = fixture()
        every { fixture.manager.openDevice(fixture.device) } returns null

        val failure = assertFailsWith<DriverException> {
            AndroidUsbMessageTransport.open(fixture.manager, fixture.device, callbacks())
        }

        assertEquals("usb_open_failed", failure.code)
        verify(exactly = 1) { fixture.manager.openDevice(fixture.device) }
        verify(exactly = 0) { fixture.connection.claimInterface(any(), any()) }
    }

    @Test
    fun `claim failure closes connection and does not release an unclaimed interface`() {
        val fixture = fixture()
        every { fixture.manager.openDevice(fixture.device) } returns fixture.connection
        every { fixture.connection.claimInterface(fixture.if1, true) } returns false

        val failure = assertFailsWith<DriverException> {
            AndroidUsbMessageTransport.open(fixture.manager, fixture.device, callbacks())
        }

        assertEquals("usb_claim_failed", failure.code)
        verify(exactly = 1) { fixture.connection.claimInterface(fixture.if1, true) }
        verify(exactly = 0) { fixture.connection.claimInterface(fixture.if0, any()) }
        verify(exactly = 0) { fixture.connection.releaseInterface(any()) }
        verify(exactly = 1) { fixture.connection.close() }
    }

    @Test
    fun `zero length reads preserve ACK and fragmented revision without framing errors`() {
        val ack = hex("04 D0 00 00 FF 2C")
        val revision = byteArrayOf(0x60, 0xA4.toByte(), 0x00, 0x00) +
            "Product Name:SS2222 Product ID:2502D57G02104 Hardware version:V3.0.A Firmware version:2.4.94"
                .encodeToByteArray() + hex("E2 43")
        val revisionReports = HidReportCodec.encode(revision)
        assertEquals(2, revisionReports.size)

        val received = RecordingCallbacks(expectedMessages = 2)
        val reads = queuedReads(
            ReadStep.Zero,
            ReadStep.Zero,
            ReadStep.Zero,
            ReadStep.Report(HidReportCodec.encode(ack).single()),
            ReadStep.Zero,
            ReadStep.Report(revisionReports[0]),
            ReadStep.Zero,
            ReadStep.Report(revisionReports[1]),
        )
        val fixture = fixture(readScript = reads)
        every { fixture.manager.openDevice(fixture.device) } returns fixture.connection
        every { fixture.connection.claimInterface(fixture.if1, true) } returns true

        val transport = AndroidUsbMessageTransport.open(fixture.manager, fixture.device, received)
        try {
            assertTrue(received.awaitMessages(), "reader did not deliver ACK and revision")
            assertEquals(0, received.framingErrors.size)
            assertContentEquals(ack, received.messages[0])
            assertContentEquals(revision, received.messages[1])
        } finally {
            runBlocking { transport.close() }
        }
    }

    @Test
    fun `positive short read rejects partial message and recovers at next complete report`() {
        val staleMessageReports = HidReportCodec.encode(ByteArray(57) { (it + 1).toByte() })
        val freshMessage = "fresh barcode\r".encodeToByteArray()
        val received = RecordingCallbacks(expectedMessages = 1)
        val reads = queuedReads(
            ReadStep.Report(staleMessageReports[0]),
            ReadStep.Short(ByteArray(7) { 0x7F.toByte() }),
            ReadStep.Report(staleMessageReports[1]),
            ReadStep.Report(HidReportCodec.encode(freshMessage).single()),
        )
        val fixture = fixture(readScript = reads)
        every { fixture.manager.openDevice(fixture.device) } returns fixture.connection
        every { fixture.connection.claimInterface(fixture.if1, true) } returns true

        val transport = AndroidUsbMessageTransport.open(fixture.manager, fixture.device, received)
        try {
            assertTrue(received.awaitMessages(), "reader did not recover after short report")
            assertEquals(1, received.framingErrors.size)
            assertTrue(received.framingErrors.single().contains("got 7"))
            assertEquals(1, received.messages.size)
            assertContentEquals(freshMessage, received.messages.single())
        } finally {
            runBlocking { transport.close() }
        }
    }

    private fun callbacks() = object : AndroidUsbMessageTransport.Callbacks {
        override fun onMessage(transport: AndroidUsbMessageTransport, message: ByteArray) = Unit
        override fun onFramingError(transport: AndroidUsbMessageTransport, message: String) = Unit
    }

    private fun queuedReads(vararg steps: ReadStep) = QueuedReads(steps.toList())

    private fun hex(value: String): ByteArray = value.split(' ')
        .map { it.toInt(16).toByte() }.toByteArray()

    private fun fixture(
        includeInterface1: Boolean = true,
        interfaceClass: Int = UsbConstants.USB_CLASS_HID,
        inputAddress: Int = AndroidUsbMessageTransport.INPUT_ENDPOINT_ADDRESS,
        inputDirection: Int = UsbConstants.USB_DIR_IN,
        inputType: Int = UsbConstants.USB_ENDPOINT_XFER_INT,
        inputPacketSize: Int = HidReportCodec.REPORT_SIZE,
        outputAddress: Int = AndroidUsbMessageTransport.OUTPUT_ENDPOINT_ADDRESS,
        outputDirection: Int = UsbConstants.USB_DIR_OUT,
        outputType: Int = UsbConstants.USB_ENDPOINT_XFER_INT,
        outputPacketSize: Int = HidReportCodec.REPORT_SIZE,
        endpointCount: Int = 2,
        readScript: QueuedReads? = null,
    ): Fixture {
        val manager = mockk<UsbManager>()
        val device = mockk<UsbDevice>()
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        val if0 = mockk<UsbInterface>()
        val if1 = mockk<UsbInterface>()
        val input = endpoint(inputAddress, inputDirection, inputType, inputPacketSize)
        val output = endpoint(outputAddress, outputDirection, outputType, outputPacketSize)

        every { device.deviceId } returns 2222
        every { device.deviceName } returns "/dev/bus/usb/001/001"
        every { if0.id } returns 0
        every { if1.id } returns 1
        every { if1.interfaceClass } returns interfaceClass
        every { if1.endpointCount } returns endpointCount
        every { if1.getEndpoint(0) } returns input
        if (endpointCount > 1) {
            every { if1.getEndpoint(1) } returns output
        }

        if (includeInterface1) {
            every { device.interfaceCount } returns 2
            every { device.getInterface(0) } returns if0
            every { device.getInterface(1) } returns if1
        } else {
            every { device.interfaceCount } returns 1
            every { device.getInterface(0) } returns if0
        }

        every {
            connection.bulkTransfer(any<UsbEndpoint>(), any<ByteArray>(), any(), any())
        } answers {
            readScript?.read(args[1] as ByteArray) ?: -1
        }

        return Fixture(manager, device, connection, if0, if1)
    }

    private fun endpoint(address: Int, direction: Int, type: Int, packetSize: Int): UsbEndpoint =
        mockk<UsbEndpoint>().also {
            every { it.address } returns address
            every { it.direction } returns direction
            every { it.type } returns type
            every { it.maxPacketSize } returns packetSize
        }

    private data class Fixture(
        val manager: UsbManager,
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val if0: UsbInterface,
        val if1: UsbInterface,
    )

    private sealed interface ReadStep {
        data object Zero : ReadStep
        data class Report(val bytes: ByteArray) : ReadStep
        data class Short(val bytes: ByteArray) : ReadStep
    }

    private class QueuedReads(steps: List<ReadStep>) {
        private val steps = ConcurrentLinkedQueue<ReadStep>().apply { addAll(steps) }

        fun read(buffer: ByteArray): Int = when (val step = steps.poll()) {
            null -> -1
            ReadStep.Zero -> 0
            is ReadStep.Report -> copy(step.bytes, buffer)
            is ReadStep.Short -> copy(step.bytes, buffer)
        }

        private fun copy(bytes: ByteArray, buffer: ByteArray): Int {
            if (bytes.size > buffer.size) error("queued read is larger than USB buffer")
            bytes.copyInto(buffer)
            return bytes.size
        }
    }

    private class RecordingCallbacks(expectedMessages: Int) : AndroidUsbMessageTransport.Callbacks {
        val messages = CopyOnWriteArrayList<ByteArray>()
        val framingErrors = CopyOnWriteArrayList<String>()
        private val messageLatch = CountDownLatch(expectedMessages)

        override fun onMessage(transport: AndroidUsbMessageTransport, message: ByteArray) {
            messages += message.copyOf()
            messageLatch.countDown()
        }

        override fun onFramingError(transport: AndroidUsbMessageTransport, message: String) {
            framingErrors += message
        }

        fun awaitMessages(): Boolean = messageLatch.await(2, TimeUnit.SECONDS)
    }
}
