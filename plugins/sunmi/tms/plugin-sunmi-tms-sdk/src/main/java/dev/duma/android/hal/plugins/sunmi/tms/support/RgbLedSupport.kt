package dev.duma.android.hal.plugins.sunmi.tms.support

import android.os.Parcel
import com.sunmi.tmsmaster.aidl.devicemanager.IDeviceManager

/**
 * Reliable detection of the CPad built-in RGB LED.
 *
 * Primary signal: the system property [PROP_BUILTIN_LED] (`persist.sys.led.light.support`), which is
 * a positive, static device attribute — verified `"1"` on CPad and empty on FLEX. It is a `system_prop`
 * (SELinux-readable by untrusted_app); the only caveat is that the Java accessor
 * `android.os.SystemProperties.get` is a non-SDK (greylist) API, so [readProp] falls back to the
 * `getprop` binary when reflection is blocked by hidden-API policy.
 *
 * Fallback signal: the SDK's [IDeviceManager.isSupportRgbLed] contract is 0=Supported / -1=Not /
 * -40=interface not supported on this ROM / -41=service not found. On ROMs whose TMS service lacks a
 * handler for the isSupportRgbLed transaction (e.g. FLEX), the unhandled transaction makes
 * `IBinder.transact()` return `false`, the reply Parcel is empty, and the AAR proxy decodes it as
 * `readInt()==0` — i.e. "method absent" would be indistinguishable from "Supported". So [transactStatus]
 * calls the binder directly and inspects the boolean the proxy swallows:
 *  - `handled == false` → the method is absent on this ROM → reported as -40 (invalid_rom).
 *  - `handled == true`  → the decoded int is trustworthy (0=supported, -1=not, …).
 */
object RgbLedSupport {

    private const val DESCRIPTOR = "com.sunmi.tmsmaster.aidl.devicemanager.IDeviceManager"

    /** System property advertising the built-in RGB LED: "1" = present (CPad), "" = absent (FLEX). */
    private const val PROP_BUILTIN_LED = "persist.sys.led.light.support"

    /** Device genuinely supports the built-in RGB LED. */
    const val CODE_SUPPORTED = 0

    /** Device explicitly does not support the built-in RGB LED. */
    const val CODE_UNSUPPORTED = -1

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
     * Real RGB LED support status. Trusts the [PROP_BUILTIN_LED] property first ("1"=supported,
     * "0"=unsupported); if the property is absent/empty it falls back to the raw-transact probe.
     */
    fun status(deviceManager: IDeviceManager): Int = when (readProp(PROP_BUILTIN_LED)) {
        "1" -> CODE_SUPPORTED
        "0" -> CODE_UNSUPPORTED
        else -> transactStatus(deviceManager)
    }

    /** True only when the device genuinely supports the built-in RGB LED (status code 0). */
    fun isSupported(deviceManager: IDeviceManager): Boolean = status(deviceManager) == CODE_SUPPORTED

    /**
     * Property-only support check — synchronous and requires no TMS connection, so it can be used as a
     * static registration gate ([dev.duma.android.hal.contract.HalPlugin.isSupported]). The built-in
     * RGB LED is a fixed hardware attribute; a device either has it or never will.
     */
    fun hasBuiltinLedByProperty(): Boolean = readProp(PROP_BUILTIN_LED) == "1"

    /**
     * Reads a system property, layering to avoid the hidden-API greylist concern:
     *  1) `android.os.SystemProperties.get(key, "")` via reflection — trusted when it does not throw
     *     (including an empty value, e.g. on FLEX; no extra process fork);
     *  2) only if reflection is blocked by hidden-API policy (throws) → the public `getprop` binary.
     */
    private fun readProp(key: String): String {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            return get.invoke(null, key, "") as String
        } catch (_: Throwable) {
            // Reflection blocked → fall back to the getprop binary (no hidden API; SELinux-allowed).
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            out
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Raw-transact probe used when the property is inconclusive. Inspects the boolean returned by
     * `IBinder.transact()` (which the AAR proxy swallows) so an unhandled transaction on this ROM
     * yields -40 instead of a bogus 0.
     */
    private fun transactStatus(deviceManager: IDeviceManager): Int {
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
}
