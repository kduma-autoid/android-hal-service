package dev.duma.android.hal.plugins.sunmi.rfid

import android.content.Context
import com.sunmi.rfid.RFIDManager
import com.sunmi.rfid.ReaderCall
import com.sunmi.rfid.constant.CMD
import com.sunmi.rfid.constant.ParamCts
import com.sunmi.rfid.entity.DataParameter
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.rfid.receiver.RfidBroadcastReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * HAL plugin wrapping the Sunmi RFID SDK (SunmiScannerSdk).
 * Controls the UHF RFID module on Sunmi L2k-UHF.
 *
 * Hybrid sync/async model:
 * - Inventory/streaming methods return {"status":"started"} and deliver results via events
 * - All other methods wait for the SDK callback and return the result directly
 */
class SunmiRfidPlugin(
    private val context: Context? = null
) : HalPlugin {

    override val pluginId = "sunmi.rfid"
    override val version = 1

    private var eventCallback: HalPluginEventCallback? = null
    private val mutex = Mutex()
    private val pendingOps = ConcurrentHashMap<Byte, CompletableDeferred<String>>()

    private val broadcastReceiver by lazy {
        RfidBroadcastReceiver(::emitEvent)
    }

    private val readerCall = object : ReaderCall() {

        override fun onSuccess(cmd: Byte, params: DataParameter?) {
            val payload = buildSuccessPayload(cmd, params)
            val deferred = pendingOps.remove(cmd)
            if (deferred != null) {
                deferred.complete(payload)
            } else {
                emitEvent(EVENT_OPERATION_SUCCESS, payload)
            }
        }

        override fun onTag(cmd: Byte, state: Byte, tag: DataParameter?) {
            val isNew = state == ParamCts.FOUND_TAG
            val payload = buildTagPayload(cmd, isNew, tag)
            emitEvent(EVENT_TAG_FOUND, payload)
        }

        override fun onFailed(cmd: Byte, errorCode: Byte, msg: String?) {
            val payload = JSONObject()
                .put("cmd", cmd.toInt() and 0xFF)
                .put("errorCode", errorCode.toInt() and 0xFF)
                .put("message", msg ?: "Unknown error")
                .toString()
            val deferred = pendingOps.remove(cmd)
            if (deferred != null) {
                deferred.complete(payload)
            } else {
                emitEvent(EVENT_OPERATION_ERROR, payload)
            }
        }
    }

    // --- Lifecycle ---

    override fun isSupported(): Boolean = true

    override fun initialize(pluginContext: PluginContext) {
        this.context?.let { ctx ->
            RFIDManager.getInstance().setPrintLog(false)
            RFIDManager.getInstance().connect(ctx)
            RFIDManager.getInstance().getHelper()?.registerReaderCall(readerCall)
            ctx.registerReceiver(broadcastReceiver, RfidBroadcastReceiver.buildIntentFilter())
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        eventCallback = callback
    }

    override fun getCapabilities() = listOf("sunmi.rfid")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi RFID UHF",
        version = version,
        capabilities = getCapabilities(),
        methods = buildMethodList(),
        events = buildEventList()
    )

    // --- Execute ---

    override suspend fun execute(method: String, params: String): String = mutex.withLock {
        val helper = RFIDManager.getInstance().getHelper()
            ?: return@withLock error("device_not_ready", "RFID helper not available")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return@withLock try {
            when (method) {

                // === Fully synchronous ===
                "sunmi.rfid.getScanModel" -> success(helper.getScanModel())

                // === Async (inventory/streaming) ===
                "sunmi.rfid.inventory" -> {
                    helper.inventory(json.optInt("btRepeat", 0xFF).toByte())
                    started()
                }
                "sunmi.rfid.realTimeInventory" -> {
                    helper.realTimeInventory(json.optInt("btRepeat", 0xFF).toByte())
                    started()
                }
                "sunmi.rfid.customizedSessionTargetInventory" -> {
                    helper.customizedSessionTargetInventory(
                        json.getInt("btSession").toByte(),
                        json.getInt("btTarget").toByte(),
                        json.getInt("btSL").toByte(),
                        json.getInt("btPhase").toByte(),
                        json.getInt("btPowerSave").toByte(),
                        json.getInt("btRepeat").toByte()
                    )
                    started()
                }
                "sunmi.rfid.fastSwitchAntInventory" -> {
                    helper.fastSwitchAntInventory(
                        json.getInt("btA").toByte(),
                        json.getInt("btStayA").toByte(),
                        json.getInt("btB").toByte(),
                        json.getInt("btStayB").toByte(),
                        json.getInt("btC").toByte(),
                        json.getInt("btStayC").toByte(),
                        json.getInt("btD").toByte(),
                        json.getInt("btStayD").toByte(),
                        json.getInt("btInterval").toByte(),
                        json.getInt("btRepeat").toByte()
                    )
                    started()
                }
                "sunmi.rfid.realTimeInventoryWithTid" -> {
                    helper.realTimeInventoryWithTid(
                        json.getInt("scanTime"),
                        json.getInt("btTidLen").toByte(),
                        json.getInt("btTarget").toByte(),
                        json.getInt("btScan").toByte(),
                        hexStringToBytes(json.optString("btAryEpc", ""))
                    )
                    started()
                }
                "sunmi.rfid.iso180006BInventory" -> {
                    helper.iso180006BInventory()
                    started()
                }
                "sunmi.rfid.getInventoryBuffer" -> {
                    helper.getInventoryBuffer()
                    started()
                }
                "sunmi.rfid.getAndResetInventoryBuffer" -> {
                    helper.getAndResetInventoryBuffer()
                    started()
                }

                // === Sync (await callback) — 6C Tag Operations ===
                "sunmi.rfid.readTag" -> awaitResult(CMD.READ_TAG) {
                    helper.readTag(
                        json.getInt("btMemBank").toByte(),
                        json.getInt("btWordAdd").toByte(),
                        json.getInt("btWordCnt").toByte(),
                        hexStringToBytes(json.getString("btAryPassWord"))
                    )
                }
                "sunmi.rfid.writeTag" -> awaitResult(CMD.WRITE_TAG) {
                    helper.writeTag(
                        hexStringToBytes(json.getString("btAryPassWord")),
                        json.getInt("btMemBank").toByte(),
                        json.getInt("btWordAdd").toByte(),
                        json.getInt("btWordCnt").toByte(),
                        hexStringToBytes(json.getString("btAryData"))
                    )
                }
                "sunmi.rfid.lockTag" -> awaitResult(CMD.LOCK_TAG) {
                    helper.lockTag(
                        hexStringToBytes(json.getString("btAryPassWord")),
                        json.getInt("btMemBank").toByte(),
                        json.getInt("btLockType").toByte()
                    )
                }
                "sunmi.rfid.killTag" -> awaitResult(CMD.KILL_TAG) {
                    helper.killTag(hexStringToBytes(json.getString("btAryPassWord")))
                }
                "sunmi.rfid.setAccessEpcMatch" -> awaitResult(CMD.SET_ACCESS_EPC_MATCH) {
                    val epcBytes = hexStringToBytes(json.getString("btAryEpc"))
                    helper.setAccessEpcMatch(epcBytes.size.toByte(), epcBytes)
                }
                "sunmi.rfid.cancelAccessEpcMatch" -> awaitResult(CMD.SET_ACCESS_EPC_MATCH) {
                    helper.cancelAccessEpcMatch()
                }
                "sunmi.rfid.getAccessEpcMatch" -> awaitResult(CMD.GET_ACCESS_EPC_MATCH) {
                    helper.getAccessEpcMatch()
                }
                "sunmi.rfid.setImpinjFastTid" -> awaitResult(CMD.SET_IMPINJ_FAST_TID) {
                    helper.setImpinjFastTid(
                        json.getBoolean("blnOpen"),
                        json.getBoolean("blnSave")
                    )
                }
                "sunmi.rfid.getImpinjFastTid" -> awaitResult(CMD.GET_IMPINJ_FAST_TID) {
                    helper.getImpinjFastTid()
                }

                // === Sync — 6B Tag Operations ===
                "sunmi.rfid.iso180006BReadTag" -> awaitResult(CMD.ISO18000_6B_READ_TAG) {
                    helper.iso180006BReadTag(
                        hexStringToBytes(json.getString("btAryUID")),
                        json.getInt("btWordAdd").toByte(),
                        json.getInt("btWordCnt").toByte()
                    )
                }
                "sunmi.rfid.iso180006BWriteTag" -> awaitResult(CMD.ISO18000_6B_WRITE_TAG) {
                    helper.iso180006BWriteTag(
                        hexStringToBytes(json.getString("btAryUID")),
                        json.getInt("btWordAdd").toByte(),
                        json.getInt("btWordCnt").toByte(),
                        hexStringToBytes(json.getString("btAryBuffer"))
                    )
                }
                "sunmi.rfid.iso180006BLockTag" -> awaitResult(CMD.ISO18000_6B_LOCK_TAG) {
                    helper.iso180006BLockTag(
                        hexStringToBytes(json.getString("btAryUID")),
                        json.getInt("btWordAdd").toByte()
                    )
                }
                "sunmi.rfid.iso180006BQueryLockTag" -> awaitResult(CMD.ISO18000_6B_QUERY_LOCK_TAG) {
                    helper.iso180006BQueryLockTag(
                        hexStringToBytes(json.getString("btAryUID")),
                        json.getInt("btWordAdd").toByte()
                    )
                }

                // === Sync — Buffer Operations ===
                "sunmi.rfid.getInventoryBufferTagCount" -> awaitResult(CMD.GET_INVENTORY_BUFFER_TAG_COUNT) {
                    helper.getInventoryBufferTagCount()
                }
                "sunmi.rfid.resetInventoryBuffer" -> awaitResult(CMD.RESET_INVENTORY_BUFFER) {
                    helper.resetInventoryBuffer()
                }

                // === Sync — Antenna ===
                "sunmi.rfid.setWorkAntenna" -> awaitResult(CMD.SET_WORK_ANTENNA) {
                    helper.setWorkAntenna(json.getInt("btAntId").toByte())
                }
                "sunmi.rfid.getWorkAntenna" -> awaitResult(CMD.GET_WORK_ANTENNA) {
                    helper.getWorkAntenna()
                }

                // === Sync — Output Power ===
                "sunmi.rfid.setOutputAllPower" -> awaitResult(CMD.SET_OUTPUT_POWER) {
                    helper.setOutputAllPower(json.getInt("btOutputPower").toByte())
                }
                "sunmi.rfid.setOutputPower" -> awaitResult(CMD.SET_OUTPUT_POWER) {
                    helper.setOutputPower(
                        json.getInt("btPower1").toByte(),
                        json.getInt("btPower2").toByte(),
                        json.getInt("btPower3").toByte(),
                        json.getInt("btPower4").toByte()
                    )
                }
                "sunmi.rfid.getOutputPower" -> awaitResult(CMD.GET_OUTPUT_POWER) {
                    helper.getOutputPower()
                }
                "sunmi.rfid.setTemporaryOutputPower" -> awaitResult(CMD.SET_TEMPORARY_OUTPUT_POWER) {
                    helper.setTemporaryOutputPower(json.getInt("btOutputPower").toByte())
                }

                // === Sync — Frequency ===
                "sunmi.rfid.setFrequencyRegion" -> awaitResult(CMD.SET_FREQUENCY_REGION) {
                    helper.setFrequencyRegion(
                        json.getInt("btRegion").toByte(),
                        json.getInt("btStart").toByte(),
                        json.getInt("btEnd").toByte()
                    )
                }
                "sunmi.rfid.setUserDefineFrequency" -> awaitResult(CMD.SET_FREQUENCY_REGION) {
                    helper.setUserDefineFrequency(
                        json.getInt("btQuantity").toByte(),
                        json.getInt("btFreqInterval").toByte(),
                        json.getInt("nStartFreq")
                    )
                }
                "sunmi.rfid.getFrequencyRegion" -> awaitResult(CMD.GET_FREQUENCY_REGION) {
                    helper.getFrequencyRegion()
                }
                "sunmi.rfid.setFixedFrequency" -> awaitResult(CMD.SET_FREQUENCY_REGION) {
                    helper.setFixedFrequency()
                }

                // === Sync — Beeper ===
                "sunmi.rfid.setBeeperMode" -> awaitResult(CMD.SET_BEEPER_MODE) {
                    helper.setBeeperMode(json.getInt("btMode").toByte())
                }
                "sunmi.rfid.getBeeperMode" -> awaitResult(CMD.GET_BEEP_MODE) {
                    helper.getBeeperMode()
                }

                // === Sync — RF Link Profile ===
                "sunmi.rfid.setRfLinkProfile" -> awaitResult(CMD.SET_RF_LINK_PROFILE) {
                    helper.setRfLinkProfile(json.getInt("btProfile").toByte())
                }
                "sunmi.rfid.getRfLinkProfile" -> awaitResult(CMD.GET_RF_LINK_PROFILE) {
                    helper.getRfLinkProfile()
                }
                "sunmi.rfid.getRfPortReturnLoss" -> awaitResult(CMD.GET_RF_PORT_RETURN_LOSS) {
                    helper.getRfPortReturnLoss(json.getInt("btFreq").toByte())
                }

                // === Sync — Antenna Connection Detector ===
                "sunmi.rfid.setAntConnectionDetector" -> awaitResult(CMD.SET_ANT_CONNECTION_DETECTOR) {
                    helper.setAntConnectionDetector(json.getInt("btPower").toByte())
                }
                "sunmi.rfid.getAntConnectionDetector" -> awaitResult(CMD.GET_ANT_CONNECTION_DETECTOR) {
                    helper.getAntConnectionDetector()
                }

                // === Sync — Reader Identity ===
                "sunmi.rfid.setReaderIdentifier" -> awaitResult(CMD.SET_READER_IDENTIFIER) {
                    helper.setReaderIdentifier(hexStringToBytes(json.getString("btAryIdentifier")))
                }
                "sunmi.rfid.getReaderIdentifier" -> awaitResult(CMD.GET_READER_IDENTIFIER) {
                    helper.getReaderIdentifier()
                }
                "sunmi.rfid.getReaderSN" -> awaitResult(CMD.GET_READER_SN) {
                    helper.getReaderSN()
                }
                "sunmi.rfid.getReaderCustomSN" -> awaitResult(CMD.GET_READER_SN) {
                    helper.getReaderCustomSN(json.getInt("btMode").toByte())
                }
                "sunmi.rfid.getReaderVersion" -> awaitResult(CMD.GET_READER_VERSION) {
                    helper.getReaderVersion()
                }
                "sunmi.rfid.getFirmwareVersion" -> awaitResult(CMD.GET_FIRMWARE_VERSION) {
                    helper.getFirmwareVersion()
                }

                // === Sync — Temperature ===
                "sunmi.rfid.getReaderTemperature" -> awaitResult(CMD.GET_READER_TEMPERATURE) {
                    helper.getReaderTemperature()
                }

                // === Sync — Battery ===
                "sunmi.rfid.getBatteryRemainingPercent" -> awaitResult(CMD.GET_READER_LOWELEC) {
                    helper.getBatteryRemainingPercent()
                }
                "sunmi.rfid.getBatteryVoltage" -> awaitResult(CMD.GET_READER_VOL) {
                    helper.getBatteryVoltage()
                }
                "sunmi.rfid.getBatteryChargeState" -> awaitResult(CMD.GET_READER_CHARGING) {
                    helper.getBatteryChargeState()
                }
                "sunmi.rfid.getBatteryChargeNumTimes" -> awaitResult(CMD.GET_READER_CHARGING_NUM_TIMES) {
                    helper.getBatteryChargeNumTimes()
                }

                // === Sync — GPIO ===
                "sunmi.rfid.readGpioValue" -> awaitResult(CMD.READ_GPIO_VALUE) {
                    helper.readGpioValue()
                }
                "sunmi.rfid.writeGpioValue" -> awaitResult(CMD.WRITE_GPIO_VALUE) {
                    helper.writeGpioValue(
                        json.getInt("btPort").toByte(),
                        json.getInt("btValue").toByte()
                    )
                }

                // === Sync — System ===
                "sunmi.rfid.resetReader" -> awaitResult(CMD.RESET) {
                    helper.resetReader()
                }
                "sunmi.rfid.reset" -> awaitResult(CMD.RESET) {
                    helper.reset()
                }
                "sunmi.rfid.setReaderAddress" -> awaitResult(CMD.SET_READER_ADDRESS) {
                    helper.setReaderAddress(json.getInt("btAddress").toByte())
                }

                // === Sync — Tag Mask ===
                "sunmi.rfid.setTagMask" -> awaitResult(CMD.OPERATE_TAG_MASK) {
                    helper.setTagMask(
                        json.getInt("btMaskId").toByte(),
                        json.getInt("btTarget").toByte(),
                        json.getInt("btAction").toByte(),
                        json.getInt("btMembank").toByte(),
                        json.getInt("btStartAdd").toByte(),
                        json.getInt("btMaskLen").toByte(),
                        hexStringToBytes(json.getString("btAryMaskData"))
                    )
                }
                "sunmi.rfid.clearTagMask" -> awaitResult(CMD.OPERATE_TAG_MASK) {
                    helper.clearTagMask(json.getInt("btMaskId").toByte())
                }
                "sunmi.rfid.getTagMask" -> awaitResult(CMD.OPERATE_TAG_MASK) {
                    helper.getTagMask()
                }

                // === Sync — Impinj ===
                "sunmi.rfid.setImpinjSaveTagFocus" -> awaitResult(CMD.SET_AND_SAVE_IMPINJ_FAST_TID_TAG_FOCUS) {
                    helper.setImpinjSaveTagFocus(json.getBoolean("blnOpen"))
                }

                // === Sync — Power ===
                "sunmi.rfid.setPowerDown" -> awaitResult(CMD.SET_READER_STATUS) {
                    helper.setPowerDown(json.getInt("nIdleTime"), json.getInt("btUnit").toByte())
                }

                else -> error("unsupported_method", "Method not supported: $method")
            }
        } catch (e: Exception) {
            error("sdk_error", e.message ?: "Unknown SDK error")
        }
    }

    // --- Sync/Async helpers ---

    private suspend fun awaitResult(cmd: Byte, timeout: Long = 5000L, block: () -> Unit): String {
        val deferred = CompletableDeferred<String>()
        pendingOps[cmd] = deferred
        try {
            block()
            return withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingOps.remove(cmd)
            return error("timeout", "Operation timed out after ${timeout}ms")
        }
    }

    private fun emitEvent(event: String, payload: String) {
        eventCallback?.onEvent(event, payload)
    }

    private fun success(data: Any? = null): String {
        val obj = JSONObject().put("status", "ok")
        if (data != null) obj.put("result", data)
        return obj.toString()
    }

    private fun started(): String =
        JSONObject().put("status", "started").toString()

    private fun error(code: String, message: String): String =
        JSONObject().put("error", code).put("message", message).toString()

    /**
     * Converts hex string (e.g. "AABBCCDD") to ByteArray.
     */
    private fun hexStringToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace(":", "")
        if (clean.isEmpty()) return ByteArray(0)
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Serializes DataParameter from onSuccess callback to JSON.
     */
    private fun buildSuccessPayload(cmd: Byte, params: DataParameter?): String {
        val obj = JSONObject()
        obj.put("cmd", cmd.toInt() and 0xFF)
        if (params == null) return obj.toString()
        // Inventory stats
        params.getInt(ParamCts.DATA_COUNT, -1).takeIf { it >= 0 }?.let { obj.put("dataCount", it) }
        params.getInt(ParamCts.COUNT, -1).takeIf { it >= 0 }?.let { obj.put("count", it) }
        params.getInt(ParamCts.READ_RATE, -1).takeIf { it >= 0 }?.let { obj.put("readRate", it) }
        params.getInt(ParamCts.COMMAND_DURATION, -1).takeIf { it >= 0 }?.let { obj.put("commandDuration", it) }
        params.getLong(ParamCts.START_TIME, -1L).takeIf { it >= 0 }?.let { obj.put("startTime", it) }
        params.getLong(ParamCts.END_TIME, -1L).takeIf { it >= 0 }?.let { obj.put("endTime", it) }
        // Tag data
        params.getString(ParamCts.TAG_PC)?.let { obj.put("tagPc", it) }
        params.getString(ParamCts.TAG_EPC)?.let { obj.put("tagEpc", it) }
        params.getString(ParamCts.TAG_CRC)?.let { obj.put("tagCrc", it) }
        params.getString(ParamCts.TAG_TID)?.let { obj.put("tagTid", it) }
        params.getString(ParamCts.TAG_DATA)?.let { obj.put("tagData", it) }
        params.getInt(ParamCts.TAG_DATA_LEN, -1).takeIf { it >= 0 }?.let { obj.put("tagDataLen", it) }
        params.getString(ParamCts.TAG_ACCESS_EPC_MATCH)?.let { obj.put("tagAccessEpcMatch", it) }
        params.getInt(ParamCts.TAG_READ_COUNT, -1).takeIf { it >= 0 }?.let { obj.put("tagReadCount", it) }
        params.getString(ParamCts.TAG_RSSI)?.let { obj.put("tagRssi", it) }
        // 6B specific
        params.getString(ParamCts.TAG_UID)?.let { obj.put("tagUid", it) }
        // Antenna
        params.getByte(ParamCts.ANT_ID, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("antId", it.toInt()) }
        // FastTID / lock / tag status
        params.getByte(ParamCts.TAG_MONZA_STATUS, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("tagMonzaStatus", it.toInt()) }
        params.getByte(ParamCts.TAG_STATUS, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("tagStatus", it.toInt()) }
        // Reader config results
        params.getByte(ParamCts.WORK_ANTENNA, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("workAntenna", it.toInt()) }
        params.getString(ParamCts.ARY_OUTPUT_POWER)?.let { obj.put("aryOutputPower", it) }
        params.getByte(ParamCts.FREQUENCY_REGION, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("frequencyRegion", it.toInt()) }
        params.getByte(ParamCts.FREQUENCY_START, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("frequencyStart", it.toInt()) }
        params.getByte(ParamCts.FREQUENCY_END, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("frequencyEnd", it.toInt()) }
        params.getByte(ParamCts.USER_DEFINE_CHANNEL_QUANTITY, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("userDefineChannelQuantity", it.toInt()) }
        params.getInt(ParamCts.USER_DEFINE_START_FREQUENCY, -1).takeIf { it >= 0 }?.let { obj.put("userDefineStartFrequency", it) }
        params.getByte(ParamCts.USER_DEFINE_FREQUENCY_INTERVAL, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("userDefineFrequencyInterval", it.toInt()) }
        params.getByte(ParamCts.PLUS_MINUS, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("plusMinus", it.toInt()) }
        params.getByte(ParamCts.TEMPERATURE, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("temperature", it.toInt()) }
        params.getByte(ParamCts.GP_IO_1_VALUE, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("gpIo1Value", it.toInt()) }
        params.getByte(ParamCts.GP_IO_2_VALUE, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("gpIo2Value", it.toInt()) }
        params.getByte(ParamCts.ANT_CONNECTION_DETECTOR, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("antConnectionDetector", it.toInt()) }
        params.getString(ParamCts.READER_IDENTIFIER)?.let { obj.put("readerIdentifier", it) }
        params.getByte(ParamCts.RF_LINK_PROFILE, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("rfLinkProfile", it.toInt()) }
        params.getByte(ParamCts.RF_PORT_RETURN_LOSS, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("rfPortReturnLoss", it.toInt()) }
        params.getByte(ParamCts.BEEP_MODE, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("beepMode", it.toInt()) }
        params.getByte(ParamCts.SCAN_TYPE, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("scanType", it.toInt()) }
        params.getString(ParamCts.SN)?.let { obj.put("sn", it) }
        params.getString(ParamCts.FIRMWARE_VERSION)?.let { obj.put("firmwareVersion", it) }
        params.getString(ParamCts.FIRMWARE_MAIN_VERSION)?.let { obj.put("firmwareMainVersion", it) }
        params.getString(ParamCts.FIRMWARE_MIN_VERSION)?.let { obj.put("firmwareMinVersion", it) }
        params.getString(ParamCts.BATTERY_VOLTAGE)?.let { obj.put("batteryVoltage", it) }
        params.getString(ParamCts.BATTERY_REMAINING_PERCENT)?.let { obj.put("batteryRemainingPercent", it) }
        params.getString(ParamCts.BATTERY_CHARGING)?.let { obj.put("batteryCharging", it) }
        params.getString(ParamCts.BATTERY_CHARGING_NUM_TIMES)?.let { obj.put("batteryChargingNumTimes", it) }
        // Tag mask
        params.getByte(ParamCts.MASK_ID, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("maskId", it.toInt()) }
        params.getInt(ParamCts.MASK_COUNT, -1).takeIf { it >= 0 }?.let { obj.put("maskCount", it) }
        params.getByte(ParamCts.MASK_TARGET, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("maskTarget", it.toInt()) }
        params.getByte(ParamCts.MASK_ACTION, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("maskAction", it.toInt()) }
        params.getByte(ParamCts.MASK_MEMBANK, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("maskMembank", it.toInt()) }
        params.getByte(ParamCts.MASK_START_ADD, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("maskStartAdd", it.toInt()) }
        params.getByte(ParamCts.MASK_LEN, (-1).toByte()).takeIf { it >= 0 }?.let { obj.put("maskLen", it.toInt()) }
        params.getString(ParamCts.MASK_VALUE)?.let { obj.put("maskValue", it) }
        return obj.toString()
    }

    /**
     * Serializes DataParameter from onTag callback to JSON.
     */
    private fun buildTagPayload(cmd: Byte, isNew: Boolean, tag: DataParameter?): String {
        val obj = JSONObject()
        obj.put("cmd", cmd.toInt() and 0xFF)
        obj.put("isNew", isNew)
        if (tag == null) return obj.toString()
        // 6C tag fields
        tag.getString(ParamCts.TAG_PC)?.let { obj.put("tagPc", it) }
        tag.getString(ParamCts.TAG_EPC)?.let { obj.put("tagEpc", it) }
        tag.getString(ParamCts.TAG_CRC)?.let { obj.put("tagCrc", it) }
        tag.getString(ParamCts.TAG_TID)?.let { obj.put("tagTid", it) }
        tag.getString(ParamCts.TAG_RSSI)?.let { obj.put("tagRssi", it) }
        tag.getString(ParamCts.TAG_FREQ)?.let { obj.put("tagFreq", it) }
        tag.getInt(ParamCts.TAG_READ_COUNT, 0).let { obj.put("tagReadCount", it) }
        tag.getLong(ParamCts.TAG_TIME, 0L).let { obj.put("tagTime", it) }
        // 6B tag fields
        tag.getString(ParamCts.TAG_UID)?.let { obj.put("tagUid", it) }
        // Antenna
        tag.getByte(ParamCts.ANT_ID, 0).let { obj.put("antId", it.toInt() and 0xFF) }
        // Switch pattern antenna counts
        tag.getInt(ParamCts.TAG_ANT_1, -1).takeIf { it >= 0 }?.let { obj.put("tagAnt1", it) }
        tag.getInt(ParamCts.TAG_ANT_2, -1).takeIf { it >= 0 }?.let { obj.put("tagAnt2", it) }
        tag.getInt(ParamCts.TAG_ANT_3, -1).takeIf { it >= 0 }?.let { obj.put("tagAnt3", it) }
        tag.getInt(ParamCts.TAG_ANT_4, -1).takeIf { it >= 0 }?.let { obj.put("tagAnt4", it) }
        return obj.toString()
    }

    // --- Descriptor ---

    private fun buildMethodList() = listOf(
        // Basic info
        MethodDescriptor("sunmi.rfid.getScanModel", "Gets RFID module type (sync). Returns {result: 100=none, 101=UHF_R2000, 102=INNER_M500, 103=UHF_S7100, 104=INNER_SIM3500}", "sunmi.rfid"),

        // 6C Inventory (async — returns started, results via events)
        MethodDescriptor("sunmi.rfid.inventory", "6C inventory — buffer mode (async). Params: {\"btRepeat\":255}. Tags via tagFound events, summary via operationSuccess.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.realTimeInventory", "6C inventory — real-time (async). Params: {\"btRepeat\":255}. Emits tagFound per tag.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.customizedSessionTargetInventory", "6C inventory — session/target (async, recommended). Params: {\"btSession\":1,\"btTarget\":0,\"btSL\":0,\"btPhase\":0,\"btPowerSave\":0,\"btRepeat\":255}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.fastSwitchAntInventory", "6C inventory — fast antenna switch (async). Params: {\"btA\":0,\"btStayA\":1,\"btB\":1,\"btStayB\":1,\"btC\":255,\"btStayC\":1,\"btD\":255,\"btStayD\":1,\"btInterval\":10,\"btRepeat\":255}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.realTimeInventoryWithTid", "6C inventory with TID reading (async). Params: {\"scanTime\":0,\"btTidLen\":6,\"btTarget\":0,\"btScan\":0,\"btAryEpc\":\"\"}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.iso180006BInventory", "6B inventory (async). Emits tagFound per tag.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getInventoryBuffer", "Get buffered 6C tags (async). Tags via tagFound events.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getAndResetInventoryBuffer", "Get buffered 6C tags and clear buffer (async). Tags via tagFound events.", "sunmi.rfid"),

        // 6C Tag Operations (sync)
        MethodDescriptor("sunmi.rfid.readTag", "Read 6C tag data (sync). Call setAccessEpcMatch first. Params: {\"btMemBank\":2,\"btWordAdd\":0,\"btWordCnt\":6,\"btAryPassWord\":\"00000000\"}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.writeTag", "Write 6C tag data (sync). Call setAccessEpcMatch first. Params: {\"btAryPassWord\":\"00000000\",\"btMemBank\":1,\"btWordAdd\":2,\"btWordCnt\":6,\"btAryData\":\"AABBCCDD...\"}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.lockTag", "Lock 6C tag memory bank (sync). Params: {\"btAryPassWord\":\"00000000\",\"btMemBank\":1,\"btLockType\":1} (lockType: 0=open,1=lock,2=perm.open,3=perm.locked)", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.killTag", "Kill 6C tag permanently (sync). Params: {\"btAryPassWord\":\"00000000\"}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.setAccessEpcMatch", "Set EPC filter for tag operations (sync). Params: {\"btAryEpc\":\"AABBCCDD...\"}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.cancelAccessEpcMatch", "Clear EPC filter (sync).", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getAccessEpcMatch", "Get current EPC filter (sync). Result: tagAccessEpcMatch.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.setImpinjFastTid", "Enable FastTID for Impinj Monza tags (sync). Params: {\"blnOpen\":true,\"blnSave\":false}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getImpinjFastTid", "Get FastTID status (sync). Result: tagMonzaStatus.", "sunmi.rfid"),

        // 6B Tag Operations (sync)
        MethodDescriptor("sunmi.rfid.iso180006BReadTag", "Read 6B tag (sync). Params: {\"btAryUID\":\"AABBCCDD11223344\",\"btWordAdd\":0,\"btWordCnt\":4}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.iso180006BWriteTag", "Write 6B tag (sync). Params: {\"btAryUID\":\"AABBCCDD11223344\",\"btWordAdd\":0,\"btWordCnt\":2,\"btAryBuffer\":\"AABB\"}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.iso180006BLockTag", "Lock 6B tag byte (sync). Params: {\"btAryUID\":\"AABBCCDD11223344\",\"btWordAdd\":10}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.iso180006BQueryLockTag", "Query 6B tag lock status (sync). Params: {\"btAryUID\":\"AABBCCDD11223344\",\"btWordAdd\":10}", "sunmi.rfid"),

        // Buffer Operations (sync)
        MethodDescriptor("sunmi.rfid.getInventoryBufferTagCount", "Get number of buffered 6C tags (sync). Result: count.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.resetInventoryBuffer", "Clear 6C tag buffer (sync).", "sunmi.rfid"),

        // Antenna (sync)
        MethodDescriptor("sunmi.rfid.setWorkAntenna", "Set active antenna (sync). Params: {\"btAntId\":0}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getWorkAntenna", "Get active antenna (sync). Result: workAntenna.", "sunmi.rfid"),

        // Output Power (sync)
        MethodDescriptor("sunmi.rfid.setOutputAllPower", "Set output power for all antennas (sync). Params: {\"btOutputPower\":26} (dBm)", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.setOutputPower", "Set per-antenna output power (sync). Params: {\"btPower1\":26,\"btPower2\":26,\"btPower3\":26,\"btPower4\":26}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getOutputPower", "Get output power (sync). Result: aryOutputPower.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.setTemporaryOutputPower", "Set non-persistent output power (sync). Params: {\"btOutputPower\":26}", "sunmi.rfid"),

        // Frequency (sync)
        MethodDescriptor("sunmi.rfid.setFrequencyRegion", "Set frequency region (sync). Params: {\"btRegion\":1,\"btStart\":0,\"btEnd\":0}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.setUserDefineFrequency", "Set user-defined frequency (sync). Params: {\"btQuantity\":1,\"btFreqInterval\":1,\"nStartFreq\":920125}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getFrequencyRegion", "Get frequency region (sync). Result: frequencyRegion, frequencyStart, frequencyEnd.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.setFixedFrequency", "Set fixed frequency mode (sync).", "sunmi.rfid"),

        // Beeper (sync)
        MethodDescriptor("sunmi.rfid.setBeeperMode", "Set beeper mode (sync). Params: {\"btMode\":0} (0=off, 1=on)", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getBeeperMode", "Get beeper mode (sync). Result: beepMode.", "sunmi.rfid"),

        // RF Link Profile (sync)
        MethodDescriptor("sunmi.rfid.setRfLinkProfile", "Set RF link profile (sync). Params: {\"btProfile\":0}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getRfLinkProfile", "Get RF link profile (sync). Result: rfLinkProfile.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getRfPortReturnLoss", "Get RF port return loss (sync). Params: {\"btFreq\":1}. Result: rfPortReturnLoss.", "sunmi.rfid"),

        // Antenna Connection Detector (sync)
        MethodDescriptor("sunmi.rfid.setAntConnectionDetector", "Set antenna connection detector (sync). Params: {\"btPower\":0}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getAntConnectionDetector", "Get antenna connection detector (sync). Result: antConnectionDetector.", "sunmi.rfid"),

        // Reader Identity (sync)
        MethodDescriptor("sunmi.rfid.setReaderIdentifier", "Set reader identifier (sync). Params: {\"btAryIdentifier\":\"AABB...\"}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getReaderIdentifier", "Get reader identifier (sync). Result: readerIdentifier.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getReaderSN", "Get reader serial number (sync). Result: sn.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getReaderCustomSN", "Get reader custom serial number (sync). Params: {\"btMode\":0}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getReaderVersion", "Get reader hardware version (sync).", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getFirmwareVersion", "Get firmware version (sync). Result: firmwareVersion.", "sunmi.rfid"),

        // Temperature (sync)
        MethodDescriptor("sunmi.rfid.getReaderTemperature", "Get reader temperature (sync). Result: temperature, plusMinus.", "sunmi.rfid"),

        // Battery (sync)
        MethodDescriptor("sunmi.rfid.getBatteryRemainingPercent", "Get battery remaining percentage (sync). Result: batteryRemainingPercent.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getBatteryVoltage", "Get battery voltage (sync). Result: batteryVoltage.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getBatteryChargeState", "Get battery charge state (sync). Result: batteryCharging.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getBatteryChargeNumTimes", "Get battery charge cycle count (sync). Result: batteryChargingNumTimes.", "sunmi.rfid"),

        // GPIO (sync)
        MethodDescriptor("sunmi.rfid.readGpioValue", "Read GPIO values (sync). Result: gpIo1Value, gpIo2Value.", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.writeGpioValue", "Write GPIO value (sync). Params: {\"btPort\":1,\"btValue\":0}", "sunmi.rfid"),

        // System (sync)
        MethodDescriptor("sunmi.rfid.resetReader", "Reset reader (sync).", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.reset", "Reset RFID module (sync).", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.setReaderAddress", "Set reader address (sync). Params: {\"btAddress\":0}", "sunmi.rfid"),

        // Tag Mask (sync)
        MethodDescriptor("sunmi.rfid.setTagMask", "Set tag mask filter (sync). Params: {\"btMaskId\":0,\"btTarget\":0,\"btAction\":0,\"btMembank\":1,\"btStartAdd\":0,\"btMaskLen\":0,\"btAryMaskData\":\"AABB...\"}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.clearTagMask", "Clear tag mask (sync). Params: {\"btMaskId\":0}", "sunmi.rfid"),
        MethodDescriptor("sunmi.rfid.getTagMask", "Get tag mask configuration (sync). Result: mask fields.", "sunmi.rfid"),

        // Impinj (sync)
        MethodDescriptor("sunmi.rfid.setImpinjSaveTagFocus", "Enable Impinj tag focus and save (sync). Params: {\"blnOpen\":true}", "sunmi.rfid"),

        // Power (sync)
        MethodDescriptor("sunmi.rfid.setPowerDown", "Set power-down mode (sync). Params: {\"nIdleTime\":0,\"btUnit\":0}", "sunmi.rfid"),
    )

    private fun buildEventList() = listOf(
        // Tag events
        EventDescriptor(EVENT_TAG_FOUND, "Tag detected during inventory. Payload: {cmd, isNew, tagEpc?, tagUid?, tagTid?, tagRssi?, tagPc?, tagCrc?, tagFreq?, tagReadCount, tagTime, antId, tagAnt1-4?}", "sunmi.rfid"),
        // Operation result events (only for async inventory methods)
        EventDescriptor(EVENT_OPERATION_SUCCESS, "Async operation completed. Payload varies by cmd.", "sunmi.rfid"),
        EventDescriptor(EVENT_OPERATION_ERROR, "Async operation failed. Payload: {cmd, errorCode, message}", "sunmi.rfid"),
        // Broadcast events
        EventDescriptor(RfidBroadcastReceiver.EVENT_DEVICE_NOT_FOUND, "No RFID device found. Payload: {message}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_DEVICE_DISCONNECTED, "RFID device connection lost. Payload: {message}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_BATTERY_LOW, "RFID device battery low. Payload: {batteryPercent}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_DEVICE_OPENED, "RFID device opened. Payload: {message}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_DEVICE_CLOSED, "RFID device closed. Payload: {message}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_DEVICE_CONNECTED, "RFID device connected. Payload: {message}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_DEVICE_DISCONNECTED_BROADCAST, "RFID device disconnected (broadcast). Payload: {message}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_READER_BOOT, "RFID reader booted. Payload: {message}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_SERIAL_NUMBER, "Reader serial number received. Payload: {sn}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_CUSTOM_SERIAL_NUMBER, "Reader custom serial number received. Payload: {customSn}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_FIRMWARE_VERSION_BROADCAST, "Firmware version received. Payload: {firmwareVersion}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_BATTERY_VOLTAGE_BROADCAST, "Battery voltage received. Payload: {batteryVoltage}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_BATTERY_PERCENT_BROADCAST, "Battery percentage received. Payload: {batteryPercent}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_BATTERY_CHARGING, "Battery charging status. Payload: {charging}", "sunmi.rfid"),
        EventDescriptor(RfidBroadcastReceiver.EVENT_BATTERY_CHARGING_NUM_TIMES, "Battery charge cycle count. Payload: {numTimes}", "sunmi.rfid"),
    )

    companion object {
        const val EVENT_TAG_FOUND = "sunmi.rfid.tagFound"
        const val EVENT_OPERATION_SUCCESS = "sunmi.rfid.operationSuccess"
        const val EVENT_OPERATION_ERROR = "sunmi.rfid.operationError"
    }
}
