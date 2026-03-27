package dev.duma.android.hal.plugins.sunmi.nfc

import android.content.Context
import com.sunmi.nfc.INfcListener
import com.sunmi.nfc.Nfc
import com.sunmi.peripheralsdk.NfcManager
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi NfcManager/NfcManager SDK.
 * Controls NFC modules (under-screen + external) on SUNMI FLEX 3.
 *
 * Emits event: sunmi.nfc.modulesChanged — when available NFC modules list changes.
 *
 * @param context Android Context needed to bind NfcManager service.
 *                Pass null only when constructing without a device (e.g. reflection-based
 *                registration via tryRegisterPlugin); the plugin will be non-functional
 *                until a Context is available.
 */
class SunmiNfcPlugin(
    private val context: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.nfc"
    override val version = 1

    private var callback: HalPluginEventCallback? = null
    private val mutex = Mutex()

    private val nfcListener = object : INfcListener.Stub() {
        override fun onNfcListChanged(nfcList: MutableList<Nfc>?) {
            callback?.onEvent("sunmi.nfc.modulesChanged", buildNfcListJson(nfcList))
        }
    }

    override fun getCapabilities(): List<String> = listOf("sunmi.nfc")

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi NFC Controller",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.nfc.switchModule",
                "Switch active NFC module by serial number.",
                "sunmi.nfc",
                exampleParameters = """{"sn": "NFC-001"}""",
                exampleOutput = """{"status": "ok"}"""
            ),
            MethodDescriptor(
                "sunmi.nfc.setWatermarkAlpha",
                "Set NFC watermark transparency (0-100).",
                "sunmi.nfc",
                exampleParameters = """{"alpha": 100}""",
                exampleOutput = """{"status": "ok"}"""
            )
        ),
        events = listOf(
            EventDescriptor(
                "sunmi.nfc.modulesChanged",
                "Fired when the list of available NFC modules changes.",
                "sunmi.nfc",
                exampleEvent = """{"modules": [{"sn": "NFC-001"}, {"sn": "NFC-002"}]}"""
            )
        )
    )

    override fun initialize(context: PluginContext) {
        this.context?.let { ctx ->
            NfcManager.init(ctx) { success ->
                if (success) NfcManager.registerNfcListener(nfcListener)
            }
        }
    }

    override suspend fun execute(method: String, params: String): String = mutex.withLock {
        return@withLock try {
            when (method) {
                "sunmi.nfc.switchModule" -> {
                    val sn = JSONObject(params).getString("sn")
                    NfcManager.switchNfc(sn)
                    success()
                }
                "sunmi.nfc.setWatermarkAlpha" -> {
                    val alpha = JSONObject(params).getInt("alpha")
                    if (alpha < 0 || alpha > 100) {
                        return@withLock error("invalid_params", "alpha must be 0-100")
                    }
                    NfcManager.setNfcWaterMarkAlpha(alpha)
                    success()
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

    private fun buildNfcListJson(nfcList: MutableList<Nfc>?): String {
        val arr = JSONArray()
        nfcList?.forEach { nfc ->
            arr.put(JSONObject().apply { put("sn", nfc.sn) })
        }
        return JSONObject().apply { put("modules", arr) }.toString()
    }

    private fun success(): String = """{"status":"ok"}"""
    private fun error(code: String, message: String): String =
        """{"error":"$code","message":"$message"}"""
}
