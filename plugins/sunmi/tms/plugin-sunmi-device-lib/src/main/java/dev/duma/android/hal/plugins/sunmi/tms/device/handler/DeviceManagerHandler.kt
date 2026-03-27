package dev.duma.android.hal.plugins.sunmi.tms.device.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import org.json.JSONObject

internal class DeviceManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): String {
        val op = method.removePrefix("sunmi.tms.device.device_manager.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "setSystemTime" -> {
                api.deviceManager.setSystemTime(
                    json.getInt("second"), json.getInt("minute"), json.getInt("hour"),
                    json.getInt("day"), json.getInt("month"), json.getInt("year")
                )
                success()
            }
            "powerReboot" -> { api.deviceManager.powerReboot(); success() }
            "shutdown" -> { api.deviceManager.shutdown(); success() }
            "setTimeZone" -> { api.deviceManager.setTimeZone(json.getString("timeZone")); success() }
            "factoryReset" -> { api.deviceManager.factoryReset(); success() }
            "toSleep" -> { api.deviceManager.toSleep(); success() }
            "toWakeUp" -> { api.deviceManager.toWakeUp(); success() }
            "setBootAnimation" -> { api.deviceManager.setBootAnimation(json.getString("filePath")); success() }
            "enableBluetooth" -> { api.deviceManager.enableBluetooth(json.getBoolean("enable")); success() }
            "switchBTModuleEnable" -> { api.deviceManager.switchBTModuleEnable(json.getBoolean("enable")); success() }
            "isBTModuleEnabled" -> success(api.deviceManager.isBTModuleEnabled)
            "setCustomKeyFunction" -> {
                api.deviceManager.setCustomKeyFunction(
                    json.getString("key"), json.getString("type"), json.getString("value")
                )
                success()
            }
            "setPowerButtonEnabled" -> { api.deviceManager.setPowerButtonEnabled(json.getBoolean("enabled")); success() }
            "isPowerButtonEnabled" -> success(api.deviceManager.isPowerButtonEnabled)
            "setWallpaper" -> { api.deviceManager.setWallpaper(json.getString("filePath")); success() }
            "setScreenBrightness" -> { api.deviceManager.setScreenBrightness(json.getInt("value")); success() }
            "getScreenBrightness" -> success(api.deviceManager.screenBrightness)
            "setScreenTimeout" -> { api.deviceManager.setScreenTimeout(json.getInt("timeout")); success() }
            "setBrightnessMode" -> { api.deviceManager.setBrightnessMode(json.getInt("mode")); success() }
            "getMinimumScreenBrightnessSetting" -> success(api.deviceManager.minimumScreenBrightnessSetting)
            "getMaximumScreenBrightnessSetting" -> success(api.deviceManager.maximumScreenBrightnessSetting)
            "setDeviceOwner" -> {
                api.deviceManager.setDeviceOwner(
                    json.getString("packageName"), json.getString("classFullPathName")
                )
                success()
            }
            "clearDeviceOwner" -> { api.deviceManager.clearDeviceOwner(); success() }
            "enableAutoTime" -> { api.deviceManager.enableAutoTime(json.getBoolean("enable")); success() }
            "enableAutoTimeZone" -> { api.deviceManager.enableAutoTimeZone(json.getBoolean("enable")); success() }
            "enableLocation" -> { api.deviceManager.enableLocation(json.getBoolean("enable")); success() }
            "set24Hour" -> { api.deviceManager.set24Hour(json.getBoolean("is24Hour")); success() }
            "setAirplaneMode" -> { api.deviceManager.setAirplaneMode(json.getBoolean("open")); success() }
            "setPhysicKeyEnabled" -> {
                api.deviceManager.setPhysicKeyEnabled(json.getInt("type"), json.getBoolean("enable"))
                success()
            }
            "getPhysicKeyEnabled" -> success(api.deviceManager.getPhysicKeyEnabled(json.getInt("type")))
            "enableGPS" -> { api.deviceManager.enableGPS(json.getBoolean("isEnable")); success() }
            "enableWIFI" -> { api.deviceManager.enableWIFI(json.getBoolean("isEnable")); success() }
            "enableNFC" -> { api.deviceManager.enableNFC(json.getBoolean("enable")); success() }
            else -> unsupportedMethod(method)
        }
    }
}
