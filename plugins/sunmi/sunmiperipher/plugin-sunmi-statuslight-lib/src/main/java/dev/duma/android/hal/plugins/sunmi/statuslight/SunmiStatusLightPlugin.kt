package dev.duma.android.hal.plugins.sunmi.statuslight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.sunmi.peripheralsdk.Color
import com.sunmi.peripheralsdk.StatusLightManager
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi StatusLightManager SDK.
 * Controls the RGB status LED on SUNMI FLEX 3.
 *
 * Exposes the unified light control surface (`on` / `off` / `flash` / `multiFlash`) shared with the
 * CPad `sunmi.tms.led` plugin. Availability is dynamic: the plugin advertises its capability only
 * while the USB status-light dongle is connected (via [PluginContext.setPluginAvailable]).
 *
 * @param context Android Context needed to bind StatusLightManager service.
 *                Pass null only when constructing without a device (e.g. reflection-based
 *                registration via tryRegisterPlugin); the plugin will be non-functional
 *                until a Context is available.
 */
class SunmiStatusLightPlugin(
    private val context: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.statuslight"
    override val version = 1

    private var callback: HalPluginEventCallback? = null
    private var pluginContext: PluginContext? = null
    private val mutex = Mutex()
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (dev.vendorId != LIGHT_VID || dev.productId != LIGHT_PID) return
            val connected = when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> true
                UsbManager.ACTION_USB_DEVICE_DETACHED -> false
                else -> return
            }
            // Advertise / retract the capability live as the dongle is plugged / unplugged.
            pluginContext?.setPluginAvailable(connected)
        }
    }

    companion object {
        // The FLEX status light is a USB dongle (WCH CH9101UH serial bridge).
        private const val LIGHT_VID = 0x1A86 // 6790  – WCH
        private const val LIGHT_PID = 0x55D8 // 21976 – SD04 / CH9101UH
    }

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        val intent = Intent().setComponent(android.content.ComponentName(
            "com.sunmi.peripheralmanager",
            "com.sunmi.statuslightmanager.StatusLightService"
        ))
        return ctx.packageManager.resolveService(intent, 0) != null
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.statuslight")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "Sunmi: Status Light (FLEX 3)",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.statuslight.on",
                "Turns the status LED on with a steady color. Supported colors: red, green, blue, yellow, magenta, cyan, white.",
                "sunmi.statuslight",
                exampleParameters = """{"color": "red"}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.statuslight.off",
                "Turns off the status LED.",
                "sunmi.statuslight",
                exampleParameters = "{}",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.statuslight.flash",
                "Blinks the status LED in a single color. NOTE: Hardware support limited — effect stops automatically when app exits.",
                "sunmi.statuslight",
                exampleParameters = """{"color": "red", "onMs": 500, "offMs": 500}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.statuslight.multiFlash",
                "Cycles the status LED through multiple colors. Accepts either {steps:[{color,onMs,offMs}]} or {colors:[...],onMs,offMs}. NOTE: Hardware support limited — effect stops automatically when app exits.",
                "sunmi.statuslight",
                exampleParameters = """{"colors": ["red","green","blue"], "onMs": 500, "offMs": 300}""",
                exampleOutput = """{}"""
            )
        ),
        events = emptyList()
    )

    override fun initialize(pluginContext: PluginContext) {
        this.pluginContext = pluginContext
        this.context?.let { ctx ->
            StatusLightManager.init(ctx) { success ->
                if (success) StatusLightManager.openDevice()
            }
            // Watch USB attach/detach and toggle capability availability accordingly.
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(ctx, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            // Reflect the current connection state immediately.
            pluginContext.setPluginAvailable(isStatusLightConnected())
        }
    }

    override fun dispose() {
        if (receiverRegistered) {
            try {
                context?.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {
                // Receiver already unregistered
            }
            receiverRegistered = false
        }
    }

    override suspend fun execute(method: String, params: String): CommandResult = mutex.withLock {
        return@withLock try {
            when (method) {
                "sunmi.statuslight.on" -> {
                    val color = parseColor(JSONObject(params).getString("color"))
                        ?: return@withLock CommandResult.badRequest("Unknown color value")
                    StatusLightManager.setColor(color)
                    CommandResult.Success()
                }
                "sunmi.statuslight.off" -> {
                    StatusLightManager.turnOff()
                    CommandResult.Success()
                }
                "sunmi.statuslight.flash" -> {
                    val json = JSONObject(params)
                    val color = parseColor(json.getString("color"))
                        ?: return@withLock CommandResult.badRequest("Unknown color value")
                    val onMs = json.getInt("onMs")
                    val offMs = json.getInt("offMs")
                    StatusLightManager.setFlashing(color, onMs, offMs)
                    CommandResult.Success()
                }
                "sunmi.statuslight.multiFlash" -> {
                    val json = JSONObject(params)
                    val colors: Array<Color>
                    val onMsArr: IntArray
                    val offMsArr: IntArray
                    if (json.has("steps")) {
                        val stepsArr = json.getJSONArray("steps")
                        colors = Array(stepsArr.length()) {
                            val step = stepsArr.getJSONObject(it)
                            parseColor(step.getString("color"))
                                ?: return@withLock CommandResult.badRequest("Unknown color: ${step.getString("color")}")
                        }
                        onMsArr = IntArray(stepsArr.length()) { stepsArr.getJSONObject(it).getInt("onMs") }
                        offMsArr = IntArray(stepsArr.length()) { stepsArr.getJSONObject(it).getInt("offMs") }
                    } else {
                        val colorsArr = json.getJSONArray("colors")
                        val onMs = json.getInt("onMs")
                        val offMs = json.getInt("offMs")
                        colors = Array(colorsArr.length()) {
                            parseColor(colorsArr.getString(it))
                                ?: return@withLock CommandResult.badRequest("Unknown color: ${colorsArr.getString(it)}")
                        }
                        onMsArr = IntArray(colorsArr.length()) { onMs }
                        offMsArr = IntArray(colorsArr.length()) { offMs }
                    }
                    StatusLightManager.setMultiFlashing(colors, onMsArr, offMsArr)
                    CommandResult.Success()
                }
                else -> CommandResult.unsupportedMethod(method)
            }
        } catch (e: IllegalStateException) {
            CommandResult.unavailable(e.message ?: "Device not initialized")
        } catch (e: IllegalArgumentException) {
            CommandResult.badRequest(e.message ?: "Invalid parameters")
        } catch (e: Exception) {
            CommandResult.internalError(e.message ?: "Unknown SDK error")
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }

    /**
     * The status light itself is a USB device (WCH CH9101UH). It can be hot-plugged, so this
     * reflects the live connection state rather than a one-time capability flag.
     */
    private fun isStatusLightConnected(): Boolean {
        val ctx = context ?: return false
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usb.deviceList.values.any { it.vendorId == LIGHT_VID && it.productId == LIGHT_PID }
    }

    private fun parseColor(name: String): Color? = when (name.lowercase()) {
        "red"     -> Color.Red
        "green"   -> Color.Green
        "blue"    -> Color.Blue
        "yellow"  -> Color.Yellow
        "magenta" -> Color.Magenta
        "cyan"    -> Color.Cyan
        "white"   -> Color.White
        else      -> null
    }

}
