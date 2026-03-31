package dev.duma.android.hal.plugins.sunmi.tms.kiosk.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.contract.CommandResult
import org.json.JSONArray
import org.json.JSONObject

internal class KioskManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.kiosk.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "enableKioskFunction" -> { api.kioskManager.enableKioskFunction(json.getBoolean("enable")); CommandResult.Success() }
            "isKioskFunctionEnabled" -> CommandResult.Success(JSONObject().put("result", api.kioskManager.isKioskFunctionEnabled).toString())
            "getKioskModeStatus" -> CommandResult.Success(JSONObject().put("result", api.kioskManager.kioskModeStatus).toString())
            "addAppToKioskList" -> {
                val arr = json.getJSONArray("packageNames")
                val list = (0 until arr.length()).map { arr.getString(it) }
                api.kioskManager.addAppToKioskList(list)
                CommandResult.Success()
            }
            "removeAppFromKioskList" -> {
                val arr = json.getJSONArray("packageNames")
                val list = (0 until arr.length()).map { arr.getString(it) }
                api.kioskManager.removeAppFromKioskList(list)
                CommandResult.Success()
            }
            "getKioskAppList" -> {
                val list = api.kioskManager.kioskAppList
                CommandResult.Success(JSONObject().put("result", JSONArray(list)).toString())
            }
            "isKioskApp" -> CommandResult.Success(JSONObject().put("result", api.kioskManager.isKioskApp(json.getString("packageName"))).toString())
            "setKioskPwdByType" -> { api.kioskManager.setKioskPwdByType(json.getInt("type"), json.getString("psw")); CommandResult.Success() }
            "setNavigationBarStatusForKiosk" -> { api.kioskManager.setNavigationBarStatusForKiosk(json.getInt("status")); CommandResult.Success() }
            "getNavigationBarStatusForKiosk" -> CommandResult.Success(JSONObject().put("result", api.kioskManager.navigationBarStatusForKiosk).toString())
            "hideStatusBarForKiosk" -> { api.kioskManager.hideStatusBarForKiosk(json.getBoolean("hide")); CommandResult.Success() }
            "isStatusBarHiddenForKiosk" -> CommandResult.Success(JSONObject().put("result", api.kioskManager.isStatusBarHiddenForKiosk).toString())
            "switchKioskPwdByType" -> { api.kioskManager.switchKioskPwdByType(json.getInt("type")); CommandResult.Success() }
            "getSwitchKioskPwdByType" -> CommandResult.Success(JSONObject().put("result", api.kioskManager.switchKioskPwdByType).toString())
            "exitKioskMode" -> { api.kioskManager.exitKioskMode(json.getString("password")); CommandResult.Success() }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
