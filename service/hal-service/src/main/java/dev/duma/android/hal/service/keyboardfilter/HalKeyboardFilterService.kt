package dev.duma.android.hal.service.keyboardfilter

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.duma.android.hal.contract.KeyOrigin
import dev.duma.android.hal.contract.KeyboardFilter
import dev.duma.android.hal.contract.KeyboardFilterRule
import dev.duma.android.hal.service.service.HalService
import java.util.concurrent.ConcurrentHashMap

/**
 * HAL's single keyboard filter. Consumes key DOWN/UP events matching the rules registered plugins
 * declare in `HalPlugin.getKeyboardFilterRules()` — built-in and bundle plugins alike — so a
 * scanner's or card reader's typed characters (and trailing Enter) never reach the focused app while
 * the plugin delivers the data through its own API.
 *
 * One service for all plugins: the user enables it once in Android's accessibility settings, however
 * many such peripherals exist. With no plugin declaring a rule it filters nothing. It runs in the HAL
 * process and reads the registry's snapshot; when HAL is not running everything passes through.
 *
 * It never inspects window content. Its class name is part of the contract
 * ([KeyboardFilter.SERVICE_CLASS_NAME]) because plugins check whether it is enabled.
 */
class HalKeyboardFilterService : AccessibilityService() {

    /** `deviceId/source` pairs already described in the debug log, to log each only once. */
    private val loggedOrigins = ConcurrentHashMap.newKeySet<String>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window/content events are not used.
    }

    override fun onInterrupt() {
        // Nothing buffered to interrupt.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        val rules = HalService.pluginRegistry?.keyboardFilterRules ?: emptySet()
        val origin = KeyOrigin(device.vendorId, device.productId, event.source)
        val consume = shouldConsume(event.action, origin, rules)
        if (Log.isLoggable(TAG, Log.DEBUG) && loggedOrigins.add("${device.id}/${event.source}")) {
            // Device metadata only — never key codes, which would leak scanned or swiped data.
            Log.d(
                TAG,
                "Input device id=${device.id} name='${device.name}' " +
                    "usb=%04X:%04X source=0x%08X ".format(device.vendorId, device.productId, event.source) +
                    "descriptor=${device.descriptor} filtered=$consume",
            )
        }
        return consume
    }

    companion object {
        /** Enable with `adb shell setprop log.tag.HalKbdFilter DEBUG`. */
        const val TAG = "HalKbdFilter"

        /**
         * Only the two key transitions matching a registered rule are consumed. Everything else —
         * other keyboards, other injected input, unknown or malformed actions — passes through.
         */
        internal fun shouldConsume(action: Int, origin: KeyOrigin, rules: Set<KeyboardFilterRule>): Boolean {
            if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
            return rules.any { it.matches(origin) }
        }
    }
}
