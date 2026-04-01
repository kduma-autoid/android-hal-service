package dev.duma.android.hal.plugins.sunmi.rfid.handler

import com.sunmi.rfid.RFIDHelper
import com.sunmi.rfid.constant.CMD
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.rfid.RfidOperationBridge
import org.json.JSONObject

internal class BatteryGpioHandler(private val bridge: RfidOperationBridge) {

    suspend fun handle(method: String, json: JSONObject, helper: RFIDHelper): CommandResult? {
        return when (method) {
            // Battery
            "sunmi.rfid.getBatteryRemainingPercent" -> bridge.awaitResult(CMD.GET_READER_LOWELEC) {
                helper.getBatteryRemainingPercent()
            }
            "sunmi.rfid.getBatteryVoltage" -> bridge.awaitResult(CMD.GET_READER_VOL) {
                helper.getBatteryVoltage()
            }
            "sunmi.rfid.getBatteryChargeState" -> bridge.awaitResult(CMD.GET_READER_CHARGING) {
                helper.getBatteryChargeState()
            }
            "sunmi.rfid.getBatteryChargeNumTimes" -> bridge.awaitResult(CMD.GET_READER_CHARGING_NUM_TIMES) {
                helper.getBatteryChargeNumTimes()
            }

            // GPIO
            "sunmi.rfid.readGpioValue" -> bridge.awaitResult(CMD.READ_GPIO_VALUE) {
                helper.readGpioValue()
            }
            "sunmi.rfid.writeGpioValue" -> bridge.awaitResult(CMD.WRITE_GPIO_VALUE) {
                helper.writeGpioValue(
                    json.getInt("btPort").toByte(),
                    json.getInt("btValue").toByte()
                )
            }

            // Power
            "sunmi.rfid.setPowerDown" -> bridge.awaitResult(CMD.SET_READER_STATUS) {
                helper.setPowerDown(json.getInt("nIdleTime"), json.getInt("btUnit").toByte())
            }

            else -> null
        }
    }
}
