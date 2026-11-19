package dev.duma.android.hal.plugins.dymo.m10

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * HAL plugin for the Dymo M10 USB postal scale (VID 0x0922, PID 0x8003), read directly over USB HID.
 * Ported from kduma-OSS/CS-usb-scale-counter.
 *
 * The scale is hot-pluggable: the plugin reports itself available only while it is attached *and*
 * the app holds USB permission for it (requested automatically when the scale is detected). While
 * available, a background loop reads the scale's reports and turns them into readings and events
 * via [ScaleState] — which also computes the `stable` flag, as the scale's own status says `stable`
 * nearly all the time.
 *
 * Provides the `scale` interface (`scale.getInfo`, `scale.getWeight`, `scale.onWeight`,
 * `scale.onStatus`) and the same under `dymo.m10.*`.
 */
class DymoM10Plugin(
    private val appContext: Context? = null
) : BaseHalPlugin() {

    override val pluginId = "dymo.m10"
    override val version = 1

    private var callback: HalPluginEventCallback? = null
    private var pluginContext: PluginContext? = null
    private var receiversRegistered = false
    @Volatile private var permissionPending = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readLock = Any()
    /** Guarded by [readLock]. */
    private var readJob: Job? = null
    @Volatile private var disposed = false
    /** The read loop that currently holds the open USB connection, or null. */
    @Volatile private var openedBy: Job? = null

    private val stateLock = Any()
    /** Guarded by [stateLock]. */
    private val state = ScaleState()

    private val usbManager: UsbManager?
        get() = appContext?.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) permissionPending = false
            @Suppress("DEPRECATION")
            val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (!isDymo(dev)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(dev)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached()
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    pluginContext?.setPluginAvailable(granted)
                    if (granted) startReading()
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
        disposed = true
        stopReading()
        scope.cancel()
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
        if (granted) startReading()
    }

    private fun onDetached() {
        stopReading()
        synchronized(stateLock) { state.clear() }
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

    private fun findDevice(): UsbDevice? = usbManager?.deviceList?.values?.firstOrNull(::isDymo)

    private fun isDymo(dev: UsbDevice) =
        dev.vendorId == DymoM10Protocol.VENDOR_ID && dev.productId == DymoM10Protocol.PRODUCT_ID

    private fun hasPermission(): Boolean {
        val dev = findDevice() ?: return false
        return usbManager?.hasPermission(dev) == true
    }

    // --- Read loop ---

    /** Starts the read loop unless it is already running or the scale isn't attached with permission. */
    private fun startReading() {
        synchronized(readLock) {
            if (disposed || readJob?.isActive == true) return
            if (!hasPermission()) return
            readJob = scope.launch { readLoop() }
        }
    }

    private fun stopReading() {
        synchronized(readLock) {
            readJob?.cancel()
            readJob = null
        }
    }

    /** Opens the scale and feeds its reports to [state] until cancelled or the scale goes away. */
    private suspend fun readLoop() {
        while (currentCoroutineContext().isActive) {
            val usb = usbManager ?: return
            val dev = findDevice() ?: return
            if (!usb.hasPermission(dev)) return
            val transport = try {
                UsbScaleTransport(usb, dev)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // IOException, but openDevice can also throw (IllegalArgument / Security) when the
                // scale goes away mid-open — nothing may escape: the scope has no exception handler.
                Log.w(TAG, "cannot open the scale, retrying: ${e.message}")
                delay(RETRY_MS)
                continue
            }
            Log.d(TAG, "reading started")
            val self = currentCoroutineContext()[Job]
            openedBy = self
            try {
                while (currentCoroutineContext().isActive) {
                    val startedAt = SystemClock.elapsedRealtime()
                    val report = transport.read(READ_TIMEOUT_MS)
                    val now = SystemClock.elapsedRealtime()
                    val reading = DymoM10Protocol.parse(report)
                    val emissions = synchronized(stateLock) {
                        if (reading != null) state.update(reading, now) else state.tick(now)
                    }
                    emissions.forEach(::emit)
                    // A failed transfer returns at once (e.g. the scale was just unplugged and the
                    // detach broadcast hasn't arrived yet): don't spin.
                    if (report == null && now - startedAt < MIN_TIMEOUT_MS) delay(MIN_TIMEOUT_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "read error, reopening", e)
                delay(RETRY_MS)
            } finally {
                runCatching { transport.close() }
                // Only this loop's own state: a re-attach may already have started the next loop.
                if (openedBy === self) {
                    openedBy = null
                    // Cleared here, after the last update — clearing only in onDetached would race
                    // with an iteration still in flight when the job was cancelled.
                    synchronized(stateLock) { state.clear() }
                }
                Log.d(TAG, "reading stopped")
            }
        }
    }

    private fun emit(emission: ScaleState.Emission) {
        when (emission) {
            is ScaleState.Emission.Weight -> {
                val data = readingJson(emission.snapshot).toString()
                emitEvent(EVENT_WEIGHT, data)
                emitEvent(EVENT_ON_WEIGHT, data)
            }
            is ScaleState.Emission.Status -> {
                val data = JSONObject()
                    .put("status", emission.status.json)
                    .put("previous", emission.previous.json)
                    .toString()
                emitEvent(EVENT_STATUS, data)
                emitEvent(EVENT_ON_STATUS, data)
            }
        }
    }

    private fun emitEvent(event: String, data: String) {
        callback?.onEvent(event, data)
    }

    // --- Descriptor ---

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Dymo M10: USB Scale",
        version = version,
        capabilities = getCapabilities(),
        interfaces = listOf(InterfaceBinding("scale", priority = 100)),
        groups = listOf(
            DescriptorGroup(
                name = "Scale",
                methods = listOf(
                    method(
                        "getWeight",
                        "The latest reading: grams, the displayed value + unit (g/oz), the scale's own status and " +
                            "stable — computed by the plugin (no change of 1 g or more for 500 ms, no error status). " +
                            "Fails with unavailable before the first reading.",
                        output = READING_EXAMPLE
                    ),
                    method(
                        "getInfo",
                        "Connection state, USB permission and the latest reading (null before the first one).",
                        output = """{"name":"Dymo M10","connected":true,"permission":true,"reading":$READING_EXAMPLE}"""
                    )
                ),
                events = listOf(
                    EventDescriptor(
                        EVENT_WEIGHT,
                        "A new reading: the weight moved by 1 g or more (stable=false), settled (stable=true) " +
                            "or the scale's status or unit changed. Also emitted as scale.onWeight.",
                        CAPABILITY,
                        exampleEvent = READING_EXAMPLE
                    ),
                    EventDescriptor(
                        EVENT_STATUS,
                        "The status reported by the scale changed. Also emitted as scale.onStatus.",
                        CAPABILITY,
                        exampleEvent = """{"status":"stable","previous":"inMotion"}"""
                    )
                )
            ),
            DescriptorGroup(
                name = "Device",
                methods = emptyList(),
                events = listOf(
                    EventDescriptor(EVENT_ATTACHED, "The scale was plugged in.", CAPABILITY, exampleEvent = "{}"),
                    EventDescriptor(EVENT_DETACHED, "The scale was unplugged.", CAPABILITY, exampleEvent = "{}")
                )
            )
        )
    )

    private fun method(name: String, description: String, output: String) = MethodDescriptor(
        "$CAPABILITY.$name", description, CAPABILITY, exampleParameters = "{}", exampleOutput = output
    )

    // --- Execute ---

    override suspend fun onExecute(method: String, params: String): CommandResult = when (method) {
        "$CAPABILITY.getWeight", "scale.getWeight" -> getWeight()
        "$CAPABILITY.getInfo", "scale.getInfo" -> getInfo()
        else -> CommandResult.unsupportedMethod(method)
    }

    private fun getWeight(): CommandResult {
        if (findDevice() == null) return CommandResult.unavailable("Dymo M10 not connected")
        if (!hasPermission()) return CommandResult.unavailable("USB permission not granted")
        val snapshot = synchronized(stateLock) { state.snapshot() }
            ?: return CommandResult.unavailable("No reading from the scale yet — is it switched on?")
        return CommandResult.Success(readingJson(snapshot).toString())
    }

    private fun getInfo(): CommandResult {
        val snapshot = synchronized(stateLock) { state.snapshot() }
        val json = JSONObject()
            .put("name", "Dymo M10")
            .put("connected", findDevice() != null && openedBy != null)
            .put("permission", hasPermission())
            .put("reading", snapshot?.let(::readingJson) ?: JSONObject.NULL)
        return CommandResult.Success(json.toString())
    }

    private fun readingJson(s: ScaleState.Snapshot) = JSONObject()
        .put("grams", s.reading.grams)
        .put("value", s.reading.value)
        .put("unit", s.reading.unit.json)
        .put("status", s.reading.status.json)
        .put("stable", s.stable)

    companion object {
        private const val TAG = "DymoM10"
        private const val CAPABILITY = "dymo.m10"
        private const val EVENT_WEIGHT = "dymo.m10.weight"
        private const val EVENT_STATUS = "dymo.m10.status"
        private const val EVENT_ATTACHED = "dymo.m10.attached"
        private const val EVENT_DETACHED = "dymo.m10.detached"
        // Unified `scale` interface events (see plugin-generic-lib ScaleInterface).
        private const val EVENT_ON_WEIGHT = "scale.onWeight"
        private const val EVENT_ON_STATUS = "scale.onStatus"
        private const val ACTION_USB_PERMISSION = "dev.duma.android.hal.plugins.dymo.USB_PERMISSION"
        private const val READ_TIMEOUT_MS = 250
        private const val MIN_TIMEOUT_MS = 50L
        private const val RETRY_MS = 1_000L

        private const val READING_EXAMPLE =
            """{"grams":1250.0,"value":1250.0,"unit":"g","status":"stable","stable":true}"""
    }
}
