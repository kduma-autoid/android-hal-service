package dev.duma.android.hal.plugins.sunmi.tms.system.handler

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.contract.CommandResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal class SystemManagerHandler(
    private val api: TMSApi,
    private val emitEvent: (String, String) -> Unit
) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.system.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "updateSystem" -> {
                api.systemManager.updateSystem(json.getString("systemPath"), object : com.sunmi.tmsmaster.aidl.systemmanager.listener.OnSystemUpdateListener.Stub() {
                    override fun progress(progress: Int) {
                        emitEvent("sunmi.tms.system.updateProgress", JSONObject().put("progress", progress).toString())
                    }
                    override fun verifyPackageFail(info: String?) {
                        emitEvent("sunmi.tms.system.updateFail", JSONObject().put("info", info ?: "").toString())
                    }
                    override fun updateSystemFail(info: String?) {
                        emitEvent("sunmi.tms.system.updateFail", JSONObject().put("info", info ?: "").toString())
                    }
                })
                CommandResult.Success()
            }
            "setDefaultLauncher" -> { api.systemManager.setDefaultLauncher(json.getString("packageName")); CommandResult.Success() }
            "doScreenshot" -> {
                val pfd = api.systemManager.doScreenshot()
                if (pfd == null) {
                    CommandResult.Success()
                } else {
                    val bitmap = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                    pfd.close()
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    bitmap.recycle()
                    val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    CommandResult.Success(JSONObject().put("result", base64).toString())
                }
            }
            "enableCommonAppLock" -> { api.systemManager.enableCommonAppLock(json.getBoolean("enable")); CommandResult.Success() }
            "isCommonAppLockEnabled" -> CommandResult.Success(JSONObject().put("result", api.systemManager.isCommonAppLockEnabled).toString())
            "setSystemLanguage" -> { api.systemManager.setSystemLanguage(json.getString("language")); CommandResult.Success() }
            "disableSecurityPCI24HoursReboot" -> { api.systemManager.disableSecurityPCI24HoursReboot(json.getBoolean("disable")); CommandResult.Success() }
            "set24HourRebootRegular" -> {
                api.systemManager.set24HourRebootRegular(
                    json.getInt("type"), json.getInt("hour"), json.getInt("minute"), json.getInt("second")
                )
                CommandResult.Success()
            }
            "setSecurityCenterPwd" -> { api.systemManager.setSecurityCenterPwd(json.getString("pwd")); CommandResult.Success() }
            "enableAdb" -> { api.systemManager.setAdbEnabled(json.getBoolean("enable")); CommandResult.Success() }
            "isAdbEnabled" -> CommandResult.Success(JSONObject().put("result", api.systemManager.isAdbEnabled).toString())
            "queryAppUsageState" -> {
                val list = api.systemManager.queryAppUsageState(
                    json.getInt("intervalType"), json.getLong("beginTime"), json.getLong("endTime")
                )
                val arr = JSONArray()
                list?.forEach { item ->
                    arr.put(JSONObject().apply {
                        put("packageName", item.packageName)
                        put("beginTimeStamp", item.beginTimeStamp)
                        put("endTimeStamp", item.endTimeStamp)
                        put("lastTimeUsed", item.lastTimeUsed)
                        put("totalTimeInForeground", item.totalTimeInForeground)
                        put("launchCount", item.launchCount)
                        put("lastEvent", item.lastEvent)
                    })
                }
                CommandResult.Success(JSONObject().put("result", arr).toString())
            }
            "getSystemPackageList" -> {
                val list = api.systemManager.systemPackageList
                CommandResult.Success(JSONObject().put("result", JSONArray(list)).toString())
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
