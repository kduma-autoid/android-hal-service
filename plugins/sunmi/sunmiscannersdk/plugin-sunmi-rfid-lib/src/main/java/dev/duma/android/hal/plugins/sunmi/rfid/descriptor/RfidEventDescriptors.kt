package dev.duma.android.hal.plugins.sunmi.rfid.descriptor

import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.plugins.sunmi.rfid.RfidOperationBridge
import dev.duma.android.hal.plugins.sunmi.rfid.receiver.RfidBroadcastReceiver

internal object RfidEventDescriptors {

    fun allEvents() = operationEvents() + broadcastEvents()

    private fun operationEvents() = listOf(
        EventDescriptor(
            RfidOperationBridge.EVENT_TAG_FOUND,
            "Tag detected during inventory.",
            "sunmi.rfid",
            exampleEvent = """{"isNew":true,"epc":"E200001234567890","pc":"3000","rssi":"-45","readCount":1,"time":1234567890,"antId":0}"""
        ),
        EventDescriptor(
            RfidOperationBridge.EVENT_OPERATION_SUCCESS,
            "Async operation completed.",
            "sunmi.rfid",
            exampleEvent = """{"dataCount":10,"count":10,"readRate":50,"commandDuration":1000}"""
        ),
        EventDescriptor(
            RfidOperationBridge.EVENT_OPERATION_ERROR,
            "Async operation failed.",
            "sunmi.rfid",
            exampleEvent = """{"errorCode":1,"message":"Tag not found"}"""
        ),
    )

    private fun broadcastEvents() = listOf(
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_DEVICE_NOT_FOUND,
            "No RFID device found.",
            "sunmi.rfid",
            exampleEvent = """{"message":"No RFID device found"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_DEVICE_DISCONNECTED,
            "RFID device connection lost.",
            "sunmi.rfid",
            exampleEvent = """{"message":"Device disconnected"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_BATTERY_LOW,
            "RFID device battery low.",
            "sunmi.rfid",
            exampleEvent = """{"batteryPercent":10}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_DEVICE_OPENED,
            "RFID device opened.",
            "sunmi.rfid",
            exampleEvent = """{"message":"Device opened"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_DEVICE_CLOSED,
            "RFID device closed.",
            "sunmi.rfid",
            exampleEvent = """{"message":"Device closed"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_DEVICE_CONNECTED,
            "RFID device connected.",
            "sunmi.rfid",
            exampleEvent = """{"message":"Device connected"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_DEVICE_DISCONNECTED_BROADCAST,
            "RFID device disconnected (broadcast).",
            "sunmi.rfid",
            exampleEvent = """{"message":"Device disconnected"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_READER_BOOT,
            "RFID reader booted.",
            "sunmi.rfid",
            exampleEvent = """{"message":"Reader booted"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_SERIAL_NUMBER,
            "Reader serial number received.",
            "sunmi.rfid",
            exampleEvent = """{"sn":"SN12345678"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_CUSTOM_SERIAL_NUMBER,
            "Reader custom serial number received.",
            "sunmi.rfid",
            exampleEvent = """{"customSn":"CSN12345678"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_FIRMWARE_VERSION_BROADCAST,
            "Firmware version received.",
            "sunmi.rfid",
            exampleEvent = """{"firmwareVersion":"2.0.0"}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_BATTERY_VOLTAGE_BROADCAST,
            "Battery voltage received.",
            "sunmi.rfid",
            exampleEvent = """{"batteryVoltage":3700}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_BATTERY_PERCENT_BROADCAST,
            "Battery percentage received.",
            "sunmi.rfid",
            exampleEvent = """{"batteryPercent":85}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_BATTERY_CHARGING,
            "Battery charging status.",
            "sunmi.rfid",
            exampleEvent = """{"charging":true}"""
        ),
        EventDescriptor(
            RfidBroadcastReceiver.EVENT_BATTERY_CHARGING_NUM_TIMES,
            "Battery charge cycle count.",
            "sunmi.rfid",
            exampleEvent = """{"numTimes":100}"""
        ),
    )
}
