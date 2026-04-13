package dev.duma.android.hal.plugins.sunmi.rfid.handler

import com.sunmi.rfid.RFIDHelper
import com.sunmi.rfid.constant.CMD
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.plugins.sunmi.rfid.RfidOperationBridge
import org.json.JSONObject

internal class ReaderConfigHandler(private val bridge: RfidOperationBridge) {

    suspend fun handle(method: String, json: JSONObject, helper: RFIDHelper): CommandResult? {
        return when (method) {
            // Antenna
            "sunmi.rfid.setWorkAntenna" -> bridge.awaitResult(CMD.SET_WORK_ANTENNA) {
                helper.setWorkAntenna(json.getInt("btAntId").toByte())
            }
            "sunmi.rfid.getWorkAntenna" -> bridge.awaitResult(CMD.GET_WORK_ANTENNA) {
                helper.getWorkAntenna()
            }

            // Output Power
            "sunmi.rfid.setOutputAllPower" -> bridge.awaitResult(CMD.SET_OUTPUT_POWER) {
                helper.setOutputAllPower(json.getInt("btOutputPower").toByte())
            }
            "sunmi.rfid.setOutputPower" -> bridge.awaitResult(CMD.SET_OUTPUT_POWER) {
                helper.setOutputPower(
                    json.getInt("btPower1").toByte(),
                    json.getInt("btPower2").toByte(),
                    json.getInt("btPower3").toByte(),
                    json.getInt("btPower4").toByte()
                )
            }
            "sunmi.rfid.getOutputPower" -> bridge.awaitResult(CMD.GET_OUTPUT_POWER) {
                helper.getOutputPower()
            }
            "sunmi.rfid.setTemporaryOutputPower" -> bridge.awaitResult(CMD.SET_TEMPORARY_OUTPUT_POWER) {
                helper.setTemporaryOutputPower(json.getInt("btOutputPower").toByte())
            }

            // Frequency
            "sunmi.rfid.setFrequencyRegion" -> bridge.awaitResult(CMD.SET_FREQUENCY_REGION) {
                helper.setFrequencyRegion(
                    json.getInt("btRegion").toByte(),
                    json.getInt("btStart").toByte(),
                    json.getInt("btEnd").toByte()
                )
            }
            "sunmi.rfid.setUserDefineFrequency" -> bridge.awaitResult(CMD.SET_FREQUENCY_REGION) {
                helper.setUserDefineFrequency(
                    json.getInt("btQuantity").toByte(),
                    json.getInt("btFreqInterval").toByte(),
                    json.getInt("nStartFreq")
                )
            }
            "sunmi.rfid.getFrequencyRegion" -> bridge.awaitResult(CMD.GET_FREQUENCY_REGION) {
                helper.getFrequencyRegion()
            }
            "sunmi.rfid.setFixedFrequency" -> bridge.awaitResult(CMD.SET_FREQUENCY_REGION) {
                helper.setFixedFrequency()
            }

            // Beeper
            "sunmi.rfid.setBeeperMode" -> bridge.awaitResult(CMD.SET_BEEPER_MODE) {
                helper.setBeeperMode(json.getInt("btMode").toByte())
            }
            "sunmi.rfid.getBeeperMode" -> bridge.awaitResult(CMD.GET_BEEP_MODE) {
                helper.getBeeperMode()
            }

            // RF Link Profile
            "sunmi.rfid.setRfLinkProfile" -> bridge.awaitResult(CMD.SET_RF_LINK_PROFILE) {
                helper.setRfLinkProfile(json.getInt("btProfile").toByte())
            }
            "sunmi.rfid.getRfLinkProfile" -> bridge.awaitResult(CMD.GET_RF_LINK_PROFILE) {
                helper.getRfLinkProfile()
            }
            "sunmi.rfid.getRfPortReturnLoss" -> bridge.awaitResult(CMD.GET_RF_PORT_RETURN_LOSS) {
                helper.getRfPortReturnLoss(json.getInt("btFreq").toByte())
            }

            // Antenna Connection Detector
            "sunmi.rfid.setAntConnectionDetector" -> bridge.awaitResult(CMD.SET_ANT_CONNECTION_DETECTOR) {
                helper.setAntConnectionDetector(json.getInt("btPower").toByte())
            }
            "sunmi.rfid.getAntConnectionDetector" -> bridge.awaitResult(CMD.GET_ANT_CONNECTION_DETECTOR) {
                helper.getAntConnectionDetector()
            }

            else -> null
        }
    }
}
