package dev.duma.android.hal.plugins.msr605x

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException

/** Raw packet I/O to an MSR605X. Packets are always [Msr605xProtocol.PACKET_SIZE] bytes. */
interface Msr605xTransport : Closeable {
    /** Sends one output report. */
    fun write(packet: ByteArray)

    /** Sends one feature report (report id 0). */
    fun sendFeature(packet: ByteArray)

    /** Reads one input report, or returns null when nothing arrived within [timeoutMs]. */
    fun read(timeoutMs: Int): ByteArray?
}

/**
 * [Msr605xTransport] over Android's USB host API. The MSR605X is a HID device without report ids:
 * input reports arrive on the interrupt IN endpoint; output reports go to the interrupt OUT endpoint
 * when the device has one, otherwise as a HID SET_REPORT(Output) control transfer. Feature reports
 * always use SET_REPORT(Feature).
 */
class UsbHidTransport(manager: UsbManager, device: UsbDevice) : Msr605xTransport {

    private val usbInterface: UsbInterface
    private val inEndpoint: UsbEndpoint
    private val outEndpoint: UsbEndpoint?
    private val connection: UsbDeviceConnection

    init {
        usbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: if (device.interfaceCount > 0) device.getInterface(0)
            else throw IOException("MSR605X exposes no USB interface")

        val endpoints = (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it) }
        inEndpoint = endpoints.firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        } ?: throw IOException("MSR605X has no interrupt IN endpoint")
        outEndpoint = endpoints.firstOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        }

        connection = manager.openDevice(device) ?: throw IOException("Cannot open MSR605X (permission?)")
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw IOException("Cannot claim MSR605X HID interface")
        }
    }

    override fun write(packet: ByteArray) {
        val sent = if (outEndpoint != null) {
            connection.bulkTransfer(outEndpoint, packet, packet.size, WRITE_TIMEOUT_MS)
        } else {
            setReport(REPORT_TYPE_OUTPUT, packet)
        }
        if (sent < 0) throw IOException("MSR605X write failed")
    }

    override fun sendFeature(packet: ByteArray) {
        if (setReport(REPORT_TYPE_FEATURE, packet) < 0) throw IOException("MSR605X feature report failed")
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val buffer = ByteArray(maxOf(inEndpoint.maxPacketSize, Msr605xProtocol.PACKET_SIZE))
        val n = connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeoutMs)
        return if (n > 0) buffer.copyOf(n) else null
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
            // Device may already be gone.
        }
        connection.close()
    }

    private fun setReport(reportType: Int, packet: ByteArray): Int = connection.controlTransfer(
        REQUEST_TYPE_CLASS_INTERFACE_OUT,
        HID_SET_REPORT,
        (reportType shl 8) or 0, // report id 0
        usbInterface.id,
        packet,
        packet.size,
        WRITE_TIMEOUT_MS
    )

    private companion object {
        const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
        const val HID_SET_REPORT = 0x09
        const val REPORT_TYPE_OUTPUT = 0x02
        const val REPORT_TYPE_FEATURE = 0x03
        const val WRITE_TIMEOUT_MS = 1000
    }
}
