package dev.duma.android.hal.plugins.sunmi.rfid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.sunmi.rfid.constant.ParamCts
import org.json.JSONObject

internal class RfidBroadcastReceiver(
    private val emitEvent: (String, String) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_UN_FOUND_READER -> emitEvent(
                EVENT_DEVICE_NOT_FOUND,
                JSONObject().put("message", "No RFID device found").toString()
            )
            ACTION_LOST_CONNECT -> emitEvent(
                EVENT_DEVICE_DISCONNECTED,
                JSONObject().put("message", "RFID device connection lost").toString()
            )
            ACTION_BATTERY_LOW -> {
                val percent = intent.getIntExtra(ParamCts.BATTERY_REMAINING_PERCENT, -1)
                emitEvent(
                    EVENT_BATTERY_LOW,
                    JSONObject().put("batteryPercent", percent).toString()
                )
            }
            ACTION_RFID_OPEN -> emitEvent(
                EVENT_DEVICE_OPENED,
                JSONObject().put("message", "RFID device opened").toString()
            )
            ACTION_RFID_CLOSE -> emitEvent(
                EVENT_DEVICE_CLOSED,
                JSONObject().put("message", "RFID device closed").toString()
            )
            ACTION_ON_CONNECT -> emitEvent(
                EVENT_DEVICE_CONNECTED,
                JSONObject().put("message", "RFID device connected").toString()
            )
            ACTION_ON_DISCONNECT -> emitEvent(
                EVENT_DEVICE_DISCONNECTED_BROADCAST,
                JSONObject().put("message", "RFID device disconnected").toString()
            )
            ACTION_READER_BOOT -> emitEvent(
                EVENT_READER_BOOT,
                JSONObject().put("message", "RFID reader booted").toString()
            )
            ACTION_SN -> {
                val sn = intent.getStringExtra(ParamCts.SN) ?: ""
                emitEvent(
                    EVENT_SERIAL_NUMBER,
                    JSONObject().put("sn", sn).toString()
                )
            }
            ACTION_CUSTOM_SN -> {
                val customSn = intent.getStringExtra(ParamCts.SN) ?: ""
                emitEvent(
                    EVENT_CUSTOM_SERIAL_NUMBER,
                    JSONObject().put("customSn", customSn).toString()
                )
            }
            ACTION_FIRMWARE_VERSION -> {
                val version = intent.getStringExtra(ParamCts.FIRMWARE_VERSION) ?: ""
                emitEvent(
                    EVENT_FIRMWARE_VERSION_BROADCAST,
                    JSONObject().put("firmwareVersion", version).toString()
                )
            }
            ACTION_BATTERY_VOLTAGE -> {
                val voltage = intent.getStringExtra(ParamCts.BATTERY_VOLTAGE) ?: ""
                emitEvent(
                    EVENT_BATTERY_VOLTAGE_BROADCAST,
                    JSONObject().put("batteryVoltage", voltage).toString()
                )
            }
            ACTION_BATTERY_REMAINING_PERCENTAGE -> {
                val percent = intent.getStringExtra(ParamCts.BATTERY_REMAINING_PERCENT) ?: ""
                emitEvent(
                    EVENT_BATTERY_PERCENT_BROADCAST,
                    JSONObject().put("batteryPercent", percent).toString()
                )
            }
            ACTION_BATTERY_CHARGING -> {
                val charging = intent.getStringExtra(ParamCts.BATTERY_CHARGING) ?: ""
                emitEvent(
                    EVENT_BATTERY_CHARGING,
                    JSONObject().put("charging", charging).toString()
                )
            }
            ACTION_BATTERY_CHARGING_NUM_TIMES -> {
                val numTimes = intent.getStringExtra(ParamCts.BATTERY_CHARGING_NUM_TIMES) ?: ""
                emitEvent(
                    EVENT_BATTERY_CHARGING_NUM_TIMES,
                    JSONObject().put("numTimes", numTimes).toString()
                )
            }
        }
    }

    companion object {
        // Broadcast actions
        const val ACTION_UN_FOUND_READER = "com.sunmi.rfid.unFoundReader"
        const val ACTION_LOST_CONNECT = "com.sunmi.rfid.onLostConnect"
        const val ACTION_BATTERY_LOW = "com.sunmi.rfid.batteryLowElec"
        const val ACTION_RFID_OPEN = "com.sunmi.rfid.rfid_open"
        const val ACTION_RFID_CLOSE = "com.sunmi.rfid.rfid_close"
        const val ACTION_ON_CONNECT = "com.sunmi.rfid.onConnect"
        const val ACTION_ON_DISCONNECT = "com.sunmi.rfid.onDisconnect"
        const val ACTION_READER_BOOT = "com.sunmi.rfid.readerBoot"
        const val ACTION_SN = "com.sunmi.rfid.sn"
        const val ACTION_CUSTOM_SN = "com.sunmi.rfid.customSN"
        const val ACTION_FIRMWARE_VERSION = "com.sunmi.rfid.firmwareVersion"
        const val ACTION_BATTERY_VOLTAGE = "com.sunmi.rfid.batteryVoltage"
        const val ACTION_BATTERY_REMAINING_PERCENTAGE = "com.sunmi.rfid.batteryRemainingPercentage"
        const val ACTION_BATTERY_CHARGING = "com.sunmi.rfid.batteryCharging"
        const val ACTION_BATTERY_CHARGING_NUM_TIMES = "com.sunmi.rfid.batteryChargingNumTimes"

        // HAL event names
        const val EVENT_DEVICE_NOT_FOUND = "sunmi.rfid.deviceNotFound"
        const val EVENT_DEVICE_DISCONNECTED = "sunmi.rfid.deviceDisconnected"
        const val EVENT_BATTERY_LOW = "sunmi.rfid.batteryLow"
        const val EVENT_DEVICE_OPENED = "sunmi.rfid.deviceOpened"
        const val EVENT_DEVICE_CLOSED = "sunmi.rfid.deviceClosed"
        const val EVENT_DEVICE_CONNECTED = "sunmi.rfid.deviceConnected"
        const val EVENT_DEVICE_DISCONNECTED_BROADCAST = "sunmi.rfid.deviceDisconnectedBroadcast"
        const val EVENT_READER_BOOT = "sunmi.rfid.readerBoot"
        const val EVENT_SERIAL_NUMBER = "sunmi.rfid.serialNumber"
        const val EVENT_CUSTOM_SERIAL_NUMBER = "sunmi.rfid.customSerialNumber"
        const val EVENT_FIRMWARE_VERSION_BROADCAST = "sunmi.rfid.firmwareVersionBroadcast"
        const val EVENT_BATTERY_VOLTAGE_BROADCAST = "sunmi.rfid.batteryVoltageBroadcast"
        const val EVENT_BATTERY_PERCENT_BROADCAST = "sunmi.rfid.batteryPercentBroadcast"
        const val EVENT_BATTERY_CHARGING = "sunmi.rfid.batteryCharging"
        const val EVENT_BATTERY_CHARGING_NUM_TIMES = "sunmi.rfid.batteryChargingNumTimes"

        fun buildIntentFilter() = IntentFilter().apply {
            addAction(ACTION_UN_FOUND_READER)
            addAction(ACTION_LOST_CONNECT)
            addAction(ACTION_BATTERY_LOW)
            addAction(ACTION_RFID_OPEN)
            addAction(ACTION_RFID_CLOSE)
            addAction(ACTION_ON_CONNECT)
            addAction(ACTION_ON_DISCONNECT)
            addAction(ACTION_READER_BOOT)
            addAction(ACTION_SN)
            addAction(ACTION_CUSTOM_SN)
            addAction(ACTION_FIRMWARE_VERSION)
            addAction(ACTION_BATTERY_VOLTAGE)
            addAction(ACTION_BATTERY_REMAINING_PERCENTAGE)
            addAction(ACTION_BATTERY_CHARGING)
            addAction(ACTION_BATTERY_CHARGING_NUM_TIMES)
        }
    }
}
