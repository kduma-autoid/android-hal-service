package dev.duma.android.hal.plugins.sunmi.statuslight

import android.content.Context
import android.content.Intent
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
    private val mutex = Mutex()

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        val intent = Intent().setComponent(android.content.ComponentName(
            "com.sunmi.peripheralmanager",
            "com.sunmi.statuslightmanager.StatusLightService"
        ))
        return ctx.packageManager.resolveService(intent, 0) != null
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.statuslight")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi: Status Light (FLEX 3)",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.statuslight.setColor",
                "Set LED color. Supported colors: red, green, blue, yellow, magenta, cyan, white.",
                "sunmi.statuslight",
                exampleParameters = """{"color": "red"}""",
                exampleOutput = """{"status": "ok"}"""
            ),
            MethodDescriptor(
                "sunmi.statuslight.turnOff",
                "Turn off the status LED.",
                "sunmi.statuslight",
                exampleParameters = "{}",
                exampleOutput = """{"status": "ok"}"""
            ),
            MethodDescriptor(
                "sunmi.statuslight.setFlashing",
                "Set LED to flash in a single color. NOTE: Hardware support limited — effect stops automatically when app exits.",
                "sunmi.statuslight",
                exampleParameters = """{"color": "red", "onMs": 500, "offMs": 500}""",
                exampleOutput = """{"status": "ok"}"""
            ),
            MethodDescriptor(
                "sunmi.statuslight.setMultiFlashing",
                "Set LED to cycle through multiple colors. NOTE: Hardware support limited — effect stops automatically when app exits.",
                "sunmi.statuslight",
                exampleParameters = """{"steps": [{"color": "red", "onMs": 500, "offMs": 500}, {"color": "green", "onMs": 300, "offMs": 300}]}""",
                exampleOutput = """{"status": "ok"}"""
            )
        ),
        events = emptyList()
    )

    override fun initialize(pluginContext: PluginContext) {
        this.context?.let { ctx ->
            StatusLightManager.init(ctx) { success ->
                if (success) StatusLightManager.openDevice()
            }
        }
    }

    override suspend fun execute(method: String, params: String): CommandResult = mutex.withLock {
        return@withLock try {
            when (method) {
                "sunmi.statuslight.setColor" -> {
                    val color = parseColor(JSONObject(params).getString("color"))
                        ?: return@withLock CommandResult.badRequest("Unknown color value")
                    StatusLightManager.setColor(color)
                    CommandResult.Success()
                }
                "sunmi.statuslight.turnOff" -> {
                    StatusLightManager.turnOff()
                    CommandResult.Success()
                }
                "sunmi.statuslight.setFlashing" -> {
                    val json = JSONObject(params)
                    val color = parseColor(json.getString("color"))
                        ?: return@withLock CommandResult.badRequest("Unknown color value")
                    val onMs = json.getInt("onMs")
                    val offMs = json.getInt("offMs")
                    StatusLightManager.setFlashing(color, onMs, offMs)
                    CommandResult.Success()
                }
                "sunmi.statuslight.setMultiFlashing" -> {
                    val json = JSONObject(params)
                    val stepsArr = json.getJSONArray("steps")
                    val colors = Array(stepsArr.length()) {
                        val step = stepsArr.getJSONObject(it)
                        parseColor(step.getString("color"))
                            ?: return@withLock CommandResult.badRequest("Unknown color: ${step.getString("color")}")
                    }
                    val onMs = IntArray(stepsArr.length()) { stepsArr.getJSONObject(it).getInt("onMs") }
                    val offMs = IntArray(stepsArr.length()) { stepsArr.getJSONObject(it).getInt("offMs") }
                    StatusLightManager.setMultiFlashing(colors, onMs, offMs)
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
