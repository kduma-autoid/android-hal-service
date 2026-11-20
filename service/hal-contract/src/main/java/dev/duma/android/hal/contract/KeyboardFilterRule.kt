package dev.duma.android.hal.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What HAL's keyboard filter swallows for a plugin: key events of a peripheral that also "types"
 * its data (a scanner or a magnetic stripe reader), while the plugin delivers that data through its
 * own API. A plugin lists its rules in [HalPlugin.getKeyboardFilterRules]; an event matching any
 * registered rule is consumed.
 *
 * Bundles send the rules as JSON over AIDL, each tagged with its kind in `"type"`.
 */
@Serializable
sealed interface KeyboardFilterRule {

    /** Whether a key event coming from [origin] is swallowed by this rule. */
    fun matches(origin: KeyOrigin): Boolean

    /**
     * A USB HID keyboard (keyboard wedge) typing on its own, identified by its vendor and product id.
     * Matches every key event of that device.
     */
    @Serializable
    @SerialName("usb")
    data class UsbDevice(val vendorId: Int, val productId: Int) : KeyboardFilterRule {
        override fun matches(origin: KeyOrigin) = origin.vendorId == vendorId && origin.productId == productId

        override fun toString() = "USB %04X:%04X".format(vendorId, productId)
    }

    /**
     * Keystrokes a vendor service injects in software, e.g. a reader that is not a keyboard at all
     * but whose vendor service types its data. Injected events come from Android's virtual keyboard,
     * so they are told apart by the `KeyEvent.getSource()` value the vendor marks them with. Matches
     * only events with exactly that source, whatever device they come from; other injected input
     * (other apps, `adb shell input`) passes.
     */
    @Serializable
    @SerialName("source")
    data class EventSource(val source: Int) : KeyboardFilterRule {
        override fun matches(origin: KeyOrigin) = origin.source == source

        override fun toString() = "event source 0x%08X".format(source)
    }
}

/** Where a key event comes from: its input device's USB id and the event's source. */
data class KeyOrigin(val vendorId: Int, val productId: Int, val source: Int)
