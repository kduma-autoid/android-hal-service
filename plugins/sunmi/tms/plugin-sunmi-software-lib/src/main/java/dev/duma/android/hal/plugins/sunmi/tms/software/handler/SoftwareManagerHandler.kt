package dev.duma.android.hal.plugins.sunmi.tms.software.handler

import com.sunmi.tms.api.TMSApi
import com.sunmi.tmsmaster.aidl.networkmanager.IUnifiedCallback
import com.sunmi.tmsmaster.aidl.softwaremanager.OnInstallAppListener
import com.sunmi.tmsmaster.aidl.softwaremanager.OnUninstallAppListener
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

internal class SoftwareManagerHandler(
    private val api: TMSApi,
    private val emitEvent: (String, String) -> Unit
) {

    suspend fun handle(method: String, params: String): String {
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
                success()
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
                success()
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
                success()
            }
            "killApp" -> { api.softwareManager.killApp(json.getString("packageName")); success() }
            "restartApp" -> { api.softwareManager.restartApp(json.getString("packageName")); success() }
            "setBatteryOptimizationWhitelist" -> { api.softwareManager.setBatteryOptimizationWhitelist(json.getString("whitelist")); success() }
            "setAppEnabled" -> { api.softwareManager.setAppEnabled(json.getString("packageName"), json.getBoolean("enabled")); success() }
            "grantAppPermissions" -> { api.softwareManager.grantAppPermissions(json.getString("packageName"), json.getString("permissions")); success() }
            "revokeAppPermission" -> { api.softwareManager.revokeAppPermission(json.getString("packageName"), json.getString("permissions")); success() }
            "getRequestPermissions" -> success(api.softwareManager.getRequestPermissions(json.getString("packageName")))
            "allowAlertWindowPermission" -> { api.softwareManager.allowAlertWindowPermission(json.getString("packageName")); success() }
            "prohibitUninstall" -> { api.softwareManager.prohibitUninstall(json.getString("packageName"), json.getBoolean("allowUninstall")); success() }
            "getProhibitUninstallList" -> success(api.softwareManager.prohibitUninstallList)
            "enableAutoStartApp" -> { api.softwareManager.enableAutoStartApp(json.getBoolean("enable")); success() }
            "isAutoStartAppEnabled" -> success(api.softwareManager.isAutoStartAppEnabled)
            "setAutoStartApp" -> { api.softwareManager.setAutoStartApp(json.getString("packageName")); success() }
            "getAutoStartApp" -> success(api.softwareManager.autoStartApp)
            "clearAutoStartApp" -> { api.softwareManager.clearAutoStartApp(); success() }
            "clearApplicationUserData" -> {
                val result = api.softwareManager.clearApplicationUserData(json.getString("packageName"))
                success(result)
            }
            "deleteApplicationCacheFiles" -> {
                suspendCancellableCoroutine { cont ->
                    api.softwareManager.deleteApplicationCacheFiles(json.getString("packageName"), object : IUnifiedCallback.Stub() {
                        override fun onCall(result: String?) {
                            cont.resume(success(result))
                        }
                    })
                }
            }
            "isForeground" -> success(api.softwareManager.isForeground(json.getString("packageName")))
            "addAppToCommonAppLockList" -> {
                val arr = json.getJSONArray("packageNames")
                val list = (0 until arr.length()).map { arr.getString(it) }
                api.softwareManager.addAppToCommonAppLockList(list, json.getInt("type"), json.getString("password"))
                success()
            }
            "removeAppFromCommonAppLockList" -> {
                val arr = json.getJSONArray("packageNames")
                val list = (0 until arr.length()).map { arr.getString(it) }
                api.softwareManager.removeAppFromCommonAppLockList(list)
                success()
            }
            "getCommonAppLockList" -> {
                val list = api.softwareManager.commonAppLockList
                success(JSONArray(list))
            }
            "isCommonAppLock" -> success(api.softwareManager.isCommonAppLock(json.getString("packageName")))
            "setNotificationsEnabledForPackage" -> {
                api.softwareManager.setNotificationsEnabledForPackage(json.getString("packageName"), json.getBoolean("enabled"))
                success()
            }
            else -> unsupportedMethod(method)
        }
    }
}
