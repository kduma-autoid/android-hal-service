package dev.duma.android.hal.plugins.sunmi.scanner.inner

import android.content.Context
import android.view.KeyEvent
import com.sunmi.scanner.entity.CodeEnable
import com.sunmi.scanner.entity.CodeSetting
import com.sunmi.scanner.entity.Entity
import com.sunmi.scanner.entity.Pair
import com.sunmi.scanner.entity.Result
import com.sunmi.scanner.entity.ServiceSetting
import com.sunmi.scanner.io.DataCallback
import com.sunmi.scanner.io.QueryCallback
import com.sunmi.scanner.sdk.InnerScanner
import com.sunmi.sdk.ServiceConnectStatus
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.scanner.common.ScannerResponseHelper.error
import dev.duma.android.hal.plugins.sunmi.scanner.common.ScannerResponseHelper.started
import dev.duma.android.hal.plugins.sunmi.scanner.common.ScannerResponseHelper.success
import dev.duma.android.hal.plugins.sunmi.scanner.common.ScannerServiceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi InnerScanner SDK for built-in hardware barcode scanners.
 * Provides scan trigger, configuration, and query capabilities.
 *
 * Scan results are delivered as [EVENT_BARCODE] events; trigger/stop return immediately.
 * Configuration methods (sendCommand, sendQuery) are synchronous.
 */
class SunmiInnerScannerPlugin(
    private val appContext: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.scanner.inner"
    override val version = 1

    private var eventCallback: HalPluginEventCallback? = null
    private val mutex = Mutex()
    private var connectionListener: ServiceConnectStatus? = null

    companion object {
        private const val CALLBACK_KEY = "hal_inner_scanner"
        private const val QUERY_TIMEOUT_MS = 5000L

        private const val EVENT_BARCODE = "sunmi.scanner.inner.barcode"
        private const val EVENT_SERVICE_CONNECTED = "sunmi.scanner.inner.serviceConnected"
        private const val EVENT_SERVICE_DISCONNECTED = "sunmi.scanner.inner.serviceDisconnected"
    }

    private val decodeCallback = object : DataCallback() {
        override fun onResult(data: String?, rawData: ByteArray?, format: String?) {
            val payload = JSONObject()
                .put("data", data ?: "")
                .put("format", format ?: "")
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
                InnerScanner.getInstance().registerDecodeCallback(CALLBACK_KEY, decodeCallback)
                emitEvent(EVENT_SERVICE_CONNECTED, "{}")
            }

            override fun onServiceDisconnected() {
                emitEvent(EVENT_SERVICE_DISCONNECTED, "{}")
            }
        }
        connectionListener = listener
        ScannerServiceManager.addConnectionListener(listener)
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        eventCallback = callback
    }

    override fun getCapabilities() = listOf("sunmi.scanner.inner")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi Inner Scanner",
        version = version,
        capabilities = getCapabilities(),
        methods = listOf(
            MethodDescriptor(
                "sunmi.scanner.inner.trigger",
                "Start barcode scan. Result delivered via barcode event.",
                "sunmi.scanner.inner",
                exampleParameters = """{}""",
                exampleOutput = """{"status":"scanning"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.stop",
                "Stop active barcode scan.",
                "sunmi.scanner.inner",
                exampleParameters = """{}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.sendCommand",
                "Send a raw configuration command to the scanner service.",
                "sunmi.scanner.inner",
                exampleParameters = """{"command":"{\"EXTRA_SCAN_POWER\":1}"}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.sendQuery",
                "Query scanner configuration. Returns typed response.",
                "sunmi.scanner.inner",
                exampleParameters = """{"query":"QUERY_ALL_SETTING_INFO"}""",
                exampleOutput = """{"status":"ok","type":"ServiceSetting","data":{}}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.sendKeyEvent",
                "Simulate a hardware key event on the scanner.",
                "sunmi.scanner.inner",
                exampleParameters = """{"action":0,"keyCode":0}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.setScannerModel",
                "Set the active scanner model identifier.",
                "sunmi.scanner.inner",
                exampleParameters = """{"model":100}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.getScannerModel",
                "Get the active scanner model identifier.",
                "sunmi.scanner.inner",
                exampleParameters = """{}""",
                exampleOutput = """{"status":"ok","model":100}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.switchSpecialScene",
                "Switch scanner to a special scene mode.",
                "sunmi.scanner.inner",
                exampleParameters = """{"scene":1}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.clearConfig",
                "Reset scanner configuration to defaults.",
                "sunmi.scanner.inner",
                exampleParameters = """{}""",
                exampleOutput = """{"status":"ok"}"""
            ),
            MethodDescriptor(
                "sunmi.scanner.inner.isServiceConnected",
                "Check if the scanner service is connected.",
                "sunmi.scanner.inner",
                exampleParameters = """{}""",
                exampleOutput = """{"status":"ok","connected":true}"""
            )
        ),
        events = listOf(
            EventDescriptor(
                EVENT_BARCODE,
                "Fired when a barcode is successfully scanned.",
                "sunmi.scanner.inner",
                exampleEvent = """{"data":"5901234123457","format":"EAN13"}"""
            ),
            EventDescriptor(
                EVENT_SERVICE_CONNECTED,
                "Fired when the scanner service becomes available.",
                "sunmi.scanner.inner",
                exampleEvent = """{}"""
            ),
            EventDescriptor(
                EVENT_SERVICE_DISCONNECTED,
                "Fired when the scanner service disconnects.",
                "sunmi.scanner.inner",
                exampleEvent = """{}"""
            )
        )
    )

    // --- Execute ---

    override suspend fun execute(method: String, params: String): String = mutex.withLock {
        val scanner = InnerScanner.getInstance()
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)

        return@withLock try {
            when (method) {
                "sunmi.scanner.inner.trigger" -> {
                    scanner.scan()
                    started()
                }

                "sunmi.scanner.inner.stop" -> {
                    scanner.stop()
                    success()
                }

                "sunmi.scanner.inner.sendCommand" -> {
                    val command = json.getString("command")
                    scanner.sendCommand(command)
                    success()
                }

                "sunmi.scanner.inner.sendQuery" -> {
                    val query = json.getString("query")
                    val deferred = CompletableDeferred<String>()
                    scanner.sendQuery(query, object : QueryCallback() {
                        override fun onSuccess(entity: Entity<*>?) {
                            deferred.complete(serializeEntity(entity))
                        }

                        override fun onFiled(errorCode: Int) {
                            deferred.complete(error("query_failed", "Query failed with code $errorCode"))
                        }
                    })
                    withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }
                }

                "sunmi.scanner.inner.sendKeyEvent" -> {
                    val action = json.optInt("action", KeyEvent.ACTION_DOWN)
                    val keyCode = json.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN)
                    scanner.sendKeyEvent(KeyEvent(action, keyCode))
                    success()
                }

                "sunmi.scanner.inner.setScannerModel" -> {
                    scanner.setScannerModel(json.getInt("model"))
                    success()
                }

                "sunmi.scanner.inner.getScannerModel" -> {
                    success("model", scanner.getScannerModel())
                }

                "sunmi.scanner.inner.switchSpecialScene" -> {
                    scanner.switchSpecialScene(json.getInt("scene"))
                    success()
                }

                "sunmi.scanner.inner.clearConfig" -> {
                    scanner.clearConfig()
                    success()
                }

                "sunmi.scanner.inner.isServiceConnected" -> {
                    success("connected", ScannerServiceManager.isConnected())
                }

                else -> error("unsupported_method", "Method not supported: $method")
            }
        } catch (e: Exception) {
            error("sdk_error", e.message ?: "Unknown SDK error")
        }
    }

    // --- Entity serialization ---

    private fun serializeEntity(entity: Entity<*>?): String {
        if (entity == null) return error("empty_response", "No data returned")

        val bean = entity.bean ?: return error("empty_response", "No data in entity")
        val result = JSONObject().put("status", "ok")

        when (bean) {
            is ServiceSetting -> {
                val data = JSONObject()
                data.put("outCodeCharSet", bean.mOutCodeCharSet)
                data.put("outBroadcast", bean.mOutBroadcast)
                data.put("outType", bean.mOutType)
                data.put("outCharInterval", bean.mOutCharInterval)
                data.put("prefix", bean.mPrefix)
                data.put("suffix", bean.mSuffix)
                data.put("prefixContext", bean.mPrefixContext)
                data.put("suffixContext", bean.mSuffixContext)
                data.put("advancedFormat", bean.mAdvancedFormat)
                data.put("triggerMethod", bean.mTriggerMethod)
                data.put("triggerTimeOut", bean.mTriggerTimeOut)
                data.put("continuousTime", bean.mContinuousTime)
                data.put("outCodeID", bean.mOutCodeID)
                data.put("centerFlagScan", bean.mCenterFlagScan)
                data.put("broadcastAction", bean.mBroadcastAction)
                data.put("dataKey", bean.mDataKey)
                data.put("byteKey", bean.mByteKey)
                data.put("startDecodeAction", bean.mStartDecodeAction)
                data.put("endDecodeAction", bean.mEndDecodeAction)
                data.put("prefixCount", bean.mPrefixCount)
                data.put("suffixCount", bean.mSuffixCount)
                data.put("removeGroupChar", bean.mRemoveGroupChar)
                result.put("type", "ServiceSetting")
                result.put("data", data)
            }

            is CodeEnable -> {
                val data = JSONObject()
                val codes = JSONArray()
                val enables = JSONArray()
                bean.codes?.forEachIndexed { i, code ->
                    codes.put(code)
                    enables.put(bean.enable?.getOrNull(i) ?: false)
                }
                data.put("codes", codes)
                data.put("enable", enables)
                result.put("type", "CodeEnable")
                result.put("data", data)
            }

            is CodeSetting -> {
                val data = JSONObject()
                data.put("minLen", bean.minLen)
                data.put("maxLen", bean.maxLen)
                data.put("checkCharType", bean.checkCharType)
                data.put("isStartEndType", bean.isStartEndType)
                data.put("startEndFormat", bean.startEndFormat)
                data.put("isExtendCode1", bean.isExtendCode1)
                data.put("isExtendCode2", bean.isExtendCode2)
                data.put("isSystemCharZero", bean.isSystemCharZero)
                data.put("isExtendToCode", bean.isExtendToCode)
                data.put("checkCharMode", bean.checkCharMode)
                data.put("doubleCode", bean.doubleCode)
                data.put("isMicroCode", bean.isMicroCode)
                data.put("inverseCode", bean.inverseCode)
                data.put("formatCode", bean.formatCode)
                result.put("type", "CodeSetting")
                result.put("data", data)
            }

            is Result -> {
                result.put("type", "Result")
                result.put("data", bean.result)
            }

            is Pair -> {
                val data = JSONObject()
                data.put("first", bean.first)
                data.put("second", bean.second)
                result.put("type", "Pair")
                result.put("data", data)
            }

            is java.util.ArrayList<*> -> {
                val arr = JSONArray()
                for (item in bean) {
                    if (item is Pair) {
                        arr.put(JSONObject().put("first", item.first).put("second", item.second))
                    }
                }
                result.put("type", "PairList")
                result.put("data", arr)
            }

            else -> {
                result.put("type", bean.javaClass.simpleName)
                result.put("data", bean.toString())
            }
        }

        return result.toString()
    }

    // --- Helpers ---

    private fun emitEvent(event: String, data: String) {
        eventCallback?.onEvent(event, data)
    }
}
