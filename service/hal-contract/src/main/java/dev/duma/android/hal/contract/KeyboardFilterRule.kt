package dev.duma.android.hal.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What HAL's keyboard filter swallows for a plugin: key events of a peripheral that also "types"
 * its data (a scanner or a magnetic stripe reader), while the plugin delivers that data through its
 * own API. A plugin lists its rules in [HalPlugin.getKeyboardFilterRules]; an event matching any
 * registered rule is consumed.
 *
 * The filter is an accessibility service, so it only sees key events of physical input devices.
 * Keystrokes an app injects in software (`InputManager.injectInputEvent`, e.g. a vendor service
 * that types card data) skip it and cannot be swallowed by any rule.
 *
 * Bundles send the rules as JSON over AIDL, each tagged with its kind in `"type"`.
 */
@Serializable
sealed interface KeyboardFilterRule {

    /** Whether a key event from the input device with [vendorId]:[productId] is swallowed. */
    fun matches(vendorId: Int, productId: Int): Boolean

    /**
     * A USB HID keyboard (keyboard wedge) typing on its own, identified by its vendor and product id.
     * Matches every key event of that device.
     */
    @Serializable
    @SerialName("usb")
    data class UsbDevice(val vendorId: Int, val productId: Int) : KeyboardFilterRule {
        override fun matches(vendorId: Int, productId: Int) =
            vendorId == this.vendorId && productId == this.productId

        override fun toString() = "USB %04X:%04X".format(vendorId, productId)
    }
}
