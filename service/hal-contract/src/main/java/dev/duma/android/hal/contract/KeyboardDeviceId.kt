package dev.duma.android.hal.contract

import kotlinx.serialization.Serializable

/**
 * USB vendor/product id of an input device that emulates a keyboard (HID keyboard wedge), e.g. a
 * scanner or a magnetic stripe reader. A plugin lists these in [HalPlugin.getKeyboardFilterDevices]
 * so that HAL's keyboard filter swallows their key events while the plugin delivers the data
 * through its own API.
 */
@Serializable
data class KeyboardDeviceId(
    val vendorId: Int,
    val productId: Int,
) {
    override fun toString(): String = "%04X:%04X".format(vendorId, productId)
}
