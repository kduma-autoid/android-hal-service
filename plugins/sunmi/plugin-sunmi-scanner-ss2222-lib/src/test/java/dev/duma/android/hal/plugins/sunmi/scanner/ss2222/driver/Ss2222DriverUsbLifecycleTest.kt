package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Ss2222DriverUsbLifecycleTest {

    @Test
    fun `stale and different permission broadcasts are ignored and denial reports error`() {
        val scanner = usbDevice(1, "/dev/bus/usb/001/001")
        val otherScanner = usbDevice(2, "/dev/bus/usb/001/002")
        val fixture = fixture(scanner, linkedMapOf("scanner" to scanner))
        var permission = false
        every { fixture.usb.hasPermission(any<UsbDevice>()) } answers {
            permission && args[0] === scanner
        }

        val requested = capturePermission(fixture)
        val errors = mutableListOf<String>()
        val errorLatch = CountDownLatch(1)
        val driver = driver(fixture.context, errors, errorLatch)
        try {
            driver.open()
            await(requested)
            idleMainLooper()
            assertEquals(Ss2222Driver.State.WAITING_FOR_PERMISSION, driver.state)
            val saved = requested.pending!!.let(::savedIntent)
            val generation = saved.getIntExtra("generation", -1)

            fixture.context.sendBroadcast(
                Intent(saved).putExtra("generation", generation + 1)
                    .putExtra(UsbManager.EXTRA_DEVICE, scanner)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true),
            )
            fixture.context.sendBroadcast(
                Intent(saved).putExtra("generation", generation)
                    .putExtra(UsbManager.EXTRA_DEVICE, otherScanner)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true),
            )
            idleMainLooper()
            assertEquals(Ss2222Driver.State.WAITING_FOR_PERMISSION, driver.state)
            verify(exactly = 0) { fixture.usb.openDevice(any()) }

            fixture.context.sendBroadcast(
                Intent(saved).putExtra(UsbManager.EXTRA_DEVICE, scanner)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
            )
            awaitLatch(errorLatch)
            idleMainLooper()
            assertEquals(Ss2222Driver.State.DISCONNECTED, driver.state)
            assertTrue(errors.contains("permission_denied"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `granted permission attempts open once and reports open failure`() {
        val scanner = usbDevice(3, "/dev/bus/usb/001/003")
        val fixture = fixture(scanner, linkedMapOf("scanner" to scanner))
        var permission = false
        every { fixture.usb.hasPermission(any<UsbDevice>()) } answers {
            permission && args[0] === scanner
        }
        val openCalls = CountDownLatch(1)
        every { fixture.usb.openDevice(scanner) } answers {
            openCalls.countDown()
            null
        }

        val requested = capturePermission(fixture)
        val errors = mutableListOf<String>()
        val errorLatch = CountDownLatch(1)
        val driver = driver(fixture.context, errors, errorLatch)
        try {
            driver.open()
            await(requested)
            val saved = requested.pending!!.let(::savedIntent)
            permission = true
            fixture.context.sendBroadcast(
                Intent(saved).putExtra(UsbManager.EXTRA_DEVICE, scanner)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true),
            )

            awaitLatch(openCalls)
            awaitLatch(errorLatch)
            idleMainLooper()
            assertEquals(Ss2222Driver.State.ERROR, driver.state)
            assertTrue(errors.contains("usb_open_failed"))
            verify(exactly = 1) { fixture.usb.openDevice(scanner) }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `two matching devices fail as ambiguous without requesting permission`() {
        val first = usbDevice(4, "/dev/bus/usb/001/004")
        val second = usbDevice(5, "/dev/bus/usb/001/005")
        val fixture = fixture(first, linkedMapOf("first" to first, "second" to second))
        val requested = CountDownLatch(1)
        every {
            fixture.usb.requestPermission(any<UsbDevice>(), any<PendingIntent>())
        } answers { requested.countDown() }
        val errors = mutableListOf<String>()
        val errorLatch = CountDownLatch(1)
        val driver = driver(fixture.context, errors, errorLatch)
        try {
            driver.open()
            awaitLatch(errorLatch)
            idleMainLooper()
            assertEquals(Ss2222Driver.State.ERROR, driver.state)
            assertTrue(errors.contains("ambiguous_device"))
            assertFalse(requested.await(100, TimeUnit.MILLISECONDS))
            verify(exactly = 0) {
                fixture.usb.requestPermission(any<UsbDevice>(), any<PendingIntent>())
            }
            verify(exactly = 0) { fixture.usb.openDevice(any<UsbDevice>()) }
        } finally {
            driver.close()
        }
    }

    private fun driver(
        context: Context,
        errors: MutableList<String>,
        errorLatch: CountDownLatch,
    ) = Ss2222Driver(context, object : Ss2222Driver.Listener {
        override fun onBarcode(raw: ByteArray) = Unit
        override fun onError(code: String, message: String) {
            synchronized(errors) { errors += code }
            errorLatch.countDown()
        }
    })

    private fun capturePermission(fixture: Fixture): PermissionCapture {
        val capture = PermissionCapture()
        every { fixture.usb.requestPermission(fixture.scanner, any()) } answers {
            capture.pending = args[1] as PendingIntent
            capture.latch.countDown()
        }
        return capture
    }

    private fun fixture(scanner: UsbDevice, devices: Map<String, UsbDevice>): Fixture {
        val usb = mockk<UsbManager>(relaxed = true)
        every { usb.deviceList } returns HashMap(devices)
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getSystemService(name: String): Any? =
                if (name == Context.USB_SERVICE) usb else super.getSystemService(name)
        }
        return Fixture(context, usb, scanner)
    }

    private fun usbDevice(id: Int, name: String): UsbDevice = mockk<UsbDevice>().also {
        every { it.vendorId } returns Ss2222Driver.VENDOR_ID
        every { it.productId } returns Ss2222Driver.PRODUCT_ID
        every { it.deviceId } returns id
        every { it.deviceName } returns name

        // The granted path must pass the transport descriptor checks before reaching the mocked
        // UsbManager.openDevice(null) failure.
        val if0 = mockk<UsbInterface>()
        val if1 = mockk<UsbInterface>()
        val input = mockk<UsbEndpoint>()
        val output = mockk<UsbEndpoint>()
        every { if0.id } returns 0
        every { if1.id } returns 1
        every { if1.interfaceClass } returns UsbConstants.USB_CLASS_HID
        every { if1.endpointCount } returns 2
        every { if1.getEndpoint(0) } returns input
        every { if1.getEndpoint(1) } returns output
        every { input.address } returns AndroidUsbMessageTransport.INPUT_ENDPOINT_ADDRESS
        every { input.direction } returns UsbConstants.USB_DIR_IN
        every { input.type } returns UsbConstants.USB_ENDPOINT_XFER_INT
        every { input.maxPacketSize } returns HidReportCodec.REPORT_SIZE
        every { output.address } returns AndroidUsbMessageTransport.OUTPUT_ENDPOINT_ADDRESS
        every { output.direction } returns UsbConstants.USB_DIR_OUT
        every { output.type } returns UsbConstants.USB_ENDPOINT_XFER_INT
        every { output.maxPacketSize } returns HidReportCodec.REPORT_SIZE
        every { it.interfaceCount } returns 2
        every { it.getInterface(0) } returns if0
        every { it.getInterface(1) } returns if1
    }

    private fun savedIntent(pending: PendingIntent): Intent = shadowOf(pending).savedIntent

    private fun await(capture: PermissionCapture) {
        awaitLatch(capture.latch)
        assertTrue(capture.pending != null)
    }

    private fun awaitLatch(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            idleMainLooper()
            if (latch.await(10, TimeUnit.MILLISECONDS)) return
        }
        assertEquals(0L, latch.count, "Timed out waiting for the USB lifecycle callback")
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class PermissionCapture {
        val latch = CountDownLatch(1)
        var pending: PendingIntent? = null
    }

    private data class Fixture(
        val context: Context,
        val usb: UsbManager,
        val scanner: UsbDevice,
    )
}
