package dev.duma.android.hal.plugins.sunmi.rfid.handler

import com.sunmi.rfid.RFIDHelper
import com.sunmi.rfid.constant.CMD
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.rfid.RfidOperationBridge
import dev.duma.android.hal.plugins.sunmi.rfid.hexStringToBytes
import org.json.JSONObject

internal class ReaderInfoHandler(private val bridge: RfidOperationBridge) {

    suspend fun handle(method: String, json: JSONObject, helper: RFIDHelper): CommandResult? {
        return when (method) {
            "sunmi.rfid.setReaderIdentifier" -> bridge.awaitResult(CMD.SET_READER_IDENTIFIER) {
                helper.setReaderIdentifier(hexStringToBytes(json.getString("btAryIdentifier")))
            }
            "sunmi.rfid.getReaderIdentifier" -> bridge.awaitResult(CMD.GET_READER_IDENTIFIER) {
                helper.getReaderIdentifier()
            }
            "sunmi.rfid.getReaderSN" -> bridge.awaitResult(CMD.GET_READER_SN) {
                helper.getReaderSN()
            }
            "sunmi.rfid.getReaderCustomSN" -> bridge.awaitResult(CMD.GET_READER_SN) {
                helper.getReaderCustomSN(json.getInt("btMode").toByte())
            }
            "sunmi.rfid.getReaderVersion" -> bridge.awaitResult(CMD.GET_READER_VERSION) {
                helper.getReaderVersion()
            }
            "sunmi.rfid.getFirmwareVersion" -> bridge.awaitResult(CMD.GET_FIRMWARE_VERSION) {
                helper.getFirmwareVersion()
            }
            "sunmi.rfid.getReaderTemperature" -> bridge.awaitResult(CMD.GET_READER_TEMPERATURE) {
                helper.getReaderTemperature()
            }

            else -> null
        }
    }
}
