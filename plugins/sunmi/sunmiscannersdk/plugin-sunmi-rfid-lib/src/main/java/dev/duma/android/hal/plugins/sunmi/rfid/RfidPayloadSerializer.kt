package dev.duma.android.hal.plugins.sunmi.rfid

import com.sunmi.rfid.constant.ParamCts
import com.sunmi.rfid.entity.DataParameter
import org.json.JSONObject

/**
 * Serializes SDK DataParameter responses to JSON strings.
 */
internal object RfidPayloadSerializer {

    /**
     * Serializes DataParameter from onSuccess callback to JSON.
     */
    fun buildSuccessPayload(cmd: Byte, params: DataParameter?): String {
        val obj = JSONObject()
        if (params == null) return obj.toString()
        // Inventory stats
        params.getInt(ParamCts.DATA_COUNT, -1).takeIf { it >= 0 }?.let { obj.put("dataCount", it) }
        params.getInt(ParamCts.COUNT, -1).takeIf { it >= 0 }?.let { obj.put("count", it) }
        params.getInt(ParamCts.READ_RATE, -1).takeIf { it >= 0 }?.let { obj.put("readRate", it) }
        params.getInt(ParamCts.COMMAND_DURATION, -1).takeIf { it >= 0 }?.let { obj.put("commandDuration", it) }
        params.getLong(ParamCts.START_TIME, -1L).takeIf { it >= 0 }?.let { obj.put("startTime", it) }
        params.getLong(ParamCts.END_TIME, -1L).takeIf { it >= 0 }?.let { obj.put("endTime", it) }
        // Tag data
        params.getString(ParamCts.TAG_PC)?.let { obj.put("pc", it) }
        params.getString(ParamCts.TAG_EPC)?.let { obj.put("epc", it) }
        params.getString(ParamCts.TAG_CRC)?.let { obj.put("crc", it) }
        params.getString(ParamCts.TAG_TID)?.let { obj.put("tid", it) }
        params.getString(ParamCts.TAG_DATA)?.let { obj.put("data", it) }
        params.getInt(ParamCts.TAG_DATA_LEN, -1).takeIf { it >= 0 }?.let { obj.put("dataLen", it) }
        params.getString(ParamCts.TAG_ACCESS_EPC_MATCH)?.let { obj.put("epcFilter", it) }
        params.getInt(ParamCts.TAG_READ_COUNT, -1).takeIf { it >= 0 }?.let { obj.put("readCount", it) }
        params.getString(ParamCts.TAG_RSSI)?.let { obj.put("rssi", it) }
        // 6B specific
        params.getString(ParamCts.TAG_UID)?.let { obj.put("uid", it) }
        // Antenna
        params.getByte(ParamCts.ANT_ID, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("antId", it.toInt() and 0xFF) }
        // FastTID / lock / tag status
        params.getByte(ParamCts.TAG_MONZA_STATUS, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("fastTidStatus", it.toInt() and 0xFF) }
        params.getByte(ParamCts.TAG_STATUS, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("lockStatus", it.toInt() and 0xFF) }
        // Reader config results
        params.getByte(ParamCts.WORK_ANTENNA, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("workAntenna", it.toInt() and 0xFF) }
        params.getString(ParamCts.ARY_OUTPUT_POWER)?.let { obj.put("outputPower", it) }
        params.getByte(ParamCts.FREQUENCY_REGION, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("frequencyRegion", it.toInt() and 0xFF) }
        params.getByte(ParamCts.FREQUENCY_START, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("frequencyStart", it.toInt() and 0xFF) }
        params.getByte(ParamCts.FREQUENCY_END, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("frequencyEnd", it.toInt() and 0xFF) }
        params.getByte(ParamCts.USER_DEFINE_CHANNEL_QUANTITY, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("userDefineChannelQuantity", it.toInt() and 0xFF) }
        params.getInt(ParamCts.USER_DEFINE_START_FREQUENCY, -1).takeIf { it >= 0 }?.let { obj.put("userDefineStartFrequency", it) }
        params.getByte(ParamCts.USER_DEFINE_FREQUENCY_INTERVAL, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("userDefineFrequencyInterval", it.toInt() and 0xFF) }
        params.getByte(ParamCts.PLUS_MINUS, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("plusMinus", it.toInt() and 0xFF) }
        params.getByte(ParamCts.TEMPERATURE, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("temperature", it.toInt() and 0xFF) }
        params.getByte(ParamCts.GP_IO_1_VALUE, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("gpio1", it.toInt() and 0xFF) }
        params.getByte(ParamCts.GP_IO_2_VALUE, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("gpio2", it.toInt() and 0xFF) }
        params.getByte(ParamCts.ANT_CONNECTION_DETECTOR, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("antConnectionDetector", it.toInt() and 0xFF) }
        params.getString(ParamCts.READER_IDENTIFIER)?.let { obj.put("readerIdentifier", it) }
        params.getByte(ParamCts.RF_LINK_PROFILE, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("rfLinkProfile", it.toInt() and 0xFF) }
        params.getByte(ParamCts.RF_PORT_RETURN_LOSS, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("rfPortReturnLoss", it.toInt() and 0xFF) }
        params.getByte(ParamCts.BEEP_MODE, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("beepMode", it.toInt() and 0xFF) }
        params.getByte(ParamCts.SCAN_TYPE, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("scanType", it.toInt() and 0xFF) }
        params.getString(ParamCts.SN)?.let { obj.put("sn", it) }
        params.getString(ParamCts.FIRMWARE_VERSION)?.let { obj.put("firmwareVersion", it) }
        params.getString(ParamCts.FIRMWARE_MAIN_VERSION)?.let { obj.put("firmwareMainVersion", it) }
        params.getString(ParamCts.FIRMWARE_MIN_VERSION)?.let { obj.put("firmwareMinVersion", it) }
        params.getString(ParamCts.BATTERY_VOLTAGE)?.let { obj.put("batteryVoltage", it) }
        params.getString(ParamCts.BATTERY_REMAINING_PERCENT)?.let { obj.put("batteryRemainingPercent", it) }
        params.getString(ParamCts.BATTERY_CHARGING)?.let { obj.put("batteryCharging", it) }
        params.getString(ParamCts.BATTERY_CHARGING_NUM_TIMES)?.let { obj.put("batteryChargingNumTimes", it) }
        // Tag mask
        params.getByte(ParamCts.MASK_ID, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("id", it.toInt() and 0xFF) }
        params.getInt(ParamCts.MASK_COUNT, -1).takeIf { it >= 0 }?.let { obj.put("count", it) }
        params.getByte(ParamCts.MASK_TARGET, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("target", it.toInt() and 0xFF) }
        params.getByte(ParamCts.MASK_ACTION, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("action", it.toInt() and 0xFF) }
        params.getByte(ParamCts.MASK_MEMBANK, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("membank", it.toInt() and 0xFF) }
        params.getByte(ParamCts.MASK_START_ADD, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("startAddress", it.toInt() and 0xFF) }
        params.getByte(ParamCts.MASK_LEN, (-1).toByte()).takeIf { it != (-1).toByte() }?.let { obj.put("length", it.toInt() and 0xFF) }
        params.getString(ParamCts.MASK_VALUE)?.let { obj.put("value", it) }
        return obj.toString()
    }

    /**
     * Serializes DataParameter from onTag callback to JSON.
     */
    fun buildTagPayload(cmd: Byte, isNew: Boolean, tag: DataParameter?): String {
        val obj = JSONObject()
        obj.put("isNew", isNew)
        if (tag == null) return obj.toString()
        // 6C tag fields
        tag.getString(ParamCts.TAG_PC)?.let { obj.put("pc", it) }
        tag.getString(ParamCts.TAG_EPC)?.let { obj.put("epc", it) }
        tag.getString(ParamCts.TAG_CRC)?.let { obj.put("crc", it) }
        tag.getString(ParamCts.TAG_TID)?.let { obj.put("tid", it) }
        tag.getString(ParamCts.TAG_RSSI)?.let { obj.put("rssi", it) }
        tag.getString(ParamCts.TAG_FREQ)?.let { obj.put("freq", it) }
        tag.getInt(ParamCts.TAG_READ_COUNT, 0).let { obj.put("readCount", it) }
        tag.getLong(ParamCts.TAG_TIME, 0L).let { obj.put("time", it) }
        // 6B tag fields
        tag.getString(ParamCts.TAG_UID)?.let { obj.put("uid", it) }
        // Antenna
        tag.getByte(ParamCts.ANT_ID, 0).let { obj.put("antId", it.toInt() and 0xFF) }
        // Switch pattern antenna counts
        tag.getInt(ParamCts.TAG_ANT_1, -1).takeIf { it >= 0 }?.let { obj.put("ant1Count", it) }
        tag.getInt(ParamCts.TAG_ANT_2, -1).takeIf { it >= 0 }?.let { obj.put("ant2Count", it) }
        tag.getInt(ParamCts.TAG_ANT_3, -1).takeIf { it >= 0 }?.let { obj.put("ant3Count", it) }
        tag.getInt(ParamCts.TAG_ANT_4, -1).takeIf { it >= 0 }?.let { obj.put("ant4Count", it) }
        return obj.toString()
    }
}
