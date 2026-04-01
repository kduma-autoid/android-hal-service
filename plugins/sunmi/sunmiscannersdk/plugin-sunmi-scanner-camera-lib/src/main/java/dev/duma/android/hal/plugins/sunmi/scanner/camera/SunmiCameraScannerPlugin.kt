package dev.duma.android.hal.plugins.sunmi.scanner.camera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.sunmi.scanner.sdk.CameraScanner
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi CameraScanner SDK for camera-based barcode scanning.
 * Uses a transparent [CameraScannerProxyActivity] to launch the camera scanner UI.
 *
 * Trigger starts the camera scanner; results arrive as [EVENT_BARCODE] events.
 * If the user cancels, [EVENT_CANCELLED] is emitted.
 */
class SunmiCameraScannerPlugin(
    private val appContext: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.scanner.camera"
    override val version = 1

    private var eventCallback: HalPluginEventCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var scanning = false

    private val config = mutableMapOf<String, Any>(
        CameraScanner.IS_QR_CODE_ENABLE to true,
        CameraScanner.IS_PDF417_ENABLE to true,
        CameraScanner.IS_DATA_MATRIX_ENABLE to true,
        CameraScanner.IS_AZTEC_ENABLE to true,
        CameraScanner.IS_EAN_13_ENABLE to true,
        CameraScanner.IS_EAN_8_ENABLE to true,
        CameraScanner.IS_UPC_A_ENABLE to true,
        CameraScanner.IS_UPC_E_ENABLE to true,
        CameraScanner.IS_CODE_128_ENABLE to true,
        CameraScanner.IS_CODE_39_ENABLE to true,
        CameraScanner.IS_CODE_93_ENABLE to true,
        CameraScanner.IS_CODABAR_ENABLE to true,
        CameraScanner.IS_INTERLEAVED_2_OF_5_ENABLE to true,
        CameraScanner.PLAY_SOUND to true,
        CameraScanner.PLAY_VIBRATE to true
    )

    companion object {
        private const val EVENT_BARCODE = "sunmi.scanner.camera.barcode"
        private const val EVENT_CANCELLED = "sunmi.scanner.camera.cancelled"
    }

    // --- Lifecycle ---

    override fun isSupported(): Boolean = true

    override fun initialize(pluginContext: PluginContext) {
        // No SDK initialization needed; CameraScanner is Activity-based
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        eventCallback = callback
    }

    override fun getCapabilities() = listOf("sunmi.scanner.camera")

    override fun getDescriptor() = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "Sunmi: Scanner (Camera)",
        version = version,
        experimental = true,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.scanner.camera.trigger",
                "Open camera scanner UI. Result delivered via barcode event.",
                "sunmi.scanner.camera",
                exampleParameters = """{}""",
                exampleOutput = """{"status":"scanning"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.camera.stop",
                "Close camera scanner UI if currently open.",
                "sunmi.scanner.camera",
                exampleParameters = """{}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.camera.configure",
                "Set camera scanner configuration options.",
                "sunmi.scanner.camera",
                exampleParameters = """{"IS_QR_CODE_ENABLE":true,"PLAY_SOUND":false,"CAMERA_ID":"0"}""",
                exampleOutput = """{}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.camera.getConfig",
                "Get current camera scanner configuration.",
                "sunmi.scanner.camera",
                exampleParameters = """{}""",
                exampleOutput = """{"config":{"IS_QR_CODE_ENABLE":true,"PLAY_SOUND":true}}"""
            )
        ),
        events = listOf(
            EventDescriptor(
                EVENT_BARCODE,
                "Fired when a barcode is successfully scanned by camera.",
                "sunmi.scanner.camera",
                exampleEvent = """{"data":"5901234123457","format":"EAN13"}"""
            ),
            EventDescriptor(
                EVENT_CANCELLED,
                "Fired when the user cancels the camera scan.",
                "sunmi.scanner.camera",
                exampleEvent = """{}"""
            )
        )
    )

    // --- Execute ---

    override suspend fun execute(method: String, params: String): CommandResult {
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)

        return try {
            when (method) {
                "sunmi.scanner.camera.trigger" -> {
                    val ctx = appContext ?: return CommandResult.internalError("Application context not available")
                    if (scanning) return CommandResult.Failure("already_scanning", "Camera scanner is already active")

                    scanning = true
                    val deferred = CompletableDeferred<Pair<String, String>?>()
                    CameraScannerProxyActivity.pendingResult = deferred
                    CameraScannerProxyActivity.currentConfig = buildConfigBundle()

                    val intent = Intent(ctx, CameraScannerProxyActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)

                    scope.launch {
                        try {
                            val result = deferred.await()
                            scanning = false
                            if (result != null) {
                                val payload = JSONObject()
                                    .put("data", result.first)
                                    .put("format", result.second)
                                emitEvent(EVENT_BARCODE, payload.toString())
                            } else {
                                emitEvent(EVENT_CANCELLED, "{}")
                            }
                        } catch (_: Exception) {
                            scanning = false
                            emitEvent(EVENT_CANCELLED, "{}")
                        }
                    }

                    CommandResult.Success("""{"status":"scanning"}""")
                }

                "sunmi.scanner.camera.stop" -> {
                    CameraScannerProxyActivity.activeInstance?.finish()
                    scanning = false
                    CommandResult.Success()
                }

                "sunmi.scanner.camera.configure" -> {
                    json.keys().forEach { key ->
                        val value = json.get(key)
                        config[key] = value
                    }
                    CommandResult.Success()
                }

                "sunmi.scanner.camera.getConfig" -> {
                    val data = JSONObject()
                    for ((k, v) in config) {
                        data.put(k, v)
                    }
                    CommandResult.Success(JSONObject().put("config", data).toString())
                }

                else -> CommandResult.unsupportedMethod(method)
            }
        } catch (e: Exception) {
            CommandResult.internalError(e.message ?: "Unknown SDK error")
        }
    }

    // --- Helpers ---

    private fun buildConfigBundle(): Bundle {
        val bundle = Bundle()
        for ((key, value) in config) {
            when (value) {
                is Boolean -> bundle.putBoolean(key, value)
                is Int -> bundle.putInt(key, value)
                is String -> bundle.putString(key, value)
            }
        }
        return bundle
    }

    private fun emitEvent(event: String, data: String) {
        eventCallback?.onEvent(event, data)
    }
}
