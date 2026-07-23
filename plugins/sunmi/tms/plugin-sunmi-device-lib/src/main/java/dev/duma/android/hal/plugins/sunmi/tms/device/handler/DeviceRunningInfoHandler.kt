package dev.duma.android.hal.plugins.sunmi.tms.device.handler

import com.sunmi.tms.api.TMSApi
import com.sunmi.tmsmaster.aidl.devicerunninginfo.listener.GPSLocationListener
import com.sunmi.tmsmaster.aidl.devicerunninginfo.listener.NetworkLocationListener
import dev.duma.android.hal.contract.CommandResult
import org.json.JSONObject

internal class DeviceRunningInfoHandler(
    private val api: TMSApi,
    private val emitEvent: (String, String) -> Unit
) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.device.runtime.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "getGPSLocation" -> {
                val location = api.deviceRunningInfo.gpsLocation
                if (location == null) {
                    CommandResult.Success()
                } else {
                    val obj = JSONObject()
                    for ((k, v) in location) { obj.put(k.toString(), v) }
                    CommandResult.Success(obj.toString())
                }
            }
            "getGPSLocationWithTimeout" -> {
                val timeout = json.getLong("timeout")
                api.deviceRunningInfo.getGPSLocationWithTimeout(object : GPSLocationListener.Stub() {
                    override fun onGPSLocationChanged(location: MutableMap<Any?, Any?>?) {
                        if (location != null) {
                            val obj = JSONObject()
                            location.forEach { (k, v) -> obj.put(k.toString(), v) }
                            emitEvent("sunmi.tms.device.runtime.gpsLocationChanged", obj.toString())
                        }
                    }
                }, timeout)
                CommandResult.Success()
            }
            "getNetworkLocationWithTimeout" -> {
                val timeout = json.getLong("timeout")
                api.deviceRunningInfo.getNetworkLocationWithTimeout(object : NetworkLocationListener.Stub() {
                    override fun onLocationChanged(location: MutableMap<Any?, Any?>?) {
                        if (location != null) {
                            val obj = JSONObject()
                            location.forEach { (k, v) -> obj.put(k.toString(), v) }
                            emitEvent("sunmi.tms.device.runtime.networkLocationChanged", obj.toString())
                        }
                    }
                }, timeout)
                CommandResult.Success()
            }
            "getBatteryCapacity" -> CommandResult.Success(JSONObject().put("result", api.deviceRunningInfo.batteryCapacity).toString())
            "getRamTotalSize" -> CommandResult.Success(JSONObject().put("result", api.deviceRunningInfo.ramTotalSize).toString())
            "getRamUsedSize" -> CommandResult.Success(JSONObject().put("result", api.deviceRunningInfo.ramUsedSize).toString())
            "getRomTotalSize" -> CommandResult.Success(JSONObject().put("result", api.deviceRunningInfo.romTotalSize).toString())
            "getRomUsedSize" -> CommandResult.Success(JSONObject().put("result", api.deviceRunningInfo.romUsedSize).toString())
            "getCpuUsage" -> CommandResult.Success(JSONObject().put("result", api.deviceRunningInfo.cpuUsage).toString())
            "getTurnOnMills" -> CommandResult.Success(JSONObject().put("result", api.deviceRunningInfo.turnOnMills).toString())
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
