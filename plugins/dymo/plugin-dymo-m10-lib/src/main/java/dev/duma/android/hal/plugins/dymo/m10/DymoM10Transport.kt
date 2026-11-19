package dev.duma.android.hal.plugins.dymo.m10

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.Closeable
import java.io.IOException

/** Input reports from a Dymo M10. The scale takes no commands, so there is nothing to send. */
interface DymoM10Transport : Closeable {
    /** Reads one input report, or returns null when nothing arrived within [timeoutMs]. */
    fun read(timeoutMs: Int): ByteArray?
}

/**
 * [DymoM10Transport] over Android's USB host API: claims the HID interface (forcibly — otherwise
 * the kernel's HID driver keeps it and reads come back empty) and reads its interrupt IN endpoint.
 *
 * Reports are logged at DEBUG when enabled: `adb shell setprop log.tag.DymoM10 DEBUG`.
 */
class UsbScaleTransport(manager: UsbManager, device: UsbDevice) : DymoM10Transport {

    private val usbInterface: UsbInterface
    private val inEndpoint: UsbEndpoint
    private val connection: UsbDeviceConnection

    init {
        usbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: if (device.interfaceCount > 0) device.getInterface(0)
            else throw IOException("Dymo M10 exposes no USB interface")

        inEndpoint = (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it) }.firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        } ?: throw IOException("Dymo M10 has no interrupt IN endpoint")

        connection = manager.openDevice(device) ?: throw IOException("Cannot open Dymo M10 (permission?)")
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw IOException("Cannot claim Dymo M10 HID interface")
        }
    }

    private val buffer = ByteArray(maxOf(inEndpoint.maxPacketSize, DymoM10Protocol.REPORT_SIZE))

    override fun read(timeoutMs: Int): ByteArray? {
        val n = connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeoutMs)
        if (n <= 0) return null
        val report = buffer.copyOf(n)
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "rx ${report.joinToString("") { "%02x".format(it) }}")
        return report
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
            // Device may already be gone.
        }
        connection.close()
    }

    private companion object {
        const val TAG = "DymoM10"
    }
}
