package dev.duma.android.hal.plugins.sunmi.tms.system.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import org.json.JSONObject

internal class SystemUiManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): String {
        val op = method.removePrefix("sunmi.tms.system.system_ui.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "showNavigationBarBackButton" -> { api.systemUIManager.showNavigationBarBackButton(json.getBoolean("show")); success() }
            "showNavigationBarHomeButton" -> { api.systemUIManager.showNavigationBarHomeButton(json.getBoolean("show")); success() }
            "showNavigationBarRecentsButton" -> { api.systemUIManager.showNavigationBarRecentsButton(json.getBoolean("show")); success() }
            "showNavigationBar" -> { api.systemUIManager.showNavigationBar(json.getBoolean("show")); success() }
            "showStatusBar" -> { api.systemUIManager.showStatusBar(json.getBoolean("show")); success() }
            "enableNavigationBarBackButton" -> { api.systemUIManager.enableNavigationBarBackButton(json.getBoolean("enable")); success() }
            "isNavigationBarBackButtonEnabled" -> success(api.systemUIManager.isNavigationBarBackButtonEnabled)
            "enableNavigationBarHomeButton" -> { api.systemUIManager.enableNavigationBarHomeButton(json.getBoolean("enable")); success() }
            "isNavigationBarHomeButtonEnabled" -> success(api.systemUIManager.isNavigationBarHomeButtonEnabled)
            "enableNavigationBarRecentsButton" -> { api.systemUIManager.enableNavigationBarRecentsButton(json.getBoolean("enable")); success() }
            "isNavigationBarRecentsButtonEnabled" -> success(api.systemUIManager.isNavigationBarRecentsButtonEnabled)
            "enableNotificationPanel" -> { api.systemUIManager.enableNotificationPanel(json.getBoolean("enable")); success() }
            "clickableNavigationBar" -> { api.systemUIManager.clickableNavigationBar(json.getBoolean("clickable")); success() }
            "setAirplaneModeBarClickable" -> { api.systemUIManager.setAirplaneModeBarClickable(json.getBoolean("clickable")); success() }
            else -> unsupportedMethod(method)
        }
    }
}
