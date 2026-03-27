package dev.duma.android.hal.plugins.sunmi.tms.device.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class DeviceInfoHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): String {
        val op = method.removePrefix("sunmi.tms.device.device_info.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "getSerialNo"                  -> success(api.deviceInfo.serialNo)
            "getModel"                     -> success(api.deviceInfo.model)
            "getManufacture"               -> success(api.deviceInfo.manufacture)
            "getBrand"                     -> success(api.deviceInfo.brand)
            "getAndroidOSVersion"          -> success(api.deviceInfo.androidOSVersion)
            "getAndroidKernelVersion"      -> success(api.deviceInfo.androidKernelVersion)
            "getROMVersion"                -> success(api.deviceInfo.romVersion)
            "getFirmwareVersion"           -> success(api.deviceInfo.firmwareVersion)
            "getHardwareVersion"           -> success(api.deviceInfo.hardwareVersion)
            "getSDKVersion"                -> success(api.deviceInfo.sdkVersion)
            "getSunmiOsVersion"            -> success(api.deviceInfo.sunmiOsVersion)
            "getSystemBuildDisplayId"      -> success(api.deviceInfo.systemBuildDisplayId)
            "getMemoryOccupation"          -> success(api.deviceInfo.memoryOccupation)
            "getMac"                       -> success(api.deviceInfo.mac)
            "getDeviceBluetoothMacAddress" -> success(api.deviceInfo.deviceBluetoothMacAddress)
            "getIpAddresses"               -> success(api.deviceInfo.ipAddresses)
            "getIMEI"                      -> success(api.deviceInfo.imei)
            "getIMSI"                      -> success(api.deviceInfo.imsi)
            "isFinancialDevice"            -> success(api.deviceInfo.isFinancialDevice)
            "isInnerScannerSupported"      -> success(api.deviceInfo.isInnerScannerSupported)
            "isInnerPrinterSupported"      -> success(api.deviceInfo.isInnerPrinterSupported)
            "getGmsFlag"                   -> success(api.deviceInfo.gmsFlag)
            "getIMEIBySlotIndex"  -> success(api.deviceInfo.getIMEIBySlotIndex(json.getInt("slotIndex")))
            "getIMSIBySlotIndex"  -> success(api.deviceInfo.getIMSIBySlotIndex(json.getInt("slotIndex")))
            "getFullICCID"        -> success(api.deviceInfo.getFullICCID(json.getInt("slotIndex")))
            "getSystemProperty"   -> success(api.deviceInfo.getSystemProperty(json.getString("key")))
            "verifyCrp"           -> withContext(Dispatchers.IO) {
                success(api.deviceInfo.verifyCrp(json.getString("filePath")))
            }
            "installCrp"          -> withContext(Dispatchers.IO) {
                success(api.deviceInfo.installCrp(json.getString("filePath")))
            }
            "getCrpType"          -> success(api.deviceInfo.getCrpType(json.getString("filePath")))
            "getCrpVersion"       -> success(api.deviceInfo.getCrpVersion(json.getInt("crpType")))
            else -> unsupportedMethod(method)
        }
    }
}
