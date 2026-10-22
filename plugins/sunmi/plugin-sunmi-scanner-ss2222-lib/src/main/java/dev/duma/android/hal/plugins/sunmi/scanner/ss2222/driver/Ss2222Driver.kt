package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.Ss2222Parameters
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PermissionDisposition { STALE, DENIED, GRANTED }
internal enum class ScannerMultiplicity { NONE, SINGLE, AMBIGUOUS }

internal fun evaluatePermissionResult(
    expectedGeneration: Int,
    expectedDeviceId: Int,
    expectedDeviceName: String,
    resultGeneration: Int,
    resultDeviceId: Int,
    resultDeviceName: String,
    isScanner: Boolean,
    granted: Boolean,
    hasPermission: Boolean,
): PermissionDisposition = when {
    resultGeneration != expectedGeneration ||
        resultDeviceId != expectedDeviceId ||
        resultDeviceName != expectedDeviceName ||
        !isScanner -> PermissionDisposition.STALE
    !granted || !hasPermission -> PermissionDisposition.DENIED
    else -> PermissionDisposition.GRANTED
}

internal fun scannerMultiplicity(count: Int): ScannerMultiplicity = when (count) {
    0 -> ScannerMultiplicity.NONE
    1 -> ScannerMultiplicity.SINGLE
    else -> ScannerMultiplicity.AMBIGUOUS
}

class Ss2222Driver(
    context: Context,
    private val listener: Listener,
) {
    enum class State {
        DISCONNECTED,
        WAITING_FOR_PERMISSION,
        CONNECTING,
        IDLE,
        STARTING,
        SCANNING,
        STOPPING,
        ERROR,
    }

    data class DeviceInfo(
        val model: String,
        val productId: String,
        val hardwareVersion: String,
        val firmwareVersion: String,
    )

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onBarcode(raw: ByteArray)
        fun onError(code: String, message: String) {}
    }

    class DriverException(
        val code: String,
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private data class PermissionRequest(
        val generation: Int,
        val deviceId: Int,
        val deviceName: String,
    )

    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val wantOpen = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val permissionGeneration = AtomicInteger(0)
    private val lifecycleGeneration = AtomicLong(0)
    private val permissionAction = "${app.packageName}.SS2222_USB_PERMISSION.${hashCode()}"
    private val receiverLock = Any()
    private val closeLock = Any()
    private val connectMutex = Mutex()

    @Volatile
    private var stateValue = State.DISCONNECTED
    @Volatile
    private var deviceInfoValue: DeviceInfo? = null
    @Volatile
    private var activeTransport: AndroidUsbMessageTransport? = null
    @Volatile
    private var permissionRequest: PermissionRequest? = null
    @Volatile
    private var receiversRegistered = false
    @Volatile
    private var requireHotplug = false
    @Volatile
    private var invalidatedDeviceKey: String? = null
    @Volatile
    private var closed = false

    val state: State get() = stateValue
    val deviceInfo: DeviceInfo? get() = deviceInfoValue

    private val controller = Ss2222Controller(
        scope = scope,
        events = object : ControllerEvents {
            override fun onStateChanged(state: State) = publishState(state)

            override fun onBarcode(source: MessageTransport, raw: ByteArray) {
                val android = source as? AndroidUsbMessageTransport ?: return
                val generation = lifecycleGeneration.get()
                if (activeTransport !== android) return
                main.post {
                    if (
                        generation == lifecycleGeneration.get() &&
                        activeTransport === android && !closed
                    ) {
                        listener.onBarcode(raw.copyOf())
                    }
                }
            }

            override fun onError(code: String, message: String) = publishError(code, message)

            override fun onRevision(packet: SsiCodec.Packet) {
                val generation = lifecycleGeneration.get()
                val info = parseRevision(packet.data)
                if (info.model != EXPECTED_MODEL) {
                    throw DriverException("unexpected_model", "Expected $EXPECTED_MODEL, got ${info.model}")
                }
                if (generation != lifecycleGeneration.get() || activeTransport == null || closing.get()) {
                    throw DriverException("device_disconnected", "Scanner changed while reading revision")
                }
                deviceInfoValue = info
            }

            override fun onTransportInvalidated(transport: MessageTransport) {
                val android = transport as? AndroidUsbMessageTransport ?: return
                if (activeTransport === android) activeTransport = null
                requireHotplug = true
                invalidatedDeviceKey = android.deviceKey
                val stillAttached = usb.deviceList.values.any {
                    "${it.deviceId}:${it.deviceName}" == android.deviceKey
                }
                if (!stillAttached) {
                    requireHotplug = false
                    invalidatedDeviceKey = null
                    publishState(State.DISCONNECTED)
                }
            }
        },
    )

    fun open() {
        if (closing.get() || closed) {
            publishError("closing", "This driver instance is closing and cannot be reopened")
            return
        }
        if (!wantOpen.compareAndSet(false, true)) return
        lifecycleGeneration.incrementAndGet()
        registerReceivers()
        scope.launch { discoverAndConnect() }
    }

    /** Starts a scan after the scanner acknowledges E9. Only one active scan is allowed. */
    suspend fun start(singleShot: Boolean) = controller.start(singleShot)

    /** Stops the current scan with EA. Calling it while idle is harmless. */
    suspend fun stop() = controller.stop()

    suspend fun beep(error: Boolean) = controller.beep(error)

    suspend fun readParam(id: Int): ByteArray = controller.readParam(
        validateParameter("read") { Ss2222Parameters.readable(id) },
    )

    /** Writes temporary (SSI status 00) one-byte values and verifies each with PARAM_REQUEST. */
    suspend fun writeParams(values: Map<Int, Int>) = controller.writeParams(
        validateParameter("write") { Ss2222Parameters.validateWrites(values) },
    )

    /** Returns the complete, checksum-validated SSI REPLY_REVISION packet. */
    suspend fun queryRevision(): ByteArray = controller.queryRevision()

    fun close() {
        synchronized(closeLock) {
            if (closed) return
            if (!closing.compareAndSet(false, true)) return
            wantOpen.set(false)
            lifecycleGeneration.incrementAndGet()
            permissionGeneration.incrementAndGet()
            permissionRequest = null
            try {
                runBlocking(Dispatchers.IO) {
                    controller.controlledClose()
                    // Barrier against an open/initialize path which started before close().
                    withTimeoutOrNull(CONNECT_BARRIER_TIMEOUT_MS) {
                        connectMutex.withLock { }
                    }
                    activeTransport?.close()
                }
            } finally {
                activeTransport = null
                unregisterReceivers()
                deviceInfoValue = null
                stateValue = State.DISCONNECTED
                closed = true
                main.post {
                    if (closed) listener.onStateChanged(State.DISCONNECTED)
                }
                scope.cancel()
            }
        }
    }

    private suspend fun discoverAndConnect() = connectMutex.withLock {
        if (!wantOpen.get() || closing.get() || requireHotplug) return@withLock
        val matches = usb.deviceList.values.filter(::isScanner)
        when (scannerMultiplicity(matches.size)) {
            ScannerMultiplicity.NONE -> publishState(State.DISCONNECTED)
            ScannerMultiplicity.AMBIGUOUS -> {
                permissionGeneration.incrementAndGet()
                permissionRequest = null
                lifecycleGeneration.incrementAndGet()
                activeTransport?.let { active ->
                    controller.controlledClose()
                    if (activeTransport === active) activeTransport = null
                }
                publishState(State.ERROR)
                publishError("ambiguous_device", "More than one SS2222 scanner is attached")
            }
            ScannerMultiplicity.SINGLE -> {
                if (activeTransport == null && permissionRequest == null) {
                    connectOrRequestPermissionLocked(matches.single())
                }
            }
        }
    }

    private suspend fun connectOrRequestPermissionLocked(device: UsbDevice) {
        if (!wantOpen.get() || closing.get() || requireHotplug || activeTransport != null) return
        if (!usb.hasPermission(device)) {
            requestPermission(device)
            return
        }
        val generation = lifecycleGeneration.incrementAndGet()
        publishState(State.CONNECTING, generation)
        val transport = try {
            AndroidUsbMessageTransport.open(usb, device, usbCallbacks)
        } catch (e: Throwable) {
            if (generation == lifecycleGeneration.get() && wantOpen.get() && !closing.get()) {
                publishState(State.ERROR, generation)
                val driver = e as? DriverException
                publishError(
                    driver?.code ?: "usb_open_failed",
                    e.message ?: "Could not open scanner",
                    generation,
                )
            }
            return
        }
        if (!wantOpen.get() || closing.get() || generation != lifecycleGeneration.get()) {
            transport.close()
            return
        }
        activeTransport = transport
        try {
            controller.initialize(transport)
        } catch (_: Throwable) {
            if (activeTransport === transport) activeTransport = null
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val generation = permissionGeneration.incrementAndGet()
        permissionRequest = PermissionRequest(generation, device.deviceId, device.deviceName)
        publishState(State.WAITING_FOR_PERMISSION)
        val intent = Intent(permissionAction)
            .setPackage(app.packageName)
            .putExtra(EXTRA_PERMISSION_GENERATION, generation)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(device, PendingIntent.getBroadcast(app, generation, intent, flags))
    }

    private val usbCallbacks = object : AndroidUsbMessageTransport.Callbacks {
        override fun onMessage(transport: AndroidUsbMessageTransport, message: ByteArray) {
            controller.onMessage(transport, message)
        }

        override fun onFramingError(transport: AndroidUsbMessageTransport, message: String) {
            if (activeTransport === transport) publishError("hid_framing", message)
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val request = permissionRequest ?: return
            val generation = intent.getIntExtra(EXTRA_PERMISSION_GENERATION, -1)
            val device = intent.usbDevice() ?: return
            when (
                evaluatePermissionResult(
                    request.generation,
                    request.deviceId,
                    request.deviceName,
                    generation,
                    device.deviceId,
                    device.deviceName,
                    isScanner(device),
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                    usb.hasPermission(device),
                )
            ) {
                PermissionDisposition.STALE -> return
                PermissionDisposition.DENIED -> {
                    permissionRequest = null
                    publishState(State.DISCONNECTED)
                    publishError("permission_denied", "USB permission was denied")
                    return
                }
                PermissionDisposition.GRANTED -> permissionRequest = null
            }
            scope.launch { discoverAndConnect() }
        }
    }

    private val hotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!isScanner(device)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDetach(device)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (wantOpen.get()) {
                    scope.launch { discoverAndConnect() }
                }
            }
        }
    }

    private fun handleDetach(device: UsbDevice) {
        val key = "${device.deviceId}:${device.deviceName}"
        val active = activeTransport
        if (active != null && active.deviceKey == key) {
            activeTransport = null
            lifecycleGeneration.incrementAndGet()
            controller.onDetached(active)
            scope.launch { active.close() }
        } else {
            lifecycleGeneration.incrementAndGet()
        }
        if (requireHotplug && invalidatedDeviceKey == key) {
            requireHotplug = false
            invalidatedDeviceKey = null
            publishState(State.DISCONNECTED)
        }
        permissionRequest?.takeIf { it.deviceId == device.deviceId && it.deviceName == device.deviceName }
            ?.let { permissionRequest = null }
        if (wantOpen.get() && !closing.get() && !requireHotplug) {
            scope.launch { discoverAndConnect() }
        }
    }

    private fun registerReceivers() = synchronized(receiverLock) {
        if (receiversRegistered) return
        val permissionFilter = IntentFilter(permissionAction)
        val hotplugFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            app,
            permissionReceiver,
            permissionFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            app,
            hotplugReceiver,
            hotplugFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiversRegistered = true
    }

    private fun unregisterReceivers() = synchronized(receiverLock) {
        if (!receiversRegistered) return
        try {
            app.unregisterReceiver(permissionReceiver)
        } catch (_: IllegalArgumentException) { }
        try {
            app.unregisterReceiver(hotplugReceiver)
        } catch (_: IllegalArgumentException) { }
        receiversRegistered = false
    }

    private fun publishState(newState: State, generation: Long = lifecycleGeneration.get()) {
        if (generation != lifecycleGeneration.get() || closed) return
        if (stateValue == newState) return
        stateValue = newState
        main.post {
            if (generation == lifecycleGeneration.get() && !closed) {
                listener.onStateChanged(newState)
            }
        }
    }

    private fun publishError(
        code: String,
        message: String,
        generation: Long = lifecycleGeneration.get(),
    ) {
        main.post {
            if (generation == lifecycleGeneration.get() && !closed) {
                listener.onError(code, message)
            }
        }
    }

    private inline fun <T> validateParameter(operation: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw DriverException("invalid_parameter", "Parameter $operation rejected: ${e.message}", e)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        const val VENDOR_ID = 0x324F
        const val PRODUCT_ID = 0x0113
        private const val EXPECTED_MODEL = "SS2222"
        private const val EXTRA_PERMISSION_GENERATION = "generation"
        private const val CONNECT_BARRIER_TIMEOUT_MS = 3_500L

        fun isScanner(device: UsbDevice?): Boolean =
            device != null && device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID

        internal fun parseRevision(data: ByteArray): DeviceInfo {
            val text = data.toString(Charsets.US_ASCII)
            fun field(name: String, next: String?): String {
                val start = text.indexOf(name)
                if (start < 0) throw DriverException("invalid_revision", "Missing '$name' in revision reply")
                val valueStart = start + name.length
                val end = next?.let { text.indexOf(it, valueStart).takeIf { index -> index >= 0 } } ?: text.length
                return text.substring(valueStart, end).trim()
            }
            return DeviceInfo(
                model = field("Product Name:", "Product ID:"),
                productId = field("Product ID:", "Hardware version:"),
                hardwareVersion = field("Hardware version:", "Firmware version:"),
                firmwareVersion = field("Firmware version:", null),
            )
        }
    }
}

internal typealias DriverException = Ss2222Driver.DriverException
