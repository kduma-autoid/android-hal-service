package dev.duma.android.hal.plugins.sunmi.card

import android.content.Context
import com.sunmi.card.IDataListener
import com.sunmi.peripheralsdk.CardManager
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
 * HAL plugin wrapping the Sunmi CardManager SDK for magnetic stripe card reading.
 * Passive reader — listens continuously and emits swipe events with parsed track data.
 *
 * @param context Android Context needed to bind CardManager service.
 */
class SunmiCardPlugin(
    private val context: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.card"
    override val version = 1

    private var callback: HalPluginEventCallback? = null
    private val mutex = Mutex()

    private val dataListener = object : IDataListener.Stub() {
        override fun onResult(data: String?) {
            if (data == null) return
            val parsed = parseTrackData(data)
            callback?.onEvent("sunmi.card.swipe", parsed)
        }
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.card")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.card.getInfo",
                "Get card reader info (name, version, serial number, connection status).",
                "sunmi.card"
            )
        ),
        events = listOf(
            EventDescriptor(
                "sunmi.card.swipe",
                "Fired when a magnetic stripe card is swiped. " +
                "Payload: {\"raw\":\"...\",\"track1\":\"...\",\"track2\":\"...\",\"track3\":\"...\"}",
                "sunmi.card"
            )
        )
    )

    override fun initialize(context: PluginContext) {
        this.context?.let { ctx ->
            CardManager.init(ctx) { success ->
                if (success) CardManager.registerDataListener(dataListener)
            }
        }
    }

    override suspend fun execute(method: String, params: String): String = mutex.withLock {
        return@withLock try {
            when (method) {
                "sunmi.card.getInfo" -> {
                    JSONObject().apply {
                        put("name", CardManager.getName() ?: "")
                        put("version", CardManager.getVersion() ?: "")
                        put("sn", CardManager.getSn() ?: "")
                        put("connected", CardManager.isConnected())
                    }.toString()
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

    /**
     * Parse raw MSR data into individual tracks.
     * Track 1 starts with '%' and ends with '?'.
     * Track 2 starts with ';' and ends with '?'.
     * Track 3 is the remaining ';'...'?' after Track 2.
     */
    private fun parseTrackData(raw: String): String {
        val json = JSONObject()
        json.put("raw", raw)

        // Track 1: %...?
        val track1Match = Regex("%[^?]*\\?").find(raw)
        json.put("track1", track1Match?.value ?: JSONObject.NULL)

        // After track 1, find track 2 and 3 (both start with ';')
        val afterTrack1 = if (track1Match != null) raw.substring(track1Match.range.last + 1) else raw
        val semicolonTracks = Regex(";[^?]*\\?").findAll(afterTrack1).toList()

        json.put("track2", semicolonTracks.getOrNull(0)?.value ?: JSONObject.NULL)
        json.put("track3", semicolonTracks.getOrNull(1)?.value ?: JSONObject.NULL)

        return json.toString()
    }

    private fun error(code: String, message: String): String =
        """{"error":"$code","message":"$message"}"""
}
