package dev.duma.android.hal.plugins.msr605x

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.duma.android.hal.contract.BaseHalPlugin
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceBinding
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.stripExperimental
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * HAL plugin for the MSR605X USB magnetic stripe reader/writer (VID 0x0801, PID 0x0003), driven
 * directly over USB HID. Ported from the `msr605x.py` driver.
 *
 * The reader is hot-pluggable: the plugin reports itself available only while the device is
 * attached *and* the app holds USB permission for it. Permission is requested automatically when
 * the reader is detected — there is no API method for it.
 *
 * Card operations (read/write/erase) wait for a swipe for up to `timeoutMs`; `msr605x.cancel`
 * aborts a pending wait. Every successful read is also emitted as the [EVENT_SWIPE] event and the
 * unified `magneticCardReader.onSwipe` event (this plugin provides the `magneticCardReader`
 * interface).
 */
class Msr605xPlugin(
    private val appContext: Context? = null
) : BaseHalPlugin() {

    override val pluginId = "msr605x"
    override val version = 1

    private val mutex = Mutex()
    private var callback: HalPluginEventCallback? = null
    private var pluginContext: PluginContext? = null
    private var receiversRegistered = false

    @Volatile private var transport: Msr605xTransport? = null
    @Volatile private var device: Msr605xDevice? = null
    @Volatile private var pendingSwipe: Deferred<*>? = null
    @Volatile private var permissionPending = false

    private val usbManager: UsbManager?
        get() = appContext?.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) permissionPending = false
            @Suppress("DEPRECATION")
            val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (!isMsr605x(dev)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(dev)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached()
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    pluginContext?.setPluginAvailable(granted)
                }
            }
        }
    }

    // --- Lifecycle ---

    override fun isSupported(): Boolean =
        appContext?.packageManager?.hasSystemFeature(PackageManager.FEATURE_USB_HOST) ?: false

    override fun getCapabilities(): List<String> = listOf(CAPABILITY)

    override fun initialize(pluginContext: PluginContext) {
        this.pluginContext = pluginContext
        val ctx = appContext ?: return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(ctx, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true

        val dev = findDevice()
        if (dev != null) onAttached(dev, emit = false) else pluginContext.setPluginAvailable(false)
    }

    override fun dispose() {
        pendingSwipe?.cancel()
        closeDevice()
        if (receiversRegistered) {
            try {
                appContext?.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {
                // Already unregistered.
            }
            receiversRegistered = false
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        this.callback = callback
    }

    private fun onAttached(dev: UsbDevice, emit: Boolean = true) {
        val granted = usbManager?.hasPermission(dev) == true
        if (!granted) requestPermission(dev)
        pluginContext?.setPluginAvailable(granted)
        if (emit) emitEvent(EVENT_ATTACHED, "{}")
    }

    private fun onDetached() {
        pendingSwipe?.cancel()
        closeDevice()
        permissionPending = false
        pluginContext?.setPluginAvailable(false)
        emitEvent(EVENT_DETACHED, "{}")
    }

    private fun requestPermission(dev: UsbDevice) {
        val ctx = appContext ?: return
        val usb = usbManager ?: return
        if (permissionPending) return
        permissionPending = true
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName)
        // The system fills in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED, so the intent must be mutable.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, intent, flags))
    }

    private fun findDevice(): UsbDevice? = usbManager?.deviceList?.values?.firstOrNull(::isMsr605x)

    private fun isMsr605x(dev: UsbDevice) =
        dev.vendorId == Msr605xProtocol.VENDOR_ID && dev.productId == Msr605xProtocol.PRODUCT_ID

    /** Opens the device lazily, or throws [DeviceUnavailableException]. */
    private fun openDevice(): Msr605xDevice {
        device?.let { return it }
        val usb = usbManager ?: throw DeviceUnavailableException("USB host not available")
        val dev = findDevice() ?: throw DeviceUnavailableException("MSR605X not connected")
        if (!usb.hasPermission(dev)) {
            requestPermission(dev)
            throw DeviceUnavailableException("USB permission not granted")
        }
        val t = UsbHidTransport(usb, dev)
        transport = t
        return Msr605xDevice(t).also { device = it }
    }

    private fun closeDevice() {
        device = null
        transport?.let { runCatching { it.close() } }
        transport = null
    }

    private class DeviceUnavailableException(message: String) : Exception(message)

    // --- Descriptor ---

    override fun getDescriptor() = fullDescriptor().let {
        if (BuildConfig.WITH_EXPERIMENTAL) it else it.stripExperimental()
    }

    private fun method(
        name: String,
        description: String,
        params: String = "{}",
        output: String = "{}",
        experimental: Boolean = false
    ) = MethodDescriptor(
        "$CAPABILITY.$name", description, CAPABILITY,
        experimental = experimental, exampleParameters = params, exampleOutput = output
    )

    private fun fullDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "MSR605X: Magnetic Card Reader/Writer",
        version = version,
        // Not yet verified on hardware — gated until enabled in settings, compiled out of stable builds.
        experimental = true,
        capabilities = getCapabilities(),
        interfaces = listOf(
            InterfaceBinding("magneticCardReader", priority = 100, features = listOf("read", "write"))
        ),
        groups = listOf(
            DescriptorGroup(
                name = "Card",
                methods = listOf(
                    method(
                        "read",
                        "Wait for a card swipe and read the ISO 7811 tracks. Tracks are returned without sentinels; " +
                            "\"\" = empty track, null = read error.",
                        params = """{"timeoutMs":15000}""",
                        output = """{"status":"ok","track1":"B4111111111111111^DOE/JOHN^2512101123400001","track2":"4111111111111111=25121011234000010001","track3":""}"""
                    ),
                    method(
                        "readRaw",
                        "Wait for a card swipe and read raw track data (hex).",
                        params = """{"timeoutMs":15000}""",
                        output = """{"status":"ok","track1":"a2c4...","track2":"","track3":""}"""
                    ),
                    method(
                        "write",
                        "Wait for a card swipe and write ISO 7811 tracks (data without sentinels). Omitted tracks are left untouched.",
                        params = """{"track1":"B4111111111111111^DOE/JOHN^2512101123400001","track2":"4111111111111111=2512101","timeoutMs":15000}""",
                        output = """{"status":"ok"}""",
                        experimental = true
                    ),
                    method(
                        "writeRaw",
                        "Wait for a card swipe and write raw track data (hex). Switches the device to 8 bits per character first.",
                        params = """{"track1":"a2c4e6","timeoutMs":15000}""",
                        output = """{"status":"ok"}""",
                        experimental = true
                    ),
                    method(
                        "erase",
                        "Wait for a card swipe and erase the given tracks.",
                        params = """{"tracks":[1,2,3],"timeoutMs":15000}""",
                        output = """{"status":"ok"}""",
                        experimental = true
                    ),
                    method("cancel", "Abort a pending swipe wait (read/write/erase).", output = """{"cancelled":true}""")
                ),
                events = listOf(
                    EventDescriptor(
                        EVENT_SWIPE,
                        "A card was read (via msr605x.read or magneticCardReader.read).",
                        CAPABILITY,
                        exampleEvent = """{"status":"ok","track1":"B4111111111111111^DOE/JOHN^2512101123400001","track2":"4111111111111111=25121011234000010001","track3":""}"""
                    )
                )
            ),
            DescriptorGroup(
                name = "Settings",
                methods = listOf(
                    method("setCoercivity", "Set write coercivity: \"hi\" (Hi-Co) or \"lo\" (Lo-Co).", """{"mode":"hi"}""", """{"mode":"hi"}"""),
                    method("getCoercivity", "Get the current write coercivity.", output = """{"mode":"hi"}"""),
                    method("setBpi", "Set bits per inch (210 or 75) for the given tracks.", """{"track1":210,"track2":75,"track3":210}""", """{"track1":210,"track2":75,"track3":210}"""),
                    method("setBpc", "Set bits per character (5-8) for all three tracks.", """{"track1":7,"track2":5,"track3":5}""", """{"track1":7,"track2":5,"track3":5}"""),
                    method("setLeadingZeros", "Set leading zeros (0-255) for tracks 1&3 and track 2.", """{"track13":61,"track2":22}""", """{"track13":61,"track2":22}"""),
                    method("getLeadingZeros", "Get the leading-zero settings.", output = """{"track13":61,"track2":22}""")
                )
            ),
            DescriptorGroup(
                name = "Device",
                methods = listOf(
                    method("getInfo", "Connection state, USB permission, model and firmware.", output = """{"connected":true,"permission":true,"model":"3","firmware":"REVH1.21"}"""),
                    method("setLed", "Set the LEDs: off, green, yellow (green+yellow), red or all.", """{"led":"green"}"""),
                    method("commTest", "Run the device communication test.", output = """{"ok":true}"""),
                    method("reset", "Reset the device; hard reset also aborts a pending operation.", """{"hard":false}""")
                ),
                events = listOf(
                    EventDescriptor(EVENT_ATTACHED, "The MSR605X was plugged in.", CAPABILITY, exampleEvent = "{}"),
                    EventDescriptor(EVENT_DETACHED, "The MSR605X was unplugged.", CAPABILITY, exampleEvent = "{}")
                )
            )
        )
    )

    // --- Execute ---

    override suspend fun onExecute(method: String, params: String): CommandResult {
        val json = try {
            if (params.isBlank()) JSONObject() else JSONObject(params)
        } catch (e: Exception) {
            return CommandResult.badRequest("Invalid JSON params")
        }

        // Cancel must not queue behind the operation it is meant to abort.
        if (method == "$CAPABILITY.cancel" || method == "magneticCardReader.cancel") {
            val swipe = pendingSwipe
            swipe?.cancel(CancellationException("Cancelled by client"))
            return CommandResult.Success(JSONObject().put("cancelled", swipe != null).toString())
        }

        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    dispatch(method, json)
                } catch (e: DeviceUnavailableException) {
                    CommandResult.unavailable(e.message ?: "MSR605X unavailable")
                } catch (e: Msr605xTimeoutException) {
                    CommandResult.timeout(e.message ?: "Timed out waiting for card")
                } catch (e: IllegalArgumentException) {
                    CommandResult.badRequest(e.message ?: "Invalid parameters")
                } catch (e: JSONException) {
                    CommandResult.badRequest(e.message ?: "Invalid parameters")
                } catch (e: CancellationException) {
                    // Our own cancel() aborted the swipe; a cancellation of the caller propagates.
                    if (currentCoroutineContext().isActive) {
                        CommandResult.Failure("cancelled", "Operation cancelled")
                    } else {
                        throw e
                    }
                } catch (e: IOException) {
                    closeDevice() // reopen on next call
                    CommandResult.internalError(e.message ?: "USB I/O error")
                } catch (e: Exception) {
                    CommandResult.internalError(e.message ?: "MSR605X error")
                }
            }
        }
    }

    private suspend fun dispatch(method: String, json: JSONObject): CommandResult {
        return when (method) {
            "$CAPABILITY.getInfo" -> getInfo(unified = false)
            "magneticCardReader.getInfo" -> getInfo(unified = true)
            "$CAPABILITY.read" -> read(json, unified = false)
            "magneticCardReader.read" -> read(json, unified = true)
            "$CAPABILITY.readRaw" -> readRaw(json)
            "$CAPABILITY.write", "magneticCardReader.write" -> write(json)
            "$CAPABILITY.writeRaw" -> writeRaw(json)
            "$CAPABILITY.erase" -> erase(json)
            "$CAPABILITY.setCoercivity" -> setCoercivity(json)
            "$CAPABILITY.getCoercivity" ->
                ok(JSONObject().put("mode", openDevice().getCoercivity() ?: JSONObject.NULL))
            "$CAPABILITY.setBpi" -> setBpi(json)
            "$CAPABILITY.setBpc" -> setBpc(json)
            "$CAPABILITY.setLeadingZeros" -> setLeadingZeros(json)
            "$CAPABILITY.getLeadingZeros" -> {
                val (t13, t2) = openDevice().getLeadingZeros() ?: return deviceRefused("getLeadingZeros")
                ok(JSONObject().put("track13", t13).put("track2", t2))
            }
            "$CAPABILITY.setLed" -> setLed(json)
            "$CAPABILITY.commTest" -> ok(JSONObject().put("ok", openDevice().commTest()))
            "$CAPABILITY.reset" -> {
                val dev = openDevice()
                if (json.optBoolean("hard", false)) dev.hardReset() else dev.reset()
                CommandResult.Success()
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }

    private suspend fun getInfo(unified: Boolean): CommandResult {
        val dev = findDevice()
        val permission = dev != null && usbManager?.hasPermission(dev) == true
        val info = JSONObject()
        if (unified) info.put("name", "MSR605X")
        info.put("connected", dev != null)
        if (!unified) info.put("permission", permission)
        if (permission) {
            val d = openDevice()
            info.put("model", d.getModel() ?: JSONObject.NULL)
            info.put("firmware", d.getFirmware() ?: JSONObject.NULL)
        }
        return ok(info)
    }

    private suspend fun read(json: JSONObject, unified: Boolean): CommandResult {
        val dev = openDevice()
        val result = swipe { dev.read(timeoutOf(json)) }
        if (result.status == null) return CommandResult.internalError("Unexpected response from MSR605X")
        val tracks = JSONObject()
            .put("track1", result.track1 ?: JSONObject.NULL)
            .put("track2", result.track2 ?: JSONObject.NULL)
            .put("track3", result.track3 ?: JSONObject.NULL)
        val native = JSONObject(tracks.toString()).put("status", Msr605xProtocol.describeStatus(result.status))
        emitEvent(EVENT_SWIPE, native.toString())
        emitEvent(EVENT_ON_SWIPE, tracks.toString())
        return ok(if (unified) tracks else native)
    }

    private suspend fun readRaw(json: JSONObject): CommandResult {
        val dev = openDevice()
        val result = swipe { dev.readRaw(timeoutOf(json)) }
        if (result.status == null) return CommandResult.internalError("Unexpected response from MSR605X")
        return ok(
            JSONObject()
                .put("status", Msr605xProtocol.describeStatus(result.status))
                .put("track1", result.track1?.toHex() ?: JSONObject.NULL)
                .put("track2", result.track2?.toHex() ?: JSONObject.NULL)
                .put("track3", result.track3?.toHex() ?: JSONObject.NULL)
        )
    }

    private suspend fun write(json: JSONObject): CommandResult {
        val tracks = (1..3).map { i ->
            json.optStringOrNull("track$i")?.also {
                require(Msr605xProtocol.isValidIso(i, it)) { "track$i contains invalid characters or is too long" }
            }
        }
        require(tracks.any { !it.isNullOrEmpty() }) { "Nothing to write" }
        val dev = openDevice()
        val status = swipe { dev.write(tracks[0], tracks[1], tracks[2], timeoutOf(json)) }
        return statusResult(status, "write_failed")
    }

    private suspend fun writeRaw(json: JSONObject): CommandResult {
        val tracks = (1..3).map { i -> json.optStringOrNull("track$i")?.let { parseHex(it, "track$i") } }
        require(tracks.any { it != null && it.isNotEmpty() }) { "Nothing to write" }
        val dev = openDevice()
        dev.setBpc(8, 8, 8) ?: return deviceRefused("setBpc")
        val status = swipe { dev.writeRaw(tracks[0], tracks[1], tracks[2], timeoutOf(json)) }
        return statusResult(status, "write_failed")
    }

    private suspend fun erase(json: JSONObject): CommandResult {
        val arr = json.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until arr.length()).map { arr.getInt(it) }.toSet()
        require(tracks.isNotEmpty() && tracks.all { it in 1..3 }) { "tracks must be a non-empty subset of [1,2,3]" }
        val dev = openDevice()
        val status = swipe { dev.erase(1 in tracks, 2 in tracks, 3 in tracks, timeoutOf(json)) }
        return statusResult(status, "erase_failed")
    }

    private suspend fun setCoercivity(json: JSONObject): CommandResult {
        val dev = openDevice()
        val applied = when (val mode = json.optString("mode")) {
            "hi" -> dev.setHiCo()
            "lo" -> dev.setLoCo()
            else -> throw IllegalArgumentException("mode must be \"hi\" or \"lo\", got \"$mode\"")
        }
        return if (applied) ok(JSONObject().put("mode", json.getString("mode"))) else deviceRefused("setCoercivity")
    }

    private suspend fun setBpi(json: JSONObject): CommandResult {
        val requested = (1..3).mapNotNull { i -> if (json.has("track$i")) i to json.getInt("track$i") else null }
        require(requested.isNotEmpty()) { "Give at least one of track1, track2, track3" }
        requested.forEach { (_, bpi) -> require(bpi == 210 || bpi == 75) { "BPI must be 210 or 75" } }
        val dev = openDevice()
        val out = JSONObject()
        for ((track, bpi) in requested) {
            if (!dev.setBpi(track, bpi)) return deviceRefused("setBpi track$track")
            out.put("track$track", bpi)
        }
        return ok(out)
    }

    private suspend fun setBpc(json: JSONObject): CommandResult {
        val (t1, t2, t3) = (1..3).map { json.getInt("track$it") }
        Msr605xProtocol.buildBpc(t1, t2, t3) // validates 5..8
        val applied = openDevice().setBpc(t1, t2, t3) ?: return deviceRefused("setBpc")
        return ok(JSONObject().put("track1", applied.first).put("track2", applied.second).put("track3", applied.third))
    }

    private suspend fun setLeadingZeros(json: JSONObject): CommandResult {
        val t13 = json.getInt("track13")
        val t2 = json.getInt("track2")
        Msr605xProtocol.buildLeadingZeros(t13, t2) // validates 0..255
        if (!openDevice().setLeadingZeros(t13, t2)) return deviceRefused("setLeadingZeros")
        return ok(JSONObject().put("track13", t13).put("track2", t2))
    }

    private suspend fun setLed(json: JSONObject): CommandResult {
        val command = when (val led = json.optString("led")) {
            "off" -> Msr605xProtocol.CMD_ALL_LED_OFF
            "green" -> Msr605xProtocol.CMD_GREEN_LED_ON
            "yellow" -> Msr605xProtocol.CMD_YELLOW_LED_ON
            "red" -> Msr605xProtocol.CMD_RED_LED_ON
            "all" -> Msr605xProtocol.CMD_ALL_LED_ON
            else -> throw IllegalArgumentException("led must be off, green, yellow, red or all, got \"$led\"")
        }
        openDevice().setLed(command)
        return CommandResult.Success()
    }

    // --- Helpers ---

    /** Runs a swipe-waiting operation as a child job so [pendingSwipe] can be cancelled by `cancel`. */
    private suspend fun <T> swipe(block: suspend () -> T): T = coroutineScope {
        val job = async { block() }
        pendingSwipe = job
        try {
            job.await()
        } finally {
            pendingSwipe = null
        }
    }

    private fun timeoutOf(json: JSONObject): Long {
        val timeout = json.optLong("timeoutMs", DEFAULT_TIMEOUT_MS)
        require(timeout in 1..MAX_TIMEOUT_MS) { "timeoutMs must be 1-$MAX_TIMEOUT_MS" }
        return timeout
    }

    private fun statusResult(status: Char?, failureCode: String): CommandResult =
        if (status == Msr605xProtocol.STATUS_OK) {
            ok(JSONObject().put("status", "ok"))
        } else {
            CommandResult.Failure(failureCode, "MSR605X: ${Msr605xProtocol.describeStatus(status)}")
        }

    private fun deviceRefused(what: String) = CommandResult.Failure("device_error", "MSR605X rejected $what")

    private fun ok(json: JSONObject) = CommandResult.Success(json.toString())

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun parseHex(value: String, field: String): ByteArray {
        val hex = value.filterNot { it.isWhitespace() }
        require(hex.length % 2 == 0 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "$field must be a hex string"
        }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun emitEvent(event: String, data: String) {
        callback?.onEvent(event, data)
    }

    companion object {
        private const val CAPABILITY = "msr605x"
        private const val EVENT_SWIPE = "msr605x.swipe"
        private const val EVENT_ATTACHED = "msr605x.attached"
        private const val EVENT_DETACHED = "msr605x.detached"
        // Unified `magneticCardReader` interface event, emitted alongside the native swipe event.
        private const val EVENT_ON_SWIPE = "magneticCardReader.onSwipe"
        private const val ACTION_USB_PERMISSION = "dev.duma.android.hal.plugins.msr605x.USB_PERMISSION"
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val MAX_TIMEOUT_MS = 120_000L
    }
}
