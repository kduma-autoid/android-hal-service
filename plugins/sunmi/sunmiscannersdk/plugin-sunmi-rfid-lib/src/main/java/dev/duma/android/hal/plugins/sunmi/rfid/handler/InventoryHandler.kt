package dev.duma.android.hal.plugins.sunmi.rfid.handler

import com.sunmi.rfid.RFIDHelper
import com.sunmi.rfid.RFIDManager
import com.sunmi.rfid.constant.CMD
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.rfid.RfidOperationBridge
import dev.duma.android.hal.plugins.sunmi.rfid.hexStringToBytes
import org.json.JSONObject

internal class InventoryHandler(private val bridge: RfidOperationBridge) {

    suspend fun handle(method: String, json: JSONObject, helper: RFIDHelper): CommandResult? {
        return when (method) {
            "sunmi.rfid.getScanModel" -> {
                val modelId = helper.getScanModel()
                val modelName = when (modelId) {
                    RFIDManager.NONE -> "NONE"
                    RFIDManager.UHF_R2000 -> "UHF_R2000"
                    RFIDManager.INNER_M500 -> "INNER_M500"
                    RFIDManager.UHF_S7100 -> "UHF_S7100"
                    RFIDManager.INNER_SIM3500 -> "INNER_SIM3500"
                    else -> "UNKNOWN"
                }
                CommandResult.Success(
                    JSONObject()
                        .put("available", modelId != RFIDManager.NONE)
                        .put("modelId", modelId)
                        .put("model", modelName)
                        .toString()
                )
            }

            "sunmi.rfid.inventory" -> {
                helper.inventory(json.optInt("btRepeat", 0xFF).toByte())
                bridge.started()
            }
            "sunmi.rfid.realTimeInventory" -> {
                helper.realTimeInventory(json.optInt("btRepeat", 0xFF).toByte())
                bridge.started()
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
                bridge.started()
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
                bridge.started()
            }
            "sunmi.rfid.realTimeInventoryWithTid" -> {
                helper.realTimeInventoryWithTid(
                    json.getInt("scanTime"),
                    json.getInt("btTidLen").toByte(),
                    json.getInt("btTarget").toByte(),
                    json.getInt("btScan").toByte(),
                    hexStringToBytes(json.optString("btAryEpc", ""))
                )
                bridge.started()
            }
            "sunmi.rfid.iso180006BInventory" -> {
                helper.iso180006BInventory()
                bridge.started()
            }
            "sunmi.rfid.getInventoryBuffer" -> {
                helper.getInventoryBuffer()
                bridge.started()
            }
            "sunmi.rfid.getAndResetInventoryBuffer" -> {
                helper.getAndResetInventoryBuffer()
                bridge.started()
            }

            // Buffer operations
            "sunmi.rfid.getInventoryBufferTagCount" -> bridge.awaitResult(CMD.GET_INVENTORY_BUFFER_TAG_COUNT) {
                helper.getInventoryBufferTagCount()
            }
            "sunmi.rfid.resetInventoryBuffer" -> bridge.awaitResult(CMD.RESET_INVENTORY_BUFFER) {
                helper.resetInventoryBuffer()
            }

            else -> null
        }
    }
}
