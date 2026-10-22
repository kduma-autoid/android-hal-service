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
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun callbacks() = object : AndroidUsbMessageTransport.Callbacks {
        override fun onMessage(transport: AndroidUsbMessageTransport, message: ByteArray) = Unit
        override fun onFramingError(transport: AndroidUsbMessageTransport, message: String) = Unit
    }

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

        // Keep the reader thread deterministic and short-lived when close() is tested.
        every {
            connection.bulkTransfer(any<UsbEndpoint>(), any<ByteArray>(), any(), any())
        } returns -1

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
}
