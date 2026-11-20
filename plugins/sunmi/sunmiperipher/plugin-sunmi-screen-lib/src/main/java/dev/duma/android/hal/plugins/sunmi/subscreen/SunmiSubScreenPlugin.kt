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

    /**
     * Mirrors `com.sunmi.usbscreen.ISetCall.onCallBack(sn, type, key, value, str)`.
     *
     * Semantics recovered from SunmiUsbScreen v2.8.13 (ApiUtil / UsbParameterHelper):
     * the service forwards every parameter report the screen MCU sends back over
     * USB — both acknowledgements of `setParameter` calls and changes the service
     * makes on its own (e.g. it forces touch off before turning the screen off).
     *
     * - `type` 1 = OPTION (a screen parameter), 2 = DC_FLAG (data-channel flag report);
     *   emitted as the string enum `option` / `dcFlag`.
     * - `key`  for OPTION: parameter id, see [OPTION_KEYS]; for DC_FLAG: flag index.
     * - `value` the value the screen reports for that parameter (e.g. 0/1 for switches,
     *   0..100 for brightness).
     * - `str` optional string payload (e.g. nickname); usually empty.
     */
    private val setCall = object : ISetCall.Stub() {
        override fun onCallBack(sn: String?, type: Int, key: Int, value: Int, str: String?) {
            callback?.onEvent("sunmi.screen.screensChanged", JSONObject().apply {
                put("sn", sn ?: "")
                put("type", TYPE_NAMES[type] ?: "unknown")
                put("key", key)
                put("parameter", if (type == TYPE_OPTION) OPTION_KEYS[key] ?: "unknown" else "dcFlag")
                put("value", value)
                put("str", str ?: "")
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
                "Fired when a customer screen reports a parameter value (ack of a set call or a " +
                    "service-initiated change). type: option | dcFlag. key (option): 0=brightness, " +
                    "1=volume, 2=screenSwitch, 3=cpuTest, 4=ddrTest, 5=memTest, 7=touchSwitch, 16=tpTest, " +
                    "18=nickname, 19=nicknameTransparency, 20=nicknameShow, 21=colorTemperature, 257=workMode. " +
                    "value: reported value (0/1 for switches, 0-100 brightness).",
                "sunmi.screen",
                exampleEvent = """{"sn": "SCR-001", "type": "option", "key": 7, "parameter": "touchSwitch", "value": 0, "str": ""}"""
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

    companion object {
        private const val TYPE_OPTION = 1
        private const val TYPE_DC_FLAG = 2

        private val TYPE_NAMES = mapOf(TYPE_OPTION to "option", TYPE_DC_FLAG to "dcFlag")

        /** Parameter ids shared by `setParameter(sn, 1, key, …)` and the callback (l2.a$a$a in the service). */
        private val OPTION_KEYS = mapOf(
            0 to "brightness",
            1 to "volume",
            2 to "screenSwitch",
            3 to "cpuTest",
            4 to "ddrTest",
            5 to "memTest",
            6 to "nfcSwitch",
            7 to "touchSwitch",
            14 to "powerOff",
            16 to "tpTest",
            18 to "nickname",
            19 to "nicknameTransparency",
            20 to "nicknameShow",
            21 to "colorTemperature",
            256 to "screenLog",
            257 to "workMode",
            258 to "tpLog",
            259 to "reboot",
        )
    }
}
