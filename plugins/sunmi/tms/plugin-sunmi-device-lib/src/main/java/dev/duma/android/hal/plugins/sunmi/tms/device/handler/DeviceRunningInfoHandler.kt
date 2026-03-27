package dev.duma.android.hal.plugins.sunmi.tms.device.handler

import com.sunmi.tms.api.TMSApi
import com.sunmi.tmsmaster.aidl.devicerunninginfo.listener.GPSLocationListener
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

internal class DeviceRunningInfoHandler(
    private val api: TMSApi,
    private val emitEvent: (String, String) -> Unit
) {

    suspend fun handle(method: String, params: String): String {
        val op = method.removePrefix("sunmi.tms.device.runtime.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "getGPSLocation" -> {
                val location = api.deviceRunningInfo.gpsLocation
                if (location == null) {
                    success(null)
                } else {
                    val obj = JSONObject()
                    for ((k, v) in location) { obj.put(k.toString(), v) }
                    success(obj)
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
                success()
            }
            "getBatteryCapacity" -> success(api.deviceRunningInfo.batteryCapacity)
            "getRamTotalSize" -> success(api.deviceRunningInfo.ramTotalSize)
            "getRamUsedSize" -> success(api.deviceRunningInfo.ramUsedSize)
            "getRomTotalSize" -> success(api.deviceRunningInfo.romTotalSize)
            "getRomUsedSize" -> success(api.deviceRunningInfo.romUsedSize)
            "getCpuUsage" -> success(api.deviceRunningInfo.cpuUsage)
            "getTurnOnMills" -> success(api.deviceRunningInfo.turnOnMills)
            else -> unsupportedMethod(method)
        }
    }
}
