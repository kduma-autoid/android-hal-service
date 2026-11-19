package dev.duma.android.hal.service.keyboardfilter

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.duma.android.hal.contract.KeyboardDeviceId
import dev.duma.android.hal.contract.KeyboardFilter
import dev.duma.android.hal.service.service.HalService
import java.util.concurrent.ConcurrentHashMap

/**
 * HAL's single keyboard filter. Consumes key DOWN/UP events coming from the keyboard-emulating
 * devices that registered plugins declare in `HalPlugin.getKeyboardFilterDevices()` — built-in and
 * bundle plugins alike — so a scanner's or card reader's typed characters (and trailing Enter) never
 * reach the focused app while the plugin delivers the data through its own API.
 *
 * One service for all plugins: the user enables it once in Android's accessibility settings, however
 * many such devices exist. With no plugin declaring a device it filters nothing. It runs in the HAL
 * process and reads the registry's snapshot; when HAL is not running everything passes through.
 *
 * It never inspects window content. Its class name is part of the contract
 * ([KeyboardFilter.SERVICE_CLASS_NAME]) because plugins check whether it is enabled.
 */
class HalKeyboardFilterService : AccessibilityService() {

    /** Input device ids already described in the debug log, to log each device only once. */
    private val loggedDevices = ConcurrentHashMap.newKeySet<Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window/content events are not used.
    }

    override fun onInterrupt() {
        // Nothing buffered to interrupt.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        val devices = HalService.pluginRegistry?.keyboardFilterDevices ?: emptySet()
        val consume = shouldConsume(event.action, device.vendorId, device.productId, devices)
        if (Log.isLoggable(TAG, Log.DEBUG) && loggedDevices.add(device.id)) {
            // Device metadata only — never key codes, which would leak scanned or swiped data.
            Log.d(
                TAG,
                "Input device id=${device.id} name='${device.name}' " +
                    "usb=${KeyboardDeviceId(device.vendorId, device.productId)} " +
                    "descriptor=${device.descriptor} filtered=$consume",
            )
        }
        return consume
    }

    companion object {
        /** Enable with `adb shell setprop log.tag.HalKbdFilter DEBUG`. */
        const val TAG = "HalKbdFilter"

        /**
         * Only the two key transitions from a registered device are consumed. Everything else —
         * other keyboards, unknown or malformed actions — passes through.
         */
        internal fun shouldConsume(
            action: Int,
            vendorId: Int,
            productId: Int,
            devices: Set<KeyboardDeviceId>,
        ): Boolean {
            if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
            return KeyboardDeviceId(vendorId, productId) in devices
        }
    }
}
