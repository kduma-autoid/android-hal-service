package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent

/**
 * Consumes the keyboard reports emitted by the SS2222's HID keyboard interface.
 *
 * The service deliberately does not inspect accessibility window content.  It is enabled as a
 * global key filter so that the scanner's trailing Enter cannot activate a focused control while
 * a HAL client is handling the scan through the vendor interface.
 */
class Ss2222KeyboardFilterService : AccessibilityService() {

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Window/content events are not used by this filter.
    }

    override fun onInterrupt() {
        // There is no buffered operation to interrupt.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        return isSs2222KeyEvent(event.action, device.vendorId, device.productId)
    }

    companion object {
        const val USB_VENDOR_ID = 0x324F
        const val USB_PRODUCT_ID = 0x0113

        private const val HAL_SERVICE_PACKAGE = "dev.duma.android.hal.service"
        private const val SUNMI_BUNDLE_PACKAGE = "dev.duma.android.hal.plugins.sunmi"

        /** The class name is intentionally exact; similarly named accessibility services do not count. */
        const val SERVICE_CLASS_NAME =
            "dev.duma.android.hal.plugins.sunmi.scanner.ss2222.Ss2222KeyboardFilterService"

        /**
         * Tests whether [event] is one of the two key transitions from the SS2222 keyboard HID.
         * All other input-device events, including malformed/unknown actions, pass through.
         */
        internal fun isSs2222KeyEvent(action: Int, vendorId: Int?, productId: Int?): Boolean {
            if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
            return vendorId == USB_VENDOR_ID && productId == USB_PRODUCT_ID
        }

        /**
         * Reports whether the exact SS2222 filter service is enabled in Android settings.
         *
         * Settings stores flattened components as package/class pairs.  The same library is used
         * by the HAL APK and by the standalone Sunmi bundle, so both package names are accepted;
         * the class name is never matched by suffix or by a broad package prefix.
         */
        @JvmStatic
        fun isEnabled(context: Context): Boolean {
            if (Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0,
                ) != 1
            ) return false

            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val acceptedPackages = setOf(
                context.packageName,
                HAL_SERVICE_PACKAGE,
                SUNMI_BUNDLE_PACKAGE,
            )
            return enabled.split(':').asSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .any { component ->
                    component.className == SERVICE_CLASS_NAME && component.packageName in acceptedPackages
                }
        }
    }
}
