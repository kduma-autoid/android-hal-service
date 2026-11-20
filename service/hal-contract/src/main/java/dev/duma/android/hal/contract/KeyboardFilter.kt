package dev.duma.android.hal.contract

import android.content.Context
import android.provider.Settings
import android.view.InputDevice
import org.json.JSONArray
import org.json.JSONObject

/**
 * Facts about HAL's keyboard filter that plugins (in-process or in a bundle) and the HAL UI share.
 *
 * The filter is a single Android accessibility service living in the HAL app. Plugins never ship
 * their own: they declare rules with [HalPlugin.getKeyboardFilterRules] and HAL filters
 * them all, so the user enables one service no matter how many keyboard-emulating devices exist.
 */
object KeyboardFilter {

    const val HAL_PACKAGE = "dev.duma.android.hal.service"

    /** The class name is matched exactly; similarly named services do not count. */
    const val SERVICE_CLASS_NAME = "dev.duma.android.hal.service.keyboardfilter.HalKeyboardFilterService"

    /**
     * Whether the user has enabled HAL's keyboard filter in Android settings. `Settings.Secure` is
     * readable by every app, so this works from a bundle as well.
     *
     * It reflects the setting only. It does not prove the service receives events: Android, the
     * OEM or another accessibility filter can still affect delivery, and HAL must be running.
     */
    @JvmStatic
    fun isEnabled(context: Context): Boolean = isEnabledIn(
        accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ),
        enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ),
    )

    /**
     * [isEnabled] over the raw setting values. Settings stores flattened components as
     * `package/class` separated by `:`; the class may be written relative to the package (`.Name`).
     */
    internal fun isEnabledIn(accessibilityEnabled: Int, enabledServices: String?): Boolean {
        if (accessibilityEnabled != 1 || enabledServices.isNullOrEmpty()) return false
        return enabledServices.split(':').any { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0 || slash == entry.length - 1) return@any false
            val pkg = entry.substring(0, slash)
            val cls = entry.substring(slash + 1).let { if (it.startsWith('.')) pkg + it else it }
            pkg == HAL_PACKAGE && cls == SERVICE_CLASS_NAME
        }
    }

    /**
     * Lists the input devices Android currently knows, for working out which one a keyboard-emulating
     * peripheral is. Contains device metadata only, never key events.
     *
     * @param filtered rules marking the devices they match with `"filtered": true` (e.g. the
     *   plugin's own list).
     */
    @JvmStatic
    @JvmOverloads
    fun describeInputDevices(filtered: Collection<KeyboardFilterRule> = emptyList()): JSONArray {
        val result = JSONArray()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            result.put(JSONObject().apply {
                put("id", device.id)
                put("name", device.name)
                put("vendorId", device.vendorId)
                put("productId", device.productId)
                put("usbId", "%04X:%04X".format(device.vendorId, device.productId))
                put("descriptor", device.descriptor)
                put("sources", device.sources)
                put("keyboard", device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD)
                put("virtual", device.isVirtual)
                put("filtered", filtered.any { it.matches(device.vendorId, device.productId) })
            })
        }
        return result
    }
}
