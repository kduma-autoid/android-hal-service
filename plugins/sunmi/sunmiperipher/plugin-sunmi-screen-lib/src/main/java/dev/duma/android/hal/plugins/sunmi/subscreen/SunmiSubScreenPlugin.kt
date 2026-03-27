package dev.duma.android.hal.plugins.sunmi.subscreen

import android.content.Context
import com.sunmi.peripheralsdk.SubScreenManager
import com.sunmi.usbscreen.ISetCall
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi SubScreenManager SDK.
 * Controls external USB displays connected to SUNMI FLEX 3
 * (screen on/off, touch panel, brightness).
 *
 * @param context Android Context needed to bind SubScreenManager service.
 */
class SunmiSubScreenPlugin(
    private val context: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.screen"
    override val version = 1

    private var callback: HalPluginEventCallback? = null
    private val mutex = Mutex()

    private val setCall = object : ISetCall.Stub() {
        override fun onCallBack(sn: String?, type: Int, value: Int, extra1: Int, extra2: String?) {
            callback?.onEvent("sunmi.screen.screensChanged", JSONObject().apply {
                put("sn", sn ?: "")
                put("type", type)
                put("value", value)
                put("extra", extra2 ?: "")
            }.toString())
        }
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.screen")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.screen.getDeviceInfo",
                "Get connected screen info.",
                "sunmi.screen"
            ),
            MethodDescriptor(
                "sunmi.screen.setScreenSwitch",
                "Enable/disable screen. Params: {\"sn\": \"...\", \"enabled\": true}",
                "sunmi.screen"
            ),
            MethodDescriptor(
                "sunmi.screen.setTouchSwitch",
                "Enable/disable touch panel. Params: {\"sn\": \"...\", \"enabled\": true}",
                "sunmi.screen"
            ),
            MethodDescriptor(
                "sunmi.screen.setBrightness",
                "Set screen brightness. Params: {\"sn\": \"...\", \"brightness\": 80}",
                "sunmi.screen"
            )
        ),
        events = listOf(
            EventDescriptor(
                "sunmi.screen.screensChanged",
                "Fired when screen state changes. Payload: {\"sn\":\"...\",\"type\":0,\"value\":0,\"extra\":\"...\"}",
                "sunmi.screen"
            )
        )
    )

    override fun initialize(context: PluginContext) {
        this.context?.let { ctx ->
            SubScreenManager.init(ctx) { success ->
                if (success) {
                    try {
                        SubScreenManager.checkServiceAvailable()?.setCallback(setCall)
                    } catch (_: Exception) { }
                }
            }
        }
    }

    override suspend fun execute(method: String, params: String): String = mutex.withLock {
        return@withLock try {
            when (method) {
                "sunmi.screen.getDeviceInfo" -> {
                    val info = SubScreenManager.getDeviceInfo()
                    val json = JSONObject()
                    info?.forEach { (k, v) -> json.put(k, v) }
                    JSONObject().apply { put("info", json) }.toString()
                }
                "sunmi.screen.setScreenSwitch" -> {
                    val json = JSONObject(params)
                    SubScreenManager.setScreenSwitch(json.getString("sn"), json.getBoolean("enabled"))
                    success()
                }
                "sunmi.screen.setTouchSwitch" -> {
                    val json = JSONObject(params)
                    SubScreenManager.setScreenTpSwitch(json.getString("sn"), json.getBoolean("enabled"))
                    success()
                }
                "sunmi.screen.setBrightness" -> {
                    val json = JSONObject(params)
                    SubScreenManager.setScreenBrightness(json.getString("sn"), json.getInt("brightness"))
                    success()
                }
                else -> error("unsupported_method", "Method not supported: $method")
            }
        } catch (e: Exception) {
            error("sdk_error", e.message ?: "Unknown SDK error")
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }

    private fun success(): String = """{"status":"ok"}"""
    private fun error(code: String, message: String): String =
        """{"error":"$code","message":"$message"}"""
}
