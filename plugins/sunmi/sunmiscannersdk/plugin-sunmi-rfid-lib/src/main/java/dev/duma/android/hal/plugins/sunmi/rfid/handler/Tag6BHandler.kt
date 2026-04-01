package dev.duma.android.hal.plugins.sunmi.rfid.handler

import com.sunmi.rfid.RFIDHelper
import com.sunmi.rfid.constant.CMD
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.rfid.RfidOperationBridge
import dev.duma.android.hal.plugins.sunmi.rfid.hexStringToBytes
import org.json.JSONObject

internal class Tag6BHandler(private val bridge: RfidOperationBridge) {

    suspend fun handle(method: String, json: JSONObject, helper: RFIDHelper): CommandResult? {
        return when (method) {
            "sunmi.rfid.iso180006BReadTag" -> bridge.awaitResult(CMD.ISO18000_6B_READ_TAG) {
                helper.iso180006BReadTag(
                    hexStringToBytes(json.getString("btAryUID")),
                    json.getInt("btWordAdd").toByte(),
                    json.getInt("btWordCnt").toByte()
                )
            }
            "sunmi.rfid.iso180006BWriteTag" -> bridge.awaitResult(CMD.ISO18000_6B_WRITE_TAG) {
                helper.iso180006BWriteTag(
                    hexStringToBytes(json.getString("btAryUID")),
                    json.getInt("btWordAdd").toByte(),
                    json.getInt("btWordCnt").toByte(),
                    hexStringToBytes(json.getString("btAryBuffer"))
                )
            }
            "sunmi.rfid.iso180006BLockTag" -> bridge.awaitResult(CMD.ISO18000_6B_LOCK_TAG) {
                helper.iso180006BLockTag(
                    hexStringToBytes(json.getString("btAryUID")),
                    json.getInt("btWordAdd").toByte()
                )
            }
            "sunmi.rfid.iso180006BQueryLockTag" -> bridge.awaitResult(CMD.ISO18000_6B_QUERY_LOCK_TAG) {
                helper.iso180006BQueryLockTag(
                    hexStringToBytes(json.getString("btAryUID")),
                    json.getInt("btWordAdd").toByte()
                )
            }

            else -> null
        }
    }
}
