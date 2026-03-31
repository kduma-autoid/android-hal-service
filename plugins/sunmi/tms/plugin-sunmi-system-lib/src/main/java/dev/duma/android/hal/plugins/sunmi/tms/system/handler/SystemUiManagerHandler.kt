package dev.duma.android.hal.plugins.sunmi.tms.system.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.contract.CommandResult
import org.json.JSONObject

internal class SystemUiManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.system.system_ui.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "showNavigationBarBackButton" -> { api.systemUIManager.showNavigationBarBackButton(json.getBoolean("show")); CommandResult.Success() }
            "showNavigationBarHomeButton" -> { api.systemUIManager.showNavigationBarHomeButton(json.getBoolean("show")); CommandResult.Success() }
            "showNavigationBarRecentsButton" -> { api.systemUIManager.showNavigationBarRecentsButton(json.getBoolean("show")); CommandResult.Success() }
            "showNavigationBar" -> { api.systemUIManager.showNavigationBar(json.getBoolean("show")); CommandResult.Success() }
            "showStatusBar" -> { api.systemUIManager.showStatusBar(json.getBoolean("show")); CommandResult.Success() }
            "enableNavigationBarBackButton" -> { api.systemUIManager.enableNavigationBarBackButton(json.getBoolean("enable")); CommandResult.Success() }
            "isNavigationBarBackButtonEnabled" -> CommandResult.Success(JSONObject().put("result", api.systemUIManager.isNavigationBarBackButtonEnabled).toString())
            "enableNavigationBarHomeButton" -> { api.systemUIManager.enableNavigationBarHomeButton(json.getBoolean("enable")); CommandResult.Success() }
            "isNavigationBarHomeButtonEnabled" -> CommandResult.Success(JSONObject().put("result", api.systemUIManager.isNavigationBarHomeButtonEnabled).toString())
            "enableNavigationBarRecentsButton" -> { api.systemUIManager.enableNavigationBarRecentsButton(json.getBoolean("enable")); CommandResult.Success() }
            "isNavigationBarRecentsButtonEnabled" -> CommandResult.Success(JSONObject().put("result", api.systemUIManager.isNavigationBarRecentsButtonEnabled).toString())
            "enableNotificationPanel" -> { api.systemUIManager.enableNotificationPanel(json.getBoolean("enable")); CommandResult.Success() }
            "clickableNavigationBar" -> { api.systemUIManager.clickableNavigationBar(json.getBoolean("clickable")); CommandResult.Success() }
            "setAirplaneModeBarClickable" -> { api.systemUIManager.setAirplaneModeBarClickable(json.getBoolean("clickable")); CommandResult.Success() }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
