package dev.duma.android.hal.plugins.sunmi.tms.software.handler

import com.sunmi.tms.api.TMSApi
import com.sunmi.tmsmaster.aidl.networkmanager.IUnifiedCallback
import com.sunmi.tmsmaster.aidl.softwaremanager.OnInstallAppListener
import com.sunmi.tmsmaster.aidl.softwaremanager.OnUninstallAppListener
import dev.duma.android.hal.contract.CommandResult
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

internal class SoftwareManagerHandler(
    private val api: TMSApi,
    private val emitEvent: (String, String) -> Unit
) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.software.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "installApp" -> {
                val filePath = json.getString("appFilePath")
                api.softwareManager.installApp(filePath, object : OnInstallAppListener.Stub() {
                    override fun onInstallFinished() {}
                    override fun onInstallError(errorCode: Int) {
                        emitEvent("sunmi.tms.software.installError",
                            JSONObject().put("errorCode", errorCode).toString())
                    }
                    override fun onInstallSuccess(packageName: String?) {
                        emitEvent("sunmi.tms.software.installSuccess",
                            JSONObject().put("packageName", packageName ?: "").toString())
                    }
                    override fun onInstallFail(packageName: String?, errorId: Int) {
                        emitEvent("sunmi.tms.software.installFail",
                            JSONObject().put("packageName", packageName ?: "").put("errorId", errorId).toString())
                    }
                })
                CommandResult.Success()
            }
            "installAppV2" -> {
                val filePath = json.getString("appFilePath")
                val autoStart = json.optBoolean("autoStart", false)
                api.softwareManager.installAppV2(filePath, autoStart, object : OnInstallAppListener.Stub() {
                    override fun onInstallFinished() {}
                    override fun onInstallError(errorCode: Int) {
                        emitEvent("sunmi.tms.software.installError",
                            JSONObject().put("errorCode", errorCode).toString())
                    }
                    override fun onInstallSuccess(packageName: String?) {
                        emitEvent("sunmi.tms.software.installSuccess",
                            JSONObject().put("packageName", packageName ?: "").toString())
                    }
                    override fun onInstallFail(packageName: String?, errorId: Int) {
                        emitEvent("sunmi.tms.software.installFail",
                            JSONObject().put("packageName", packageName ?: "").put("errorId", errorId).toString())
                    }
                })
                CommandResult.Success()
            }
            "uninstallApp" -> {
                val packageName = json.getString("packageName")
                api.softwareManager.uninstallApp(packageName, object : OnUninstallAppListener.Stub() {
                    override fun onUnInstallFinished() {}
                    override fun onUnInstallError(errorCode: Int) {
                        emitEvent("sunmi.tms.software.uninstallError",
                            JSONObject().put("errorCode", errorCode).toString())
                    }
                    override fun onUnInstallSuccess(pkg: String?) {
                        emitEvent("sunmi.tms.software.uninstallSuccess",
                            JSONObject().put("packageName", pkg ?: "").toString())
                    }
                    override fun onUnInstallFail(pkg: String?, errorId: Int) {
                        emitEvent("sunmi.tms.software.uninstallFail",
                            JSONObject().put("packageName", pkg ?: "").put("errorId", errorId).toString())
                    }
                })
                CommandResult.Success()
            }
            "killApp" -> { api.softwareManager.killApp(json.getString("packageName")); CommandResult.Success() }
            "restartApp" -> { api.softwareManager.restartApp(json.getString("packageName")); CommandResult.Success() }
            "setBatteryOptimizationWhitelist" -> { api.softwareManager.setBatteryOptimizationWhitelist(json.getString("whitelist")); CommandResult.Success() }
            "setAppEnabled" -> { api.softwareManager.setAppEnabled(json.getString("packageName"), json.getBoolean("enabled")); CommandResult.Success() }
            "grantAppPermissions" -> { api.softwareManager.grantAppPermissions(json.getString("packageName"), json.getString("permissions")); CommandResult.Success() }
            "revokeAppPermission" -> { api.softwareManager.revokeAppPermission(json.getString("packageName"), json.getString("permissions")); CommandResult.Success() }
            "getRequestPermissions" -> CommandResult.Success(JSONObject().put("result", api.softwareManager.getRequestPermissions(json.getString("packageName"))).toString())
            "allowAlertWindowPermission" -> { api.softwareManager.allowAlertWindowPermission(json.getString("packageName")); CommandResult.Success() }
            "prohibitUninstall" -> { api.softwareManager.prohibitUninstall(json.getString("packageName"), json.getBoolean("allowUninstall")); CommandResult.Success() }
            "getProhibitUninstallList" -> CommandResult.Success(JSONObject().put("result", api.softwareManager.prohibitUninstallList).toString())
            "enableAutoStartApp" -> { api.softwareManager.enableAutoStartApp(json.getBoolean("enable")); CommandResult.Success() }
            "isAutoStartAppEnabled" -> CommandResult.Success(JSONObject().put("result", api.softwareManager.isAutoStartAppEnabled).toString())
            "setAutoStartApp" -> { api.softwareManager.setAutoStartApp(json.getString("packageName")); CommandResult.Success() }
            "getAutoStartApp" -> CommandResult.Success(JSONObject().put("result", api.softwareManager.autoStartApp).toString())
            "clearAutoStartApp" -> { api.softwareManager.clearAutoStartApp(); CommandResult.Success() }
            "clearApplicationUserData" -> {
                val result = api.softwareManager.clearApplicationUserData(json.getString("packageName"))
                CommandResult.Success(JSONObject().put("result", result).toString())
            }
            "deleteApplicationCacheFiles" -> {
                suspendCancellableCoroutine { cont ->
                    api.softwareManager.deleteApplicationCacheFiles(json.getString("packageName"), object : IUnifiedCallback.Stub() {
                        override fun onCall(result: String?) {
                            cont.resume(CommandResult.Success(result?.let { JSONObject().put("result", it).toString() }))
                        }
                    })
                }
            }
            "isForeground" -> CommandResult.Success(JSONObject().put("result", api.softwareManager.isForeground(json.getString("packageName"))).toString())
            "addAppToCommonAppLockList" -> {
                val arr = json.getJSONArray("packageNames")
                val list = (0 until arr.length()).map { arr.getString(it) }
                api.softwareManager.addAppToCommonAppLockList(list, json.getInt("type"), json.getString("password"))
                CommandResult.Success()
            }
            "removeAppFromCommonAppLockList" -> {
                val arr = json.getJSONArray("packageNames")
                val list = (0 until arr.length()).map { arr.getString(it) }
                api.softwareManager.removeAppFromCommonAppLockList(list)
                CommandResult.Success()
            }
            "getCommonAppLockList" -> {
                val list = api.softwareManager.commonAppLockList
                CommandResult.Success(JSONObject().put("result", JSONArray(list)).toString())
            }
            "isCommonAppLock" -> CommandResult.Success(JSONObject().put("result", api.softwareManager.isCommonAppLock(json.getString("packageName"))).toString())
            "setNotificationsEnabledForPackage" -> {
                api.softwareManager.setNotificationsEnabledForPackage(json.getString("packageName"), json.getBoolean("enabled"))
                CommandResult.Success()
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
