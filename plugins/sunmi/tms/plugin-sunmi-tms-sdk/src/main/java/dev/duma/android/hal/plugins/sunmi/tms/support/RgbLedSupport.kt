package dev.duma.android.hal.plugins.sunmi.tms.support

import android.os.Parcel
import com.sunmi.tmsmaster.aidl.devicemanager.IDeviceManager

/**
 * Reliable detection of the CPad built-in RGB LED.
 *
 * The SDK's [IDeviceManager.isSupportRgbLed] contract is 0=Supported / -1=Not / -40=interface not
 * supported on this ROM / -41=service not found. But on ROMs whose TMS service lacks a handler for
 * the isSupportRgbLed transaction (e.g. FLEX), the unhandled transaction makes `IBinder.transact()`
 * return `false`, the reply Parcel is empty, and the AAR proxy decodes it as `readInt()==0` — i.e.
 * "method absent" is indistinguishable from "Supported", and the -40 code can never actually be
 * returned. The only trustworthy signal is the boolean from `transact()`, which the proxy swallows.
 *
 * This helper calls the binder directly and inspects that boolean:
 *  - `handled == false` → the method is absent on this ROM → reported as -40 (invalid_rom).
 *  - `handled == true`  → the decoded int is trustworthy (0=supported, -1=not, …).
 */
object RgbLedSupport {

    private const val DESCRIPTOR = "com.sunmi.tmsmaster.aidl.devicemanager.IDeviceManager"

    /** Method absent on this ROM (mirrors the SDK's -40 "interface not supported"). */
    const val CODE_METHOD_ABSENT = -40

    /** Failure talking to the binder (mirrors the SDK's -41 "service not found"). */
    const val CODE_TRANSACT_ERROR = -41

    /**
     * Transaction code for isSupportRgbLed, read reflectively from the generated Stub constant so it
     * stays correct across AAR versions; falls back to 87 (the value in the currently vendored AAR).
     */
    private val transactionCode: Int by lazy {
        try {
            Class.forName("$DESCRIPTOR\$Stub")
                .getDeclaredField("TRANSACTION_isSupportRgbLed")
                .apply { isAccessible = true }
                .getInt(null)
        } catch (_: Throwable) {
            87
        }
    }

    /**
     * Returns the real RGB LED support status for the connected device manager, working around the
     * empty-reply-decodes-as-0 quirk described above.
     */
    fun status(deviceManager: IDeviceManager): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            val handled = deviceManager.asBinder().transact(transactionCode, data, reply, 0)
            if (!handled) {
                CODE_METHOD_ABSENT
            } else {
                reply.readException()
                reply.readInt()
            }
        } catch (_: Exception) {
            CODE_TRANSACT_ERROR
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** True only when the device genuinely supports the built-in RGB LED (status code 0). */
    fun isSupported(deviceManager: IDeviceManager): Boolean = status(deviceManager) == 0
}
