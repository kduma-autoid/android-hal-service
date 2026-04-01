package dev.duma.android.hal.plugins.sunmi.rfid.handler

import com.sunmi.rfid.RFIDHelper
import com.sunmi.rfid.constant.CMD
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.rfid.RfidOperationBridge
import dev.duma.android.hal.plugins.sunmi.rfid.hexStringToBytes
import org.json.JSONObject

internal class SystemHandler(private val bridge: RfidOperationBridge) {

    suspend fun handle(method: String, json: JSONObject, helper: RFIDHelper): CommandResult? {
        return when (method) {
            // System
            "sunmi.rfid.resetReader" -> bridge.awaitResult(CMD.RESET) {
                helper.resetReader()
            }
            "sunmi.rfid.reset" -> bridge.awaitResult(CMD.RESET) {
                helper.reset()
            }
            "sunmi.rfid.setReaderAddress" -> bridge.awaitResult(CMD.SET_READER_ADDRESS) {
                helper.setReaderAddress(json.getInt("btAddress").toByte())
            }

            // Tag Mask
            "sunmi.rfid.setTagMask" -> bridge.awaitResult(CMD.OPERATE_TAG_MASK) {
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
            "sunmi.rfid.clearTagMask" -> bridge.awaitResult(CMD.OPERATE_TAG_MASK) {
                helper.clearTagMask(json.getInt("btMaskId").toByte())
            }
            "sunmi.rfid.getTagMask" -> bridge.awaitResult(CMD.OPERATE_TAG_MASK) {
                helper.getTagMask()
            }

            else -> null
        }
    }
}
