package dev.duma.android.hal.plugins.msr605x

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.Closeable
import java.io.IOException

/** Raw packet I/O to an MSR605X. Packets are always [Msr605xProtocol.PACKET_SIZE] bytes. */
interface Msr605xTransport : Closeable {
    /** Sends one command packet. */
    fun write(packet: ByteArray)

    /** Sends one feature report (report id 0). */
    fun sendFeature(packet: ByteArray)

    /** Reads one input report, or returns null when nothing arrived within [timeoutMs]. */
    fun read(timeoutMs: Int): ByteArray?
}

/**
 * [Msr605xTransport] over Android's USB host API. The MSR605X is a HID device without report ids
 * and with only an interrupt IN endpoint: input reports arrive there, and every packet goes out as
 * a HID SET_REPORT(**Feature**) control transfer.
 *
 * The device silently ignores SET_REPORT(Output) with report id 0 (the transfer is ACKed, nothing
 * happens), which is all a raw control transfer can send as an output report. The reference driver
 * hits the same wall on macOS and also uses Feature reports there; Linux only gets away with Output
 * reports because hidraw sends them with report id 0xFF.
 *
 * Packet traffic is logged at DEBUG only when enabled (`adb shell setprop log.tag.Msr605x DEBUG`),
 * as it contains card data.
 */
class UsbHidTransport(manager: UsbManager, device: UsbDevice) : Msr605xTransport {

    private val usbInterface: UsbInterface
    private val inEndpoint: UsbEndpoint
    private val connection: UsbDeviceConnection

    init {
        usbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: if (device.interfaceCount > 0) device.getInterface(0)
            else throw IOException("MSR605X exposes no USB interface")

        inEndpoint = (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it) }.firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        } ?: throw IOException("MSR605X has no interrupt IN endpoint")

        connection = manager.openDevice(device) ?: throw IOException("Cannot open MSR605X (permission?)")
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw IOException("Cannot claim MSR605X HID interface")
        }
    }

    override fun write(packet: ByteArray) = sendFeature(packet)

    override fun sendFeature(packet: ByteArray) {
        val result = connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE_OUT,
            HID_SET_REPORT,
            (REPORT_TYPE_FEATURE shl 8) or 0, // report id 0
            usbInterface.id,
            packet,
            packet.size,
            WRITE_TIMEOUT_MS
        )
        if (debug) Log.d(TAG, "tx result=$result ${packet.copyOf(packet.indexOfLast { it != 0.toByte() } + 1).toHex()}")
        if (result < 0) throw IOException("MSR605X write failed ($result)")
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val buffer = ByteArray(maxOf(inEndpoint.maxPacketSize, Msr605xProtocol.PACKET_SIZE))
        val n = connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeoutMs)
        if (n <= 0) return null
        val packet = buffer.copyOf(n)
        // The device reuses its report buffer, so only the header-declared length is meaningful.
        if (debug) Log.d(TAG, "rx ${packet.copyOf(minOf(n, 1 + (packet[0].toInt() and 0x3F))).toHex()}")
        return packet
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
            // Device may already be gone.
        }
        connection.close()
    }

    private val debug: Boolean get() = Log.isLoggable(TAG, Log.DEBUG)

    private companion object {
        const val TAG = "Msr605x"
        const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
        const val HID_SET_REPORT = 0x09
        const val REPORT_TYPE_FEATURE = 0x03
        const val WRITE_TIMEOUT_MS = 1000

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
