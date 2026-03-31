package dev.duma.android.hal.plugins.sunmi.tms.device.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.contract.CommandResult
import org.json.JSONObject

internal class DeviceManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.device.device_manager.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "setSystemTime" -> {
                api.deviceManager.setSystemTime(
                    json.getInt("second"), json.getInt("minute"), json.getInt("hour"),
                    json.getInt("day"), json.getInt("month"), json.getInt("year")
                )
                CommandResult.Success()
            }
            "powerReboot" -> { api.deviceManager.powerReboot(); CommandResult.Success() }
            "shutdown" -> { api.deviceManager.shutdown(); CommandResult.Success() }
            "setTimeZone" -> { api.deviceManager.setTimeZone(json.getString("timeZone")); CommandResult.Success() }
            "factoryReset" -> { api.deviceManager.factoryReset(); CommandResult.Success() }
            "toSleep" -> { api.deviceManager.toSleep(); CommandResult.Success() }
            "toWakeUp" -> { api.deviceManager.toWakeUp(); CommandResult.Success() }
            "setBootAnimation" -> { api.deviceManager.setBootAnimation(json.getString("filePath")); CommandResult.Success() }
            "enableBluetooth" -> { api.deviceManager.enableBluetooth(json.getBoolean("enable")); CommandResult.Success() }
            "switchBTModuleEnable" -> { api.deviceManager.switchBTModuleEnable(json.getBoolean("enable")); CommandResult.Success() }
            "isBTModuleEnabled" -> CommandResult.Success(JSONObject().put("result", api.deviceManager.isBTModuleEnabled).toString())
            "setCustomKeyFunction" -> {
                api.deviceManager.setCustomKeyFunction(
                    json.getString("key"), json.getString("type"), json.getString("value")
                )
                CommandResult.Success()
            }
            "setPowerButtonEnabled" -> { api.deviceManager.setPowerButtonEnabled(json.getBoolean("enabled")); CommandResult.Success() }
            "isPowerButtonEnabled" -> CommandResult.Success(JSONObject().put("result", api.deviceManager.isPowerButtonEnabled).toString())
            "setWallpaper" -> { api.deviceManager.setWallpaper(json.getString("filePath")); CommandResult.Success() }
            "setScreenBrightness" -> { api.deviceManager.setScreenBrightness(json.getInt("value")); CommandResult.Success() }
            "getScreenBrightness" -> CommandResult.Success(JSONObject().put("result", api.deviceManager.screenBrightness).toString())
            "setScreenTimeout" -> { api.deviceManager.setScreenTimeout(json.getInt("timeout")); CommandResult.Success() }
            "setBrightnessMode" -> { api.deviceManager.setBrightnessMode(json.getInt("mode")); CommandResult.Success() }
            "getMinimumScreenBrightnessSetting" -> CommandResult.Success(JSONObject().put("result", api.deviceManager.minimumScreenBrightnessSetting).toString())
            "getMaximumScreenBrightnessSetting" -> CommandResult.Success(JSONObject().put("result", api.deviceManager.maximumScreenBrightnessSetting).toString())
            "setDeviceOwner" -> {
                api.deviceManager.setDeviceOwner(
                    json.getString("packageName"), json.getString("classFullPathName")
                )
                CommandResult.Success()
            }
            "clearDeviceOwner" -> { api.deviceManager.clearDeviceOwner(); CommandResult.Success() }
            "enableAutoTime" -> { api.deviceManager.enableAutoTime(json.getBoolean("enable")); CommandResult.Success() }
            "enableAutoTimeZone" -> { api.deviceManager.enableAutoTimeZone(json.getBoolean("enable")); CommandResult.Success() }
            "enableLocation" -> { api.deviceManager.enableLocation(json.getBoolean("enable")); CommandResult.Success() }
            "set24Hour" -> { api.deviceManager.set24Hour(json.getBoolean("is24Hour")); CommandResult.Success() }
            "setAirplaneMode" -> { api.deviceManager.setAirplaneMode(json.getBoolean("open")); CommandResult.Success() }
            "setPhysicKeyEnabled" -> {
                api.deviceManager.setPhysicKeyEnabled(json.getInt("type"), json.getBoolean("enable"))
                CommandResult.Success()
            }
            "getPhysicKeyEnabled" -> CommandResult.Success(JSONObject().put("result", api.deviceManager.getPhysicKeyEnabled(json.getInt("type"))).toString())
            "enableGPS" -> { api.deviceManager.enableGPS(json.getBoolean("isEnable")); CommandResult.Success() }
            "enableWIFI" -> { api.deviceManager.enableWIFI(json.getBoolean("isEnable")); CommandResult.Success() }
            "enableNFC" -> { api.deviceManager.enableNFC(json.getBoolean("enable")); CommandResult.Success() }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
