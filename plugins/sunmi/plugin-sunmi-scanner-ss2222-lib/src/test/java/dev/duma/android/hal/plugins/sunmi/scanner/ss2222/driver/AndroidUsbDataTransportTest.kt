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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The scanner in TTDATA (data) mode: `AF99:8001`, bare SSI and raw codes on interface 0's bulk endpoints. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUsbDataTransportTest {

    private val manager = mockk<UsbManager>()
    private val device = mockk<UsbDevice>()
    private val connection = mockk<UsbDeviceConnection>(relaxed = true)
    private val dataInterface = mockk<UsbInterface>()
    private val input = endpoint(0x81, UsbConstants.USB_DIR_IN)
    private val output = endpoint(0x01, UsbConstants.USB_DIR_OUT)
    private val reads = ConcurrentLinkedQueue<ByteArray>()
    private val writes = CopyOnWriteArrayList<ByteArray>()

    init {
        every { device.deviceId } returns 3333
        every { device.deviceName } returns "/dev/bus/usb/004/021"
        every { device.vendorId } returns Ss2222Driver.DATA_VENDOR_ID
        every { device.productId } returns Ss2222Driver.DATA_PRODUCT_ID
        every { device.interfaceCount } returns 1
        every { device.getInterface(0) } returns dataInterface
        every { dataInterface.id } returns 0
        every { dataInterface.interfaceClass } returns UsbConstants.USB_CLASS_VENDOR_SPEC
        every { dataInterface.endpointCount } returns 2
        every { dataInterface.getEndpoint(0) } returns input
        every { dataInterface.getEndpoint(1) } returns output
        every { manager.openDevice(device) } returns connection
        every { connection.claimInterface(dataInterface, true) } returns true
        every { connection.bulkTransfer(any<UsbEndpoint>(), any<ByteArray>(), any(), any()) } answers {
            val ep = args[0] as UsbEndpoint
            val buffer = args[1] as ByteArray
            val length = args[2] as Int
            if (ep === output) {
                writes += buffer.copyOf(length)
                length
            } else {
                val next = reads.poll()
                if (next == null) {
                    Thread.sleep(5)
                    -1
                } else {
                    next.copyInto(buffer)
                    next.size
                }
            }
        }
    }

    @Test
    fun `data mode claims interface 0 and recognises the TTDATA identity`() {
        assertEquals(UsbMode.DATA, Ss2222Driver.usbModeOf(device))
        val transport = AndroidUsbMessageTransport.open(manager, device, Recorder(0))
        try {
            assertEquals(UsbMode.DATA, transport.mode)
            assertEquals(AndroidUsbMessageTransport.USB_MODE_KEYBOARD_HID, transport.releaseUsbMode)
            verify(exactly = 1) { connection.claimInterface(dataInterface, true) }
        } finally {
            runBlocking { transport.close() }
        }
        verify(exactly = 1) { connection.releaseInterface(dataInterface) }
    }

    @Test
    fun `commands go out as bare SSI packets, without the HID report wrapper`() {
        val transport = AndroidUsbMessageTransport.open(manager, device, Recorder(0))
        val revisionRequest = SsiCodec.encode(SsiCodec.REQUEST_REVISION)
        try {
            runBlocking { transport.send(revisionRequest) }
        } finally {
            runBlocking { transport.close() }
        }
        assertContentEquals(revisionRequest, writes.single())
    }

    @Test
    fun `each bulk transfer is one message - a code ending in CR or an SSI reply`() {
        val code = "AQR|Z0T3|2/15|ted QR demo.\r".encodeToByteArray()
        val revision = SsiCodec.encode(
            SsiCodec.REPLY_REVISION,
            "Product Name:SS2222 Product ID:2502D57G02104 Hardware version:V3.0.A Firmware version:2.4.94".encodeToByteArray(),
            source = SsiCodec.SOURCE_SCANNER,
        )
        reads += code
        reads += ByteArray(0) // a zero-length read is not a message
        reads += revision
        val received = Recorder(expected = 2)
        val transport = AndroidUsbMessageTransport.open(manager, device, received)
        try {
            assertTrue(received.await(), "reader did not deliver both messages")
            assertContentEquals(code, received.messages[0])
            assertContentEquals(revision, received.messages[1])
        } finally {
            runBlocking { transport.close() }
        }
    }

    @Test
    fun `a data interface that is not vendor specific is rejected before openDevice`() {
        every { dataInterface.interfaceClass } returns UsbConstants.USB_CLASS_HID
        val failure = assertFailsWith<DriverException> { AndroidUsbMessageTransport.open(manager, device, Recorder(0)) }
        assertEquals("invalid_device", failure.code)
        verify(exactly = 0) { manager.openDevice(device) }
    }

    @Test
    fun `other devices are not the scanner`() {
        val other = mockk<UsbDevice>()
        every { other.vendorId } returns 0xAF99
        every { other.productId } returns 0x9000
        assertNull(Ss2222Driver.usbModeOf(other))
    }

    private fun endpoint(address: Int, direction: Int): UsbEndpoint = mockk<UsbEndpoint>().also {
        every { it.address } returns address
        every { it.direction } returns direction
        every { it.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
        every { it.maxPacketSize } returns 64
    }

    private class Recorder(expected: Int) : AndroidUsbMessageTransport.Callbacks {
        val messages = CopyOnWriteArrayList<ByteArray>()
        private val latch = CountDownLatch(expected)
        override fun onMessage(transport: AndroidUsbMessageTransport, message: ByteArray) {
            messages += message
            latch.countDown()
        }
        override fun onFramingError(transport: AndroidUsbMessageTransport, message: String) = Unit
        fun await() = latch.await(3, TimeUnit.SECONDS)
    }
}
