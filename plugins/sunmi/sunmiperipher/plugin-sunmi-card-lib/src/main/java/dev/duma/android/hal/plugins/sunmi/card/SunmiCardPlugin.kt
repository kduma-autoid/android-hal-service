package dev.duma.android.hal.plugins.sunmi.card

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.sunmi.card.IDataListener
import com.sunmi.peripheralsdk.CardManager
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
    private var pluginContext: PluginContext? = null
    private val mutex = Mutex()
    private var receiverRegistered = false

    companion object {
        // The FLEX magnetic card reader is a USB device (WCH bridge).
        private const val CARD_READER_VID = 0x1A86 // 6790  – WCH
        private const val CARD_READER_PID = 0x7523 // 29987 – MSR
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (dev.vendorId != CARD_READER_VID || dev.productId != CARD_READER_PID) return
            val connected = when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> true
                UsbManager.ACTION_USB_DEVICE_DETACHED -> false
                else -> return
            }
            // Advertise / retract the capability live as the reader is plugged / unplugged.
            pluginContext?.setPluginAvailable(connected)
        }
    }

    private val dataListener = object : IDataListener.Stub() {
        override fun onResult(data: String?) {
            if (data == null) return
            val parsed = parseTrackData(data)
            callback?.onEvent("sunmi.card.swipe", parsed)
        }
    }

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        val intent = Intent("com.sunmi.mscard.service").setPackage("com.sunmi.peripheralmanager")
        return ctx.packageManager.resolveService(intent, 0) != null
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.card")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "Sunmi: Magnetic Card Reader (FLEX 3)",
        version = version,
        experimental = true,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.card.getInfo",
                "Get card reader info (name, version, serial number, connection status).",
                "sunmi.card",
                experimental = true,
                exampleParameters = "{}",
                exampleOutput = """{"name": "MSR Reader", "version": "1.0", "sn": "MSR-001", "connected": true}"""
            )
        ),
        events = listOf(
            EventDescriptor(
                "sunmi.card.swipe",
                "Fired when a magnetic stripe card is swiped. Returns raw data and parsed tracks (track1/2/3 are null if not present).",
                "sunmi.card",
                exampleEvent = """{"raw": "%B4111111111111111^DOE/JOHN^2512101123400001?;4111111111111111=25121011234000010001?", "track1": "%B4111111111111111^DOE/JOHN^2512101123400001?", "track2": ";4111111111111111=25121011234000010001?", "track3": null}"""
            )
        )
    )

    override fun initialize(pluginContext: PluginContext) {
        this.pluginContext = pluginContext
        this.context?.let { ctx ->
            CardManager.init(ctx) { success ->
                if (success) CardManager.registerDataListener(dataListener)
            }
            // Watch USB attach/detach and toggle capability availability accordingly.
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(ctx, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            // Reflect the current connection state immediately.
            pluginContext.setPluginAvailable(isCardReaderConnected())
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

    /**
     * The card reader is a USB device. It can be hot-plugged, so this reflects the live
     * connection state rather than a one-time capability flag.
     */
    private fun isCardReaderConnected(): Boolean {
        val ctx = context ?: return false
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usb.deviceList.values.any { it.vendorId == CARD_READER_VID && it.productId == CARD_READER_PID }
    }

    override suspend fun execute(method: String, params: String): CommandResult = mutex.withLock {
        return@withLock try {
            when (method) {
                "sunmi.card.getInfo" -> {
                    CommandResult.Success(JSONObject().apply {
                        put("name", CardManager.getName() ?: "")
                        put("version", CardManager.getVersion() ?: "")
                        put("sn", CardManager.getSn() ?: "")
                        put("connected", CardManager.isConnected())
                    }.toString())
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

}
