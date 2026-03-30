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
import dev.duma.android.hal.plugins.sunmi.scanner.common.compat.ScannerService
import dev.duma.android.hal.plugins.sunmi.scanner.common.compat.SunmiHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * HAL plugin wrapping the Sunmi InnerScanner SDK for built-in hardware barcode scanners.
 * Provides scan trigger, full configuration management, and query capabilities.
 *
 * Scan results are delivered as events; trigger/stop return immediately.
 * Configuration get/set methods use SunmiHelper to generate SDK commands.
 */
class SunmiInnerScannerPlugin(
    private val appContext: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.scanner.inner"
    override val version = 1

    private var eventCallback: HalPluginEventCallback? = null
    private val mutex = Mutex()
    private var connectionListener: ServiceConnectStatus? = null
    private var broadcastReceiver: ScannerBroadcastReceiver? = null
    private var beeper: Beeper? = null

    companion object {
        private const val CALLBACK_KEY = "hal_inner_scanner"
        private const val QUERY_TIMEOUT_MS = 5000L

        private const val EVENT_BARCODE = "sunmi.scanner.inner.barcode"
        private const val EVENT_SCAN_START = "sunmi.scanner.inner.scanStart"
        private const val EVENT_SCAN_STOP = "sunmi.scanner.inner.scanStop"
        private const val EVENT_SERVICE_CONNECTED = "sunmi.scanner.inner.serviceConnected"
        private const val EVENT_SERVICE_DISCONNECTED = "sunmi.scanner.inner.serviceDisconnected"
    }

    private val decodeCallback = object : DataCallback() {
        override fun onResult(data: String?, rawData: ByteArray?, format: String?) {
            val payload = JSONObject()
                .put("data", data ?: "")
                .put("format", format ?: "")
            if (rawData != null) {
                payload.put("rawData", android.util.Base64.encodeToString(rawData, android.util.Base64.NO_WRAP))
            }
            emitEvent(EVENT_BARCODE, payload.toString())
        }
    }

    // --- Lifecycle ---

    override fun isSupported(): Boolean = true

    override fun initialize(pluginContext: PluginContext) {
        val ctx = appContext ?: return

        beeper = Beeper(ctx)

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

        broadcastReceiver = ScannerBroadcastReceiver(
            onScan = { _, _ -> /* barcode already handled by decodeCallback */ },
            onScanStart = { emitEvent(EVENT_SCAN_START, "{}") },
            onScanStop = { emitEvent(EVENT_SCAN_STOP, "{}") }
        ).also { it.register(ctx) }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        eventCallback = callback
    }

    override fun getCapabilities() = listOf("sunmi.scanner.inner")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi: Scanner (Built In)",
        version = version,
        capabilities = getCapabilities(),
        methods = buildMethodList(),
        events = buildEventList()
    )

    // --- Execute ---

    override suspend fun execute(method: String, params: String): String = mutex.withLock {
        val scanner = InnerScanner.getInstance()
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)

        return@withLock try {
            when (method) {
                // --- Core scan control ---
                "sunmi.scanner.inner.trigger" -> { scanner.scan(); started() }
                "sunmi.scanner.inner.stop" -> { scanner.stop(); success() }
                "sunmi.scanner.inner.sendCommand" -> { scanner.sendCommand(json.getString("command")); success() }
                "sunmi.scanner.inner.sendQuery" -> querySetting(scanner, json.getString("query"))
                "sunmi.scanner.inner.sendKeyEvent" -> {
                    scanner.sendKeyEvent(KeyEvent(json.optInt("action", KeyEvent.ACTION_DOWN), json.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN)))
                    success()
                }
                "sunmi.scanner.inner.clearConfig" -> { scanner.clearConfig(); success() }
                "sunmi.scanner.inner.isServiceConnected" -> success("connected", ScannerServiceManager.isConnected())

                // --- Scanner model ---
                "sunmi.scanner.inner.getScannerModel" -> {
                    val id = scanner.getScannerModel()
                    JSONObject().put("status", "ok").put("id", id).put("name", ScannerService.scannerIdToName(id)).toString()
                }
                "sunmi.scanner.inner.setScannerModel" -> { scanner.setScannerModel(json.getInt("model")); success() }

                // --- Output configuration ---
                "sunmi.scanner.inner.getOutputType" -> queryServiceSetting { ss ->
                    JSONObject().put("status", "ok")
                        .put("mode", ss.mOutType)
                        .put("interval", ss.mOutCharInterval)
                        .put("tab", ss.mOutAutoAdd?.getOrNull(0)?.let { it == 1 })
                        .put("enter", ss.mOutAutoAdd?.getOrNull(1)?.let { it == 1 })
                        .put("asEvent", ss.mOutAutoAdd?.getOrNull(2)?.let { it == 1 })
                        .put("space", ss.mOutAutoAdd?.getOrNull(3)?.let { it == 1 })
                        .toString()
                }
                "sunmi.scanner.inner.setOutputType" -> {
                    scanner.sendCommand(SunmiHelper.setOutType(json.getInt("mode")))
                    val tab = if (json.has("tab")) (if (json.getBoolean("tab")) 1 else 0) else -1
                    val enter = if (json.has("enter")) (if (json.getBoolean("enter")) 1 else 0) else -1
                    val asEvent = if (json.has("asEvent")) (if (json.getBoolean("asEvent")) 1 else 0) else -1
                    val space = if (json.has("space")) (if (json.getBoolean("space")) 1 else 0) else -1
                    scanner.sendCommand(SunmiHelper.setOutAutoAdd(intArrayOf(tab, enter, asEvent, space)))
                    if (json.has("interval")) scanner.sendCommand(SunmiHelper.setOutCharInterval(json.getInt("interval")))
                    success()
                }
                "sunmi.scanner.inner.getOutputEncodingCode" -> queryServiceSetting { ss ->
                    success("encoding", ss.mOutCodeCharSet)
                }
                "sunmi.scanner.inner.setOutputEncodingCode" -> {
                    scanner.sendCommand(SunmiHelper.setOutCode(json.getInt("encoding")))
                    success()
                }
                "sunmi.scanner.inner.getScanResultCodeID" -> queryServiceSetting { ss ->
                    success("type", ss.mOutCodeID)
                }
                "sunmi.scanner.inner.setScanResultCodeID" -> {
                    scanner.sendCommand(SunmiHelper.setSetOutCodeID(json.getInt("type")))
                    success()
                }

                // --- Broadcast ---
                "sunmi.scanner.inner.isOutputBroadcastEnabled" -> queryServiceSetting { ss ->
                    success("enabled", ss.mOutBroadcast == 1)
                }
                "sunmi.scanner.inner.setOutputBroadcastEnabled" -> {
                    scanner.sendCommand(SunmiHelper.setOutBroadcast(if (json.getBoolean("enabled")) 1 else 0))
                    success()
                }
                "sunmi.scanner.inner.getBroadcastConfiguration" -> queryServiceSetting { ss ->
                    JSONObject().put("status", "ok")
                        .put("action", ss.mBroadcastAction)
                        .put("dataKey", ss.mDataKey)
                        .put("byteKey", ss.mByteKey)
                        .put("startAction", ss.mStartDecodeAction)
                        .put("endAction", ss.mEndDecodeAction)
                        .toString()
                }
                "sunmi.scanner.inner.setBroadcastConfiguration" -> {
                    if (json.has("action")) scanner.sendCommand(SunmiHelper.setOutBroadcastAction(json.getString("action")))
                    if (json.has("dataKey")) scanner.sendCommand(SunmiHelper.setOutBroadcastDataKey(json.getString("dataKey")))
                    if (json.has("byteKey")) scanner.sendCommand(SunmiHelper.setOutBroadcastByteKey(json.getString("byteKey")))
                    if (json.has("startAction")) scanner.sendCommand(SunmiHelper.setStartDecodeBroadcastAction(json.getString("startAction").ifEmpty { " " }))
                    if (json.has("endAction")) scanner.sendCommand(SunmiHelper.setEndDecodeBroadcastAction(json.getString("endAction").ifEmpty { " " }))
                    success()
                }

                // --- Trigger ---
                "sunmi.scanner.inner.getTriggerMethod" -> queryServiceSetting { ss ->
                    JSONObject().put("status", "ok")
                        .put("mode", ss.mTriggerMethod)
                        .put("timeout", ss.mTriggerTimeOut)
                        .put("sleep", ss.mContinuousTime)
                        .toString()
                }
                "sunmi.scanner.inner.setTriggerMethod" -> {
                    val mode = json.getInt("mode")
                    scanner.sendCommand(SunmiHelper.setTriggerMethod(mode))
                    scanner.sendCommand(SunmiHelper.setScanTriggerModel(mode))
                    if (json.has("timeout")) {
                        val timeout = json.getInt("timeout")
                        scanner.sendCommand(SunmiHelper.setScanTriggerTimeOut(timeout))
                        scanner.sendCommand(SunmiHelper.setTriggerOverTime(if (mode == 2) timeout else 5000))
                    }
                    if (json.has("sleep")) scanner.sendCommand(SunmiHelper.setTriggerContinuousTime(json.getInt("sleep")))
                    success()
                }
                "sunmi.scanner.inner.setTrigger" -> {
                    val enabled = json.optBoolean("enabled", true)
                    appContext?.sendBroadcast(android.content.Intent("com.sunmi.scanner.ACTION_TRIGGER_CONTROL").putExtra("enable", enabled))
                    success()
                }

                // --- Beep / Vibrate ---
                "sunmi.scanner.inner.isBeep" -> queryServiceSetting { ss ->
                    success("enabled", ss.mTips?.getOrNull(0)?.let { it == 1 } ?: true)
                }
                "sunmi.scanner.inner.setBeep" -> {
                    queryServiceSettingDirect { ss ->
                        val vibrate = ss.mTips?.getOrNull(1) ?: 1
                        scanner.sendCommand(SunmiHelper.setTips(intArrayOf(if (json.getBoolean("enabled")) 1 else 0, vibrate)))
                    }
                    success()
                }
                "sunmi.scanner.inner.isVibrate" -> queryServiceSetting { ss ->
                    success("enabled", ss.mTips?.getOrNull(1)?.let { it == 1 } ?: true)
                }
                "sunmi.scanner.inner.setVibrate" -> {
                    queryServiceSettingDirect { ss ->
                        val beepVal = ss.mTips?.getOrNull(0) ?: 1
                        scanner.sendCommand(SunmiHelper.setTips(intArrayOf(beepVal, if (json.getBoolean("enabled")) 1 else 0)))
                    }
                    success()
                }
                "sunmi.scanner.inner.beep" -> { beeper?.beep(); success() }
                "sunmi.scanner.inner.vibrate" -> { beeper?.vibrate(); success() }

                // --- Flash / Scene / Center / Virtual button ---
                "sunmi.scanner.inner.isFlash" -> queryResultSetting(SunmiHelper.SET_FLASH_CONTROL) { value ->
                    success("enabled", value == "1" || value == "true")
                }
                "sunmi.scanner.inner.setFlash" -> {
                    scanner.sendCommand(SunmiHelper.setFlashControl(if (json.getBoolean("enabled")) 1 else 0))
                    success()
                }
                "sunmi.scanner.inner.getCenterFlagScan" -> queryServiceSetting { ss ->
                    success("mode", ss.mCenterFlagScan)
                }
                "sunmi.scanner.inner.setCenterFlagScan" -> {
                    scanner.sendCommand(SunmiHelper.setCenterFlagScan(json.getInt("mode")))
                    success()
                }
                "sunmi.scanner.inner.getScene" -> queryResultSetting(SunmiHelper.SET_SCAN_SPECIFIC_SCENE) { value ->
                    success("scene", try { value.toInt() } catch (_: Exception) { 0 })
                }
                "sunmi.scanner.inner.setScene" -> {
                    scanner.sendCommand(SunmiHelper.setSetScanSpecificScene(json.getInt("scene")))
                    success()
                }
                "sunmi.scanner.inner.switchSpecialScene" -> {
                    scanner.switchSpecialScene(json.getInt("scene"))
                    success()
                }
                "sunmi.scanner.inner.isVirtualFloatingScanButton" -> queryServiceSetting { ss ->
                    success("enabled", ss.mTrigger?.getOrNull(0)?.let { it == 1 } ?: false)
                }
                "sunmi.scanner.inner.setVirtualFloatingScanButton" -> {
                    scanner.sendCommand(SunmiHelper.setScanTrigger(intArrayOf(if (json.getBoolean("enabled")) 1 else 0)))
                    success()
                }

                // --- Prefix / Suffix ---
                "sunmi.scanner.inner.getPrefix" -> queryServiceSetting { ss ->
                    JSONObject().put("status", "ok")
                        .put("content", if (ss.mPrefix == 1) ss.mPrefixContext else JSONObject.NULL)
                        .toString()
                }
                "sunmi.scanner.inner.setPrefix" -> {
                    val content = json.optString("content", "").ifEmpty { null }
                    scanner.sendCommand(SunmiHelper.setPrefix(if (content != null) 1 else 0))
                    scanner.sendCommand(SunmiHelper.setPrefixContext(content ?: ScannerService.FIX_NULL))
                    success()
                }
                "sunmi.scanner.inner.getSuffix" -> queryServiceSetting { ss ->
                    JSONObject().put("status", "ok")
                        .put("content", if (ss.mSuffix == 1) ss.mSuffixContext else JSONObject.NULL)
                        .toString()
                }
                "sunmi.scanner.inner.setSuffix" -> {
                    val content = json.optString("content", "").ifEmpty { null }
                    scanner.sendCommand(SunmiHelper.setSuffix(if (content != null) 1 else 0))
                    scanner.sendCommand(SunmiHelper.setSuffixContext(content ?: ScannerService.FIX_NULL))
                    success()
                }
                "sunmi.scanner.inner.getPrefixCharactersRemoved" -> queryServiceSetting { ss ->
                    success("length", ss.mPrefixCount)
                }
                "sunmi.scanner.inner.setPrefixCharactersRemoved" -> {
                    scanner.sendCommand(SunmiHelper.setPrefixCount(json.getInt("length")))
                    success()
                }
                "sunmi.scanner.inner.getSuffixCharactersRemoved" -> queryServiceSetting { ss ->
                    success("length", ss.mSuffixCount)
                }
                "sunmi.scanner.inner.setSuffixCharactersRemoved" -> {
                    scanner.sendCommand(SunmiHelper.setSuffixCount(json.getInt("length")))
                    success()
                }
                "sunmi.scanner.inner.isRemoveGroupSeparator" -> queryServiceSetting { ss ->
                    success("enabled", ss.mRemoveGroupChar == 1)
                }
                "sunmi.scanner.inner.setRemoveGroupSeparator" -> {
                    scanner.sendCommand(SunmiHelper.setRemoveGroupChar(if (json.getBoolean("enabled")) 1 else 0))
                    success()
                }

                // --- Advanced Formatting ---
                "sunmi.scanner.inner.isAdvancedFormatEnabled" -> queryServiceSetting { ss ->
                    success("enabled", ss.mAdvancedFormat == 1)
                }
                "sunmi.scanner.inner.setAdvancedFormatEnabled" -> {
                    scanner.sendCommand(SunmiHelper.setAdvancedFormat(if (json.getBoolean("enabled")) 1 else 0))
                    success()
                }
                "sunmi.scanner.inner.getAdvancedFormats" -> queryAdvancedFormats()
                "sunmi.scanner.inner.setAdvancedFormats" -> {
                    scanner.sendCommand(SunmiHelper.setAdvancedFormatClear(1))
                    val formats = json.getJSONObject("formats")
                    formats.keys().forEach { key ->
                        scanner.sendCommand(SunmiHelper.setAdvancedFormatAdd(arrayOf(key, formats.getString(key))))
                    }
                    success()
                }
                "sunmi.scanner.inner.addAdvancedFormat" -> {
                    scanner.sendCommand(SunmiHelper.setAdvancedFormatAdd(arrayOf(json.getString("search"), json.getString("replacement"))))
                    success()
                }
                "sunmi.scanner.inner.removeAdvancedFormat" -> {
                    scanner.sendCommand(SunmiHelper.setAdvancedFormatRemove(json.getString("search")))
                    success()
                }
                "sunmi.scanner.inner.clearAdvancedFormats" -> {
                    scanner.sendCommand(SunmiHelper.setAdvancedFormatClear(1))
                    success()
                }

                // --- Barcode symbologies ---
                "sunmi.scanner.inner.getBarcodesList" -> queryBarcodesList()
                "sunmi.scanner.inner.getBarcode" -> queryBarcode(json.getString("name"))
                "sunmi.scanner.inner.setBarcode" -> {
                    val cmd = SunmiHelper.setCodeEnable(json.getString("name"), json.getBoolean("enabled"))
                    if (cmd.isNullOrEmpty()) error("invalid_barcode", "Unknown barcode type: ${json.getString("name")}")
                    else { scanner.sendCommand(cmd); success() }
                }
                "sunmi.scanner.inner.getBarcodeConfig" -> queryBarcodeConfig(json.getString("name"))
                "sunmi.scanner.inner.setBarcodeConfig" -> setBarcodeConfig(scanner, json)

                else -> error("unsupported_method", "Method not supported: $method")
            }
        } catch (e: Exception) {
            error("sdk_error", e.message ?: "Unknown SDK error")
        }
    }

    // --- Query helpers ---

    private suspend fun querySetting(scanner: InnerScanner, query: String): String {
        val deferred = CompletableDeferred<String>()
        scanner.sendQuery(query, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                deferred.complete(serializeEntity(entity))
            }
            override fun onFiled(errorCode: Int) {
                deferred.complete(error("query_failed", "Query failed with code $errorCode"))
            }
        })
        return withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun queryServiceSetting(transform: (ServiceSetting) -> String): String {
        val deferred = CompletableDeferred<String>()
        InnerScanner.getInstance().sendQuery(SunmiHelper.QUERY_ALL_SETTING_INFO, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                val bean = entity?.bean
                if (bean is ServiceSetting) deferred.complete(transform(bean))
                else deferred.complete(error("unexpected_type", "Expected ServiceSetting"))
            }
            override fun onFiled(errorCode: Int) {
                deferred.complete(error("query_failed", "Query failed with code $errorCode"))
            }
        })
        return withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun queryServiceSettingDirect(action: (ServiceSetting) -> Unit) {
        val deferred = CompletableDeferred<ServiceSetting?>()
        InnerScanner.getInstance().sendQuery(SunmiHelper.QUERY_ALL_SETTING_INFO, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                deferred.complete(entity?.bean as? ServiceSetting)
            }
            override fun onFiled(errorCode: Int) {
                deferred.complete(null)
            }
        })
        withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }?.let(action)
    }

    private suspend fun queryResultSetting(queryString: String, transform: (String) -> String): String {
        val deferred = CompletableDeferred<String>()
        InnerScanner.getInstance().sendQuery(queryString, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                val bean = entity?.bean
                if (bean is Result) {
                    val raw = bean.result ?: ""
                    val value = raw.substringAfterLast("=", raw)
                    deferred.complete(transform(value))
                } else deferred.complete(error("unexpected_type", "Expected Result"))
            }
            override fun onFiled(errorCode: Int) {
                deferred.complete(error("query_failed", "Query failed with code $errorCode"))
            }
        })
        return withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun queryAdvancedFormats(): String {
        val deferred = CompletableDeferred<String>()
        InnerScanner.getInstance().sendQuery(SunmiHelper.QUERY_ADVANCED_FORMAT, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                val bean = entity?.bean
                val result = JSONObject().put("status", "ok")
                val formats = JSONObject()
                if (bean is java.util.ArrayList<*>) {
                    for (item in bean) {
                        if (item is Pair) formats.put(item.first ?: "", item.second ?: "")
                    }
                }
                result.put("formats", formats)
                deferred.complete(result.toString())
            }
            override fun onFiled(errorCode: Int) {
                deferred.complete(error("query_failed", "Query failed with code $errorCode"))
            }
        })
        return withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun queryBarcodesList(): String {
        val deferred = CompletableDeferred<String>()
        InnerScanner.getInstance().sendQuery(SunmiHelper.QUERY_ALL_ENABLE_CODE, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                val bean = entity?.bean
                if (bean is CodeEnable) {
                    val result = JSONObject().put("status", "ok")
                    val barcodes = JSONArray()
                    bean.codes?.forEachIndexed { i, code ->
                        barcodes.put(JSONObject().put("name", code).put("enabled", bean.enable?.getOrNull(i) ?: false))
                    }
                    result.put("barcodes", barcodes)
                    deferred.complete(result.toString())
                } else deferred.complete(error("unexpected_type", "Expected CodeEnable"))
            }
            override fun onFiled(errorCode: Int) {
                deferred.complete(error("query_failed", "Query failed with code $errorCode"))
            }
        })
        return withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun queryBarcode(name: String): String {
        val deferred = CompletableDeferred<String>()
        InnerScanner.getInstance().sendQuery(SunmiHelper.QUERY_ALL_ENABLE_CODE, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                val bean = entity?.bean
                if (bean is CodeEnable) {
                    val idx = bean.codes?.indexOf(name) ?: -1
                    if (idx >= 0) {
                        deferred.complete(JSONObject().put("status", "ok").put("name", name).put("enabled", bean.enable?.getOrNull(idx) ?: false).toString())
                    } else deferred.complete(error("not_found", "Barcode type not found: $name"))
                } else deferred.complete(error("unexpected_type", "Expected CodeEnable"))
            }
            override fun onFiled(errorCode: Int) {
                deferred.complete(error("query_failed", "Query failed with code $errorCode"))
            }
        })
        return withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun queryBarcodeConfig(name: String): String {
        val queryCmd = SunmiHelper.queryCodeSetting(name)
        if (queryCmd.isNullOrEmpty()) return error("invalid_barcode", "Unknown barcode type: $name")

        val deferred = CompletableDeferred<String>()
        InnerScanner.getInstance().sendQuery(queryCmd, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                val bean = entity?.bean
                if (bean is CodeSetting) {
                    deferred.complete(JSONObject().put("status", "ok").put("name", name)
                        .put("minLen", bean.minLen).put("maxLen", bean.maxLen)
                        .put("checkCharType", bean.checkCharType).put("checkCharMode", bean.checkCharMode)
                        .put("isStartEndType", bean.isStartEndType).put("startEndFormat", bean.startEndFormat)
                        .put("isExtendCode1", bean.isExtendCode1).put("isExtendCode2", bean.isExtendCode2)
                        .put("isSystemCharZero", bean.isSystemCharZero).put("isExtendToCode", bean.isExtendToCode)
                        .put("doubleCode", bean.doubleCode).put("isMicroCode", bean.isMicroCode)
                        .put("inverseCode", bean.inverseCode).put("formatCode", bean.formatCode)
                        .toString())
                } else deferred.complete(error("unexpected_type", "Expected CodeSetting"))
            }
            override fun onFiled(errorCode: Int) {
                deferred.complete(error("query_failed", "Query failed with code $errorCode"))
            }
        })
        return withTimeout(QUERY_TIMEOUT_MS) { deferred.await() }
    }

    private fun setBarcodeConfig(scanner: InnerScanner, json: JSONObject): String {
        val name = json.getString("name")
        if (json.has("minLen") || json.has("maxLen")) {
            val minLen = json.optInt("minLen", 0)
            val maxLen = json.optInt("maxLen", 255)
            scanner.sendCommand(SunmiHelper.setCodeReadRange(name, intArrayOf(minLen, maxLen)))
        }
        if (json.has("checkCharType")) scanner.sendCommand(SunmiHelper.setCodeCheckCharType(name, json.getInt("checkCharType")))
        if (json.has("checkCharMode")) scanner.sendCommand(SunmiHelper.setCodeCheckMode(name, json.getInt("checkCharMode")))
        if (json.has("isStartEndType")) scanner.sendCommand(SunmiHelper.setCodeStartEndType(name, json.getBoolean("isStartEndType")))
        if (json.has("startEndFormat")) scanner.sendCommand(SunmiHelper.setCodeStartEndFormat(name, json.getInt("startEndFormat")))
        if (json.has("isExtendCode1")) scanner.sendCommand(SunmiHelper.setCodeExtendRead1(name, json.getBoolean("isExtendCode1")))
        if (json.has("isExtendCode2")) scanner.sendCommand(SunmiHelper.setCodeExtendRead2(name, json.getBoolean("isExtendCode2")))
        if (json.has("isSystemCharZero")) scanner.sendCommand(SunmiHelper.setCodeSystemCharZero(name, json.getBoolean("isSystemCharZero")))
        if (json.has("isExtendToCode")) scanner.sendCommand(SunmiHelper.setCodeExtendToCode(name, json.getBoolean("isExtendToCode")))
        if (json.has("doubleCode")) scanner.sendCommand(SunmiHelper.setCodeReadDouble(name, json.getInt("doubleCode")))
        if (json.has("isMicroCode")) scanner.sendCommand(SunmiHelper.setCodeReadMicro(name, json.getBoolean("isMicroCode")))
        if (json.has("inverseCode")) scanner.sendCommand(SunmiHelper.setCodeReadInverse(name, json.getInt("inverseCode")))
        if (json.has("formatCode")) scanner.sendCommand(SunmiHelper.setCodeFormatMode(name, json.getInt("formatCode")))
        return success()
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
                result.put("type", "ServiceSetting").put("data", data)
            }
            is CodeEnable -> {
                val data = JSONObject()
                val codes = JSONArray(); val enables = JSONArray()
                bean.codes?.forEachIndexed { i, code -> codes.put(code); enables.put(bean.enable?.getOrNull(i) ?: false) }
                data.put("codes", codes).put("enable", enables)
                result.put("type", "CodeEnable").put("data", data)
            }
            is CodeSetting -> {
                val data = JSONObject()
                data.put("minLen", bean.minLen).put("maxLen", bean.maxLen)
                data.put("checkCharType", bean.checkCharType).put("isStartEndType", bean.isStartEndType)
                data.put("startEndFormat", bean.startEndFormat).put("isExtendCode1", bean.isExtendCode1)
                data.put("isExtendCode2", bean.isExtendCode2).put("isSystemCharZero", bean.isSystemCharZero)
                data.put("isExtendToCode", bean.isExtendToCode).put("checkCharMode", bean.checkCharMode)
                data.put("doubleCode", bean.doubleCode).put("isMicroCode", bean.isMicroCode)
                data.put("inverseCode", bean.inverseCode).put("formatCode", bean.formatCode)
                result.put("type", "CodeSetting").put("data", data)
            }
            is Result -> result.put("type", "Result").put("data", bean.result)
            is Pair -> result.put("type", "Pair").put("data", JSONObject().put("first", bean.first).put("second", bean.second))
            is java.util.ArrayList<*> -> {
                val arr = JSONArray()
                for (item in bean) { if (item is Pair) arr.put(JSONObject().put("first", item.first).put("second", item.second)) }
                result.put("type", "PairList").put("data", arr)
            }
            else -> result.put("type", bean.javaClass.simpleName).put("data", bean.toString())
        }
        return result.toString()
    }

    // --- Helpers ---

    private fun emitEvent(event: String, data: String) {
        eventCallback?.onEvent(event, data)
    }

    // --- Descriptor builders ---

    private fun buildMethodList() = listOf(
        MethodDescriptor("sunmi.scanner.inner.trigger", "Start barcode scan. Result delivered via barcode event.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"scanning"}"""),
        MethodDescriptor("sunmi.scanner.inner.stop", "Stop active barcode scan.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.sendCommand", "Send a raw configuration command to the scanner service.", "sunmi.scanner.inner", exampleParameters = """{"command":"{\"EXTRA_SCAN_POWER\":1}"}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.sendQuery", "Query scanner configuration. Returns typed response.", "sunmi.scanner.inner", exampleParameters = """{"query":"sunmi001000"}""", exampleOutput = """{"status":"ok","type":"ServiceSetting","data":{}}"""),
        MethodDescriptor("sunmi.scanner.inner.sendKeyEvent", "Simulate a hardware key event on the scanner.", "sunmi.scanner.inner", exampleParameters = """{"action":0,"keyCode":0}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.clearConfig", "Reset scanner configuration to defaults.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.isServiceConnected", "Check if the scanner service is connected.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","connected":true}"""),
        MethodDescriptor("sunmi.scanner.inner.getScannerModel", "Get scanner model identifier and name.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","id":101,"name":"SUPER_N1365_Y1825"}"""),
        MethodDescriptor("sunmi.scanner.inner.setScannerModel", "Set the active scanner model identifier.", "sunmi.scanner.inner", exampleParameters = """{"model":100}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getOutputType", "Get output mode and related options.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","mode":2,"interval":0,"tab":false,"enter":true,"space":false}"""),
        MethodDescriptor("sunmi.scanner.inner.setOutputType", "Set output mode and options.", "sunmi.scanner.inner", exampleParameters = """{"mode":2,"enter":true,"tab":false}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getOutputEncodingCode", "Get output character encoding.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","encoding":0}"""),
        MethodDescriptor("sunmi.scanner.inner.setOutputEncodingCode", "Set output character encoding.", "sunmi.scanner.inner", exampleParameters = """{"encoding":0}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getScanResultCodeID", "Get scan result code ID variant.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","type":0}"""),
        MethodDescriptor("sunmi.scanner.inner.setScanResultCodeID", "Set scan result code ID variant.", "sunmi.scanner.inner", exampleParameters = """{"type":1}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.isOutputBroadcastEnabled", "Check if output broadcast is enabled.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","enabled":true}"""),
        MethodDescriptor("sunmi.scanner.inner.setOutputBroadcastEnabled", "Enable or disable output broadcast.", "sunmi.scanner.inner", exampleParameters = """{"enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getBroadcastConfiguration", "Get broadcast action and key configuration.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","action":"com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED","dataKey":"data","byteKey":"source_byte"}"""),
        MethodDescriptor("sunmi.scanner.inner.setBroadcastConfiguration", "Set broadcast action and key configuration.", "sunmi.scanner.inner", exampleParameters = """{"action":"com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED","dataKey":"data"}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getTriggerMethod", "Get trigger mode, timeout, and sleep interval.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","mode":0,"timeout":5000,"sleep":500}"""),
        MethodDescriptor("sunmi.scanner.inner.setTriggerMethod", "Set trigger mode, timeout, and sleep interval.", "sunmi.scanner.inner", exampleParameters = """{"mode":0,"timeout":5000}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.setTrigger", "Enable or disable the physical trigger button.", "sunmi.scanner.inner", exampleParameters = """{"enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.isBeep", "Check if beep on scan is enabled.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","enabled":true}"""),
        MethodDescriptor("sunmi.scanner.inner.setBeep", "Enable or disable beep on scan.", "sunmi.scanner.inner", exampleParameters = """{"enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.isVibrate", "Check if vibration on scan is enabled.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","enabled":true}"""),
        MethodDescriptor("sunmi.scanner.inner.setVibrate", "Enable or disable vibration on scan.", "sunmi.scanner.inner", exampleParameters = """{"enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.beep", "Play a beep sound.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.vibrate", "Trigger a short vibration.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.isFlash", "Check if illumination flash is enabled.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","enabled":true}"""),
        MethodDescriptor("sunmi.scanner.inner.setFlash", "Enable or disable illumination flash.", "sunmi.scanner.inner", exampleParameters = """{"enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getCenterFlagScan", "Get center decoding mode (0=disabled, 1=centerOnly, 2=centerFirst).", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","mode":0}"""),
        MethodDescriptor("sunmi.scanner.inner.setCenterFlagScan", "Set center decoding mode.", "sunmi.scanner.inner", exampleParameters = """{"mode":1}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getScene", "Get active specific scene optimization.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","scene":0}"""),
        MethodDescriptor("sunmi.scanner.inner.setScene", "Set specific scene optimization.", "sunmi.scanner.inner", exampleParameters = """{"scene":1}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.switchSpecialScene", "Switch scanner to a special scene mode via SDK.", "sunmi.scanner.inner", exampleParameters = """{"scene":1}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.isVirtualFloatingScanButton", "Check if the virtual floating scan button is visible.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","enabled":false}"""),
        MethodDescriptor("sunmi.scanner.inner.setVirtualFloatingScanButton", "Show or hide the virtual floating scan button.", "sunmi.scanner.inner", exampleParameters = """{"enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getPrefix", "Get the current scan result prefix.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","content":"PRE-"}"""),
        MethodDescriptor("sunmi.scanner.inner.setPrefix", "Set or clear the scan result prefix.", "sunmi.scanner.inner", exampleParameters = """{"content":"PRE-"}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getSuffix", "Get the current scan result suffix.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","content":"-SUF"}"""),
        MethodDescriptor("sunmi.scanner.inner.setSuffix", "Set or clear the scan result suffix.", "sunmi.scanner.inner", exampleParameters = """{"content":"-SUF"}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getPrefixCharactersRemoved", "Get number of prefix characters removed from scan result.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","length":0}"""),
        MethodDescriptor("sunmi.scanner.inner.setPrefixCharactersRemoved", "Set number of prefix characters to remove (0-20).", "sunmi.scanner.inner", exampleParameters = """{"length":2}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getSuffixCharactersRemoved", "Get number of suffix characters removed from scan result.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","length":0}"""),
        MethodDescriptor("sunmi.scanner.inner.setSuffixCharactersRemoved", "Set number of suffix characters to remove (0-20).", "sunmi.scanner.inner", exampleParameters = """{"length":2}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.isRemoveGroupSeparator", "Check if group separator removal is enabled.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","enabled":false}"""),
        MethodDescriptor("sunmi.scanner.inner.setRemoveGroupSeparator", "Enable or disable group separator removal.", "sunmi.scanner.inner", exampleParameters = """{"enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.isAdvancedFormatEnabled", "Check if advanced formatting is enabled.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","enabled":false}"""),
        MethodDescriptor("sunmi.scanner.inner.setAdvancedFormatEnabled", "Enable or disable advanced formatting.", "sunmi.scanner.inner", exampleParameters = """{"enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getAdvancedFormats", "Get all advanced format search/replace pairs.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","formats":{"search1":"replace1"}}"""),
        MethodDescriptor("sunmi.scanner.inner.setAdvancedFormats", "Replace all advanced format pairs.", "sunmi.scanner.inner", exampleParameters = """{"formats":{"old":"new"}}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.addAdvancedFormat", "Add an advanced format search/replace pair.", "sunmi.scanner.inner", exampleParameters = """{"search":"old","replacement":"new"}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.removeAdvancedFormat", "Remove an advanced format pair by search key.", "sunmi.scanner.inner", exampleParameters = """{"search":"old"}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.clearAdvancedFormats", "Remove all advanced format pairs.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getBarcodesList", "Get all barcode symbologies with enabled/disabled status.", "sunmi.scanner.inner", exampleParameters = """{}""", exampleOutput = """{"status":"ok","barcodes":[{"name":"QR Code","enabled":true}]}"""),
        MethodDescriptor("sunmi.scanner.inner.getBarcode", "Get enabled/disabled status of a specific barcode type.", "sunmi.scanner.inner", exampleParameters = """{"name":"QR Code"}""", exampleOutput = """{"status":"ok","name":"QR Code","enabled":true}"""),
        MethodDescriptor("sunmi.scanner.inner.setBarcode", "Enable or disable a specific barcode type.", "sunmi.scanner.inner", exampleParameters = """{"name":"QR Code","enabled":true}""", exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.scanner.inner.getBarcodeConfig", "Get detailed configuration for a barcode symbology.", "sunmi.scanner.inner", exampleParameters = """{"name":"Code 128"}""", exampleOutput = """{"status":"ok","name":"Code 128","minLen":0,"maxLen":255}"""),
        MethodDescriptor("sunmi.scanner.inner.setBarcodeConfig", "Set configuration fields for a barcode symbology.", "sunmi.scanner.inner", exampleParameters = """{"name":"Code 128","minLen":4,"maxLen":40}""", exampleOutput = """{"status":"ok"}"""),
    )

    private fun buildEventList() = listOf(
        EventDescriptor(EVENT_BARCODE, "Fired when a barcode is successfully scanned.", "sunmi.scanner.inner", exampleEvent = """{"data":"5901234123457","format":"EAN13"}"""),
        EventDescriptor(EVENT_SCAN_START, "Fired when scanning starts.", "sunmi.scanner.inner", exampleEvent = """{}"""),
        EventDescriptor(EVENT_SCAN_STOP, "Fired when scanning stops.", "sunmi.scanner.inner", exampleEvent = """{}"""),
        EventDescriptor(EVENT_SERVICE_CONNECTED, "Fired when the scanner service becomes available.", "sunmi.scanner.inner", exampleEvent = """{}"""),
        EventDescriptor(EVENT_SERVICE_DISCONNECTED, "Fired when the scanner service disconnects.", "sunmi.scanner.inner", exampleEvent = """{}"""),
    )
}
