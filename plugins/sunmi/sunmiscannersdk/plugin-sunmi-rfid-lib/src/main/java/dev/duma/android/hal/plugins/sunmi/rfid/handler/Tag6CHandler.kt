package dev.duma.android.hal.plugins.sunmi.rfid.handler

import com.sunmi.rfid.RFIDHelper
import com.sunmi.rfid.constant.CMD
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.rfid.RfidOperationBridge
import dev.duma.android.hal.plugins.sunmi.rfid.hexStringToBytes
import org.json.JSONObject

internal class Tag6CHandler(private val bridge: RfidOperationBridge) {

    suspend fun handle(method: String, json: JSONObject, helper: RFIDHelper): CommandResult? {
        return when (method) {
            "sunmi.rfid.readTag" -> bridge.awaitResult(CMD.READ_TAG) {
                helper.readTag(
                    json.getInt("btMemBank").toByte(),
                    json.getInt("btWordAdd").toByte(),
                    json.getInt("btWordCnt").toByte(),
                    hexStringToBytes(json.getString("btAryPassWord"))
                )
            }
            "sunmi.rfid.writeTag" -> bridge.awaitResult(CMD.WRITE_TAG) {
                helper.writeTag(
                    hexStringToBytes(json.getString("btAryPassWord")),
                    json.getInt("btMemBank").toByte(),
                    json.getInt("btWordAdd").toByte(),
                    json.getInt("btWordCnt").toByte(),
                    hexStringToBytes(json.getString("btAryData"))
                )
            }
            "sunmi.rfid.lockTag" -> bridge.awaitResult(CMD.LOCK_TAG) {
                helper.lockTag(
                    hexStringToBytes(json.getString("btAryPassWord")),
                    json.getInt("btMemBank").toByte(),
                    json.getInt("btLockType").toByte()
                )
            }
            "sunmi.rfid.killTag" -> bridge.awaitResult(CMD.KILL_TAG) {
                helper.killTag(hexStringToBytes(json.getString("btAryPassWord")))
            }
            "sunmi.rfid.setAccessEpcMatch" -> bridge.awaitResult(CMD.SET_ACCESS_EPC_MATCH) {
                val epcBytes = hexStringToBytes(json.getString("btAryEpc"))
                helper.setAccessEpcMatch(epcBytes.size.toByte(), epcBytes)
            }
            "sunmi.rfid.cancelAccessEpcMatch" -> bridge.awaitResult(CMD.SET_ACCESS_EPC_MATCH) {
                helper.cancelAccessEpcMatch()
            }
            "sunmi.rfid.getAccessEpcMatch" -> bridge.awaitResult(CMD.GET_ACCESS_EPC_MATCH) {
                helper.getAccessEpcMatch()
            }
            "sunmi.rfid.setImpinjFastTid" -> bridge.awaitResult(CMD.SET_IMPINJ_FAST_TID) {
                helper.setImpinjFastTid(
                    json.getBoolean("blnOpen"),
                    json.getBoolean("blnSave")
                )
            }
            "sunmi.rfid.getImpinjFastTid" -> bridge.awaitResult(CMD.GET_IMPINJ_FAST_TID) {
                helper.getImpinjFastTid()
            }
            "sunmi.rfid.setImpinjSaveTagFocus" -> bridge.awaitResult(CMD.SET_AND_SAVE_IMPINJ_FAST_TID_TAG_FOCUS) {
                helper.setImpinjSaveTagFocus(json.getBoolean("blnOpen"))
            }

            else -> null
        }
    }
}
