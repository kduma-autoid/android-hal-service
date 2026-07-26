package dev.duma.android.hal.plugins.sunmi.tms.led.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.contract.CommandResult
import org.json.JSONObject

/**
 * Handler for the CPad built-in RGB LED indicator.
 *
 * Wraps the [com.sunmi.tmsmaster.aidl.devicemanager.IDeviceManager] RGB LED methods
 * (`isSupportRgbLed`, `openRgbLed`, `closeRgbLed`) exposed by the Sunmi Customer API SDK
 * (>= 1.3.48). Currently compatible with CPad running Android 14.
 */
internal class RgbLedHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): CommandResult {
        // Operation is the last dotted segment, so both the native "sunmi.tms.led.*" methods and the
        // unified "light.*" interface methods resolve to the same handler branch.
        val op = method.substringAfterLast('.')
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "on" -> {
                val rgb = parseColor(json.opt("color"))
                    ?: return CommandResult.badRequest("Invalid or missing 'color' (expected 1-7 or a color name)")
                val timeoutMs = json.optLong("timeoutMs", 0L)
                // lightMode 0 = steady on; onMs/offMs ignored in steady mode
                mapResult(api.deviceManager.openRgbLed(rgb, 0, 0, 0, timeoutMs))
            }
            "flash" -> {
                val rgb = parseColor(json.opt("color"))
                    ?: return CommandResult.badRequest("Invalid or missing 'color' (expected 1-7 or a color name)")
                val onMs = json.optInt("onMs", 0)
                val offMs = json.optInt("offMs", 0)
                val timeoutMs = json.optLong("timeoutMs", 0L)
                // lightMode 1 = blink
                mapResult(api.deviceManager.openRgbLed(rgb, onMs, offMs, 1, timeoutMs))
            }
            "off" -> mapResult(api.deviceManager.closeRgbLed())
            else -> CommandResult.unsupportedMethod(method)
        }
    }

    /**
     * Resolves a preset color index (1-7) from either an integer or a color name.
     * Order per Sunmi CPad docs: 1=Red, 2=Green, 3=Blue, 4=Yellow, 5=Cyan, 6=Magenta, 7=White.
     */
    private fun parseColor(value: Any?): Int? = when (value) {
        is Int -> value.takeIf { it in 1..7 }
        is Number -> value.toInt().takeIf { it in 1..7 }
        is String -> value.toIntOrNull()?.takeIf { it in 1..7 } ?: when (value.lowercase()) {
            "red"     -> 1
            "green"   -> 2
            "blue"    -> 3
            "yellow"  -> 4
            "cyan"    -> 5
            "magenta" -> 6
            "white"   -> 7
            else      -> null
        }
        else -> null
    }

    /**
     * Maps the SDK return code to a [CommandResult].
     *  0   -> success
     * -1   -> invalid parameters / call failed
     * -40  -> interface not supported on current device or ROM
     * -41  -> system service not found
     */
    private fun mapResult(code: Int): CommandResult = when (code) {
        0 -> CommandResult.Success()
        -1 -> CommandResult.badRequest("LED call failed (invalid parameters)")
        -40 -> CommandResult.unavailable("RGB LED interface not supported on this device or ROM")
        -41 -> CommandResult.unavailable("System service not found; please contact the device supplier")
        else -> CommandResult.internalError("Unexpected LED error code: $code")
    }
}
