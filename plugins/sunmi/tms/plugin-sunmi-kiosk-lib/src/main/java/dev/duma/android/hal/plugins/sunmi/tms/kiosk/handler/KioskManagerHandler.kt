package dev.duma.android.hal.plugins.sunmi.tms.kiosk.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import org.json.JSONArray
import org.json.JSONObject

internal class KioskManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): String {
        val op = method.removePrefix("sunmi.tms.kiosk.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "enableKioskFunction" -> { api.kioskManager.enableKioskFunction(json.getBoolean("enable")); success() }
            "isKioskFunctionEnabled" -> success(api.kioskManager.isKioskFunctionEnabled)
            "getKioskModeStatus" -> success(api.kioskManager.kioskModeStatus)
            "addAppToKioskList" -> {
                val arr = json.getJSONArray("packageNames")
                val list = (0 until arr.length()).map { arr.getString(it) }
                api.kioskManager.addAppToKioskList(list)
                success()
            }
            "removeAppFromKioskList" -> {
                val arr = json.getJSONArray("packageNames")
                val list = (0 until arr.length()).map { arr.getString(it) }
                api.kioskManager.removeAppFromKioskList(list)
                success()
            }
            "getKioskAppList" -> {
                val list = api.kioskManager.kioskAppList
                success(JSONArray(list))
            }
            "isKioskApp" -> success(api.kioskManager.isKioskApp(json.getString("packageName")))
            "setKioskPwdByType" -> { api.kioskManager.setKioskPwdByType(json.getInt("type"), json.getString("psw")); success() }
            "setNavigationBarStatusForKiosk" -> { api.kioskManager.setNavigationBarStatusForKiosk(json.getInt("status")); success() }
            "getNavigationBarStatusForKiosk" -> success(api.kioskManager.navigationBarStatusForKiosk)
            "hideStatusBarForKiosk" -> { api.kioskManager.hideStatusBarForKiosk(json.getBoolean("hide")); success() }
            "isStatusBarHiddenForKiosk" -> success(api.kioskManager.isStatusBarHiddenForKiosk)
            "switchKioskPwdByType" -> { api.kioskManager.switchKioskPwdByType(json.getInt("type")); success() }
            "getSwitchKioskPwdByType" -> success(api.kioskManager.switchKioskPwdByType)
            "exitKioskMode" -> { api.kioskManager.exitKioskMode(json.getString("password")); success() }
            else -> unsupportedMethod(method)
        }
    }
}
