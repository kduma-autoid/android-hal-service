package dev.duma.android.hal.plugins.sunmi.scanner.external

import android.content.Context
import com.sunmi.scanner.connector.InitStatusCallback
import com.sunmi.scanner.connector.ScanResultCallback
import com.sunmi.scanner.manager.LittleFlashScanner
import com.sunmi.sdk.ServiceConnectStatus
import dev.duma.android.hal.contract.BaseHalPlugin
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.stripExperimental
import dev.duma.android.hal.plugins.sunmi.scanner.common.ScannerServiceManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi LittleFlashScanner SDK for external USB/serial
 * barcode scanner modules. Provides scan trigger, configuration, and lifecycle management.
 *
 * Scan results are delivered as [EVENT_BARCODE] events.
 */
class SunmiExternalScannerPlugin(
    private val appContext: Context? = null
) : BaseHalPlugin() {

    override val pluginId = "sunmi.scanner.external"
    override val version = 1

    private var eventCallback: HalPluginEventCallback? = null
    private val mutex = Mutex()
    private var connectionListener: ServiceConnectStatus? = null
    @Volatile
    private var initialized = false

    companion object {
        private const val EVENT_BARCODE = "sunmi.scanner.external.barcode"
        private const val EVENT_INITIALIZED = "sunmi.scanner.external.initialized"
        private const val EVENT_INIT_FAILED = "sunmi.scanner.external.initFailed"
        private const val EVENT_SERVICE_CONNECTED = "sunmi.scanner.external.serviceConnected"
        private const val EVENT_SERVICE_DISCONNECTED = "sunmi.scanner.external.serviceDisconnected"
    }

    private val scanResultCallback = object : ScanResultCallback {
        override fun onScanResultAvailable(data: String?, rawData: ByteArray?, format: String?, status: String?) {
            val payload = JSONObject()
                .put("data", data ?: "")
                .put("format", format ?: "")
                .put("status", status ?: "")
            if (rawData != null) {
                payload.put("rawData", rawData.joinToString("") { "%02x".format(it) })
            }
            emitEvent(EVENT_BARCODE, payload.toString())
        }
    }

    // --- Lifecycle ---

    override fun isSupported(): Boolean = true

    override fun initialize(pluginContext: PluginContext) {
        val ctx = appContext ?: return

        ScannerServiceManager.acquire(ctx)

        val listener = object : ServiceConnectStatus {
            override fun onServiceConnected() {
                initScanner()
                emitEvent(EVENT_SERVICE_CONNECTED, "{}")
            }

            override fun onServiceDisconnected() {
                initialized = false
                emitEvent(EVENT_SERVICE_DISCONNECTED, "{}")
            }
        }
        connectionListener = listener
        ScannerServiceManager.addConnectionListener(listener)

        if (ScannerServiceManager.isConnected()) {
            initScanner()
        }
    }

    private fun initScanner() {
        val scanner = LittleFlashScanner.getInstance()
        scanner.init(object : InitStatusCallback {
            override fun onSuccess() {
                initialized = true
                scanner.setScanResultCallback(scanResultCallback)
                emitEvent(EVENT_INITIALIZED, "{}")
            }

            override fun onFail(message: String?) {
                initialized = false
                emitEvent(EVENT_INIT_FAILED, JSONObject().put("message", message ?: "Unknown error").toString())
            }
        })
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        eventCallback = callback
    }

    override fun getCapabilities() = listOf("sunmi.scanner.external")

    override fun getDescriptor() = fullDescriptor().let {
        if (BuildConfig.WITH_EXPERIMENTAL) it else it.stripExperimental()
    }

    private fun fullDescriptor() = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "Sunmi: Scanner (External)",
        version = version,
        experimental = true,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.scanner.external.trigger",
                "Start barcode scan. Result delivered via barcode event.",
                "sunmi.scanner.external",
                exampleParameters = """{}""",
                exampleOutput = """{"status":"scanning"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.external.stop",
                "Stop active barcode scan.",
                "sunmi.scanner.external",
                exampleParameters = """{}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.external.setParams",
                "Set scanner parameters as key-value pairs.",
                "sunmi.scanner.external",
                exampleParameters = """{"ScanMode":"1","DurationScanMode":"3"}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.external.getVersion",
                "Get the scanner SDK version string.",
                "sunmi.scanner.external",
                exampleParameters = """{}""",
                exampleOutput = """{"version":"1.0.0"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.external.getScanEngineVersion",
                "Request scan engine version (async, logged by SDK).",
                "sunmi.scanner.external",
                exampleParameters = """{}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.external.sendData",
                "Send raw hex-encoded data to the scanner module.",
                "sunmi.scanner.external",
                exampleParameters = """{"data":"1b31"}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.external.release",
                "Release scanner resources and unregister callbacks.",
                "sunmi.scanner.external",
                exampleParameters = """{}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.external.isServiceConnected",
                "Check if the scanner service is connected.",
                "sunmi.scanner.external",
                exampleParameters = """{}""",
                exampleOutput = """{"connected":true}"""
            )
        ),
        events = listOf(
            EventDescriptor(
                EVENT_BARCODE,
                "Fired when a barcode is successfully scanned.",
                "sunmi.scanner.external",
                exampleEvent = """{"data":"5901234123457","format":"EAN13","status":"SCAN_SUCCESS","rawData":"35393031323334313233343537"}"""
            ),
            EventDescriptor(
                EVENT_INITIALIZED,
                "Fired when the external scanner is initialized successfully.",
                "sunmi.scanner.external",
                exampleEvent = """{}"""
            ),
            EventDescriptor(
                EVENT_INIT_FAILED,
                "Fired when scanner initialization fails.",
                "sunmi.scanner.external",
                exampleEvent = """{"message":"Device not found"}"""
            ),
            EventDescriptor(
                EVENT_SERVICE_CONNECTED,
                "Fired when the scanner service becomes available.",
                "sunmi.scanner.external",
                exampleEvent = """{}"""
            ),
            EventDescriptor(
                EVENT_SERVICE_DISCONNECTED,
                "Fired when the scanner service disconnects.",
                "sunmi.scanner.external",
                exampleEvent = """{}"""
            )
        )
    )

    // --- Execute ---

    override suspend fun onExecute(method: String, params: String): CommandResult = mutex.withLock {
        val scanner = LittleFlashScanner.getInstance()
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)

        return@withLock try {
            when (method) {
                "sunmi.scanner.external.trigger" -> {
                    if (!initialized) return@withLock CommandResult.Failure("not_initialized", "Scanner not initialized yet")
                    scanner.start()
                    CommandResult.Success("""{"status":"scanning"}""")
                }

                "sunmi.scanner.external.stop" -> {
                    if (!initialized) return@withLock CommandResult.Failure("not_initialized", "Scanner not initialized yet")
                    scanner.stop()
                    CommandResult.Success()
                }

                "sunmi.scanner.external.setParams" -> {
                    if (!initialized) return@withLock CommandResult.Failure("not_initialized", "Scanner not initialized yet")
                    val map = mutableMapOf<String, String>()
                    json.keys().forEach { key -> map[key] = json.getString(key) }
                    scanner.setParams(map)
                    CommandResult.Success()
                }

                "sunmi.scanner.external.getVersion" -> {
                    CommandResult.Success(JSONObject().put("version", scanner.version() ?: "unknown").toString())
                }

                "sunmi.scanner.external.getScanEngineVersion" -> {
                    scanner.getScanEngineVersion()
                    CommandResult.Success()
                }

                "sunmi.scanner.external.sendData" -> {
                    if (!initialized) return@withLock CommandResult.Failure("not_initialized", "Scanner not initialized yet")
                    val hex = json.getString("data")
                    val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    scanner.sendData(bytes)
                    CommandResult.Success()
                }

                "sunmi.scanner.external.release" -> {
                    initialized = false
                    scanner.unRegisterScanResultCallback()
                    scanner.release()
                    CommandResult.Success()
                }

                "sunmi.scanner.external.isServiceConnected" -> {
                    CommandResult.Success(JSONObject().put("connected", ScannerServiceManager.isConnected()).toString())
                }

                else -> CommandResult.unsupportedMethod(method)
            }
        } catch (e: Exception) {
            CommandResult.internalError(e.message ?: "Unknown SDK error")
        }
    }

    // --- Helpers ---

    private fun emitEvent(event: String, data: String) {
        eventCallback?.onEvent(event, data)
    }
}
