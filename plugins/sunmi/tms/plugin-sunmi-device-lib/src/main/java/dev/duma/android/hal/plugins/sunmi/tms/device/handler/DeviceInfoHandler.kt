package dev.duma.android.hal.plugins.sunmi.tms.device.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.contract.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class DeviceInfoHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.device.device_info.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "getSerialNo"                  -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.serialNo).toString())
            "getModel"                     -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.model).toString())
            "getManufacture"               -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.manufacture).toString())
            "getBrand"                     -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.brand).toString())
            "getAndroidOSVersion"          -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.androidOSVersion).toString())
            "getAndroidKernelVersion"      -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.androidKernelVersion).toString())
            "getROMVersion"                -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.romVersion).toString())
            "getFirmwareVersion"           -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.firmwareVersion).toString())
            "getHardwareVersion"           -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.hardwareVersion).toString())
            "getSDKVersion"                -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.sdkVersion).toString())
            "getSunmiOsVersion"            -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.sunmiOsVersion).toString())
            "getSystemBuildDisplayId"      -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.systemBuildDisplayId).toString())
            "getMemoryOccupation"          -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.memoryOccupation).toString())
            "getMac"                       -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.mac).toString())
            "getDeviceBluetoothMacAddress" -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.deviceBluetoothMacAddress).toString())
            "getIpAddresses"               -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.ipAddresses).toString())
            "getIMEI"                      -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.imei).toString())
            "getIMSI"                      -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.imsi).toString())
            "isFinancialDevice"            -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.isFinancialDevice).toString())
            "isInnerScannerSupported"      -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.isInnerScannerSupported).toString())
            "isInnerPrinterSupported"      -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.isInnerPrinterSupported).toString())
            "getGmsFlag"                   -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.gmsFlag).toString())
            "getIMEIBySlotIndex"  -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.getIMEIBySlotIndex(json.getInt("slotIndex"))).toString())
            "getIMSIBySlotIndex"  -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.getIMSIBySlotIndex(json.getInt("slotIndex"))).toString())
            "getFullICCID"        -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.getFullICCID(json.getInt("slotIndex"))).toString())
            "getSystemProperty"   -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.getSystemProperty(json.getString("key"))).toString())
            "verifyCrp"           -> withContext(Dispatchers.IO) {
                CommandResult.Success(JSONObject().put("result", api.deviceInfo.verifyCrp(json.getString("filePath"))).toString())
            }
            "installCrp"          -> withContext(Dispatchers.IO) {
                CommandResult.Success(JSONObject().put("result", api.deviceInfo.installCrp(json.getString("filePath"))).toString())
            }
            "getCrpType"          -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.getCrpType(json.getString("filePath"))).toString())
            "getCrpVersion"       -> CommandResult.Success(JSONObject().put("result", api.deviceInfo.getCrpVersion(json.getInt("crpType"))).toString())
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
