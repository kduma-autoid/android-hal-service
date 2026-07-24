package dev.duma.android.hal.plugins.sunmi.subscreen

import android.content.Context
import android.content.Intent
import com.sunmi.peripheralsdk.SubScreenManager
import com.sunmi.usbscreen.ISetCall
import dev.duma.android.hal.contract.BaseHalPlugin
import dev.duma.android.hal.contract.CommandResult
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
) : BaseHalPlugin() {

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

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        val intent = Intent("com.sunmi.usbscreen.IUsbScreenInterface").setPackage("com.sunmi.usbscreen")
        return ctx.packageManager.resolveService(intent, 0) != null
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.screen")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "Sunmi: Customer Screen",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.screen.getDeviceInfo",
                "Get connected screen info.",
                "sunmi.screen",
                exampleParameters = "{}",
                exampleOutput = """{"info": {"sn": "SCR-001", "resolution": "1920x1080"}}"""
            ),
            MethodDescriptor(
                "sunmi.screen.setScreenSwitch",
                "Enable or disable a connected screen.",
                "sunmi.screen",
                exampleParameters = """{"sn": "SCR-001", "enabled": true}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.screen.setTouchSwitch",
                "Enable or disable the touch panel on a connected screen.",
                "sunmi.screen",
                exampleParameters = """{"sn": "SCR-001", "enabled": true}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.screen.setBrightness",
                "Set screen brightness level.",
                "sunmi.screen",
                exampleParameters = """{"sn": "SCR-001", "brightness": 80}""",
                exampleOutput = """{}"""
            )
        ),
        events = listOf(
            EventDescriptor(
                "sunmi.screen.screensChanged",
                "Fired when screen state changes.",
                "sunmi.screen",
                exampleEvent = """{"sn": "SCR-001", "type": 1, "value": 0, "extra": ""}"""
            )
        )
    )

    override fun initialize(pluginContext: PluginContext) {
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

    override suspend fun onExecute(method: String, params: String): CommandResult = mutex.withLock {
        return@withLock try {
            when (method) {
                "sunmi.screen.getDeviceInfo" -> {
                    val info = SubScreenManager.getDeviceInfo()
                    val json = JSONObject()
                    info?.forEach { (k, v) -> json.put(k, v) }
                    CommandResult.Success(JSONObject().apply { put("info", json) }.toString())
                }
                "sunmi.screen.setScreenSwitch" -> {
                    val json = JSONObject(params)
                    SubScreenManager.setScreenSwitch(json.getString("sn"), json.getBoolean("enabled"))
                    CommandResult.Success()
                }
                "sunmi.screen.setTouchSwitch" -> {
                    val json = JSONObject(params)
                    SubScreenManager.setScreenTpSwitch(json.getString("sn"), json.getBoolean("enabled"))
                    CommandResult.Success()
                }
                "sunmi.screen.setBrightness" -> {
                    val json = JSONObject(params)
                    SubScreenManager.setScreenBrightness(json.getString("sn"), json.getInt("brightness"))
                    CommandResult.Success()
                }
                else -> CommandResult.unsupportedMethod(method)
            }
        } catch (e: Exception) {
            CommandResult.internalError(e.message ?: "Unknown SDK error")
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }

}
