package dev.duma.android.hal.plugins.sunmi.tms.network.handler

import android.os.Bundle
import android.util.Base64
import com.sunmi.tms.api.TMSApi
import com.sunmi.tmsmaster.aidl.networkmanager.IUnifiedCallback
import com.sunmi.tmsmaster.aidl.networkmanager.WifiConfigurationInfo
import dev.duma.android.hal.contract.CommandResult
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

internal class NetworkManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.network.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "enableMobileNetwork" -> { api.networkManager.enableMobileNetwork(json.getInt("slotIdx"), json.getBoolean("enable")); CommandResult.Success() }
            "getDefaultDataSlotIndex" -> CommandResult.Success(JSONObject().put("result", api.networkManager.defaultDataSlotIndex).toString())
            "setDefaultDataSlotIndex" -> { api.networkManager.setDefaultDataSlotIndex(json.getInt("slotIndex")); CommandResult.Success() }
            "setMobileDataLocked" -> { api.networkManager.setMobileDataLocked(json.getBoolean("locked")); CommandResult.Success() }
            "getMobileDataStatus" -> CommandResult.Success(JSONObject().put("result", api.networkManager.mobileDataStatus).toString())
            "getMobileDataStatusBySlot" -> CommandResult.Success(JSONObject().put("result", api.networkManager.getMobileDataStatusBySlot(json.getInt("slotIndex"))).toString())
            "getDataRoamingEnabled" -> CommandResult.Success(JSONObject().put("result", api.networkManager.getDataRoamingEnabled(json.getInt("slotIndex"))).toString())
            "setDataRoamingEnabled" -> { api.networkManager.setDataRoamingEnabled(json.getInt("slotIndex"), json.getBoolean("enable")); CommandResult.Success() }
            "isActiveSlotIndex" -> CommandResult.Success(JSONObject().put("result", api.networkManager.isActiveSlotIndex(json.getInt("slotIndex"))).toString())
            "enableWIFI" -> { api.networkManager.enableWifi(json.getBoolean("isEnable")); CommandResult.Success() }
            "connectWifiSsid" -> {
                suspendCancellableCoroutine { cont ->
                    api.networkManager.connectWifiSsid(json.getString("ssid"), object : IUnifiedCallback.Stub() {
                        override fun onCall(result: String?) { cont.resume(CommandResult.Success(result?.let { JSONObject().put("result", it).toString() })) }
                    })
                }
            }
            "removeWifiSsid" -> {
                suspendCancellableCoroutine { cont ->
                    api.networkManager.removeWifiSsid(json.getString("ssid"), object : IUnifiedCallback.Stub() {
                        override fun onCall(result: String?) { cont.resume(CommandResult.Success(result?.let { JSONObject().put("result", it).toString() })) }
                    })
                }
            }
            "forgetSavedWifi" -> { api.networkManager.forgetSavedWifi(); CommandResult.Success() }
            "addNetwork" -> {
                val bundle = Bundle().apply {
                    putString("ssid", json.getString("ssid"))
                    putString("preSharedKey", json.optString("preSharedKey", ""))
                }
                CommandResult.Success(JSONObject().put("result", api.networkManager.addNetwork(bundle)).toString())
            }
            "updateNetwork" -> {
                val bundle = Bundle().apply {
                    putString("ssid", json.getString("ssid"))
                    putString("preSharedKey", json.optString("preSharedKey", ""))
                    putInt("networkId", json.getInt("networkId"))
                }
                CommandResult.Success(JSONObject().put("result", api.networkManager.updateNetwork(bundle)).toString())
            }
            "removeNetwork" -> { api.networkManager.removeNetwork(json.getInt("networkId")); CommandResult.Success() }
            "getConfiguredNetworks" -> {
                val bundles = api.networkManager.configuredNetworks
                val arr = JSONArray()
                bundles?.forEach { b ->
                    arr.put(JSONObject().apply {
                        put("ssid", b.getString("ssid"))
                        put("networkId", b.getInt("networkId"))
                    })
                }
                CommandResult.Success(JSONObject().put("result", arr).toString())
            }
            "addWifiSsidByWifiConfiguration" -> {
                val config = WifiConfigurationInfo()
                config.ssid = json.getString("ssid")
                config.pwd = json.optString("pwd", "")
                config.security_type = json.optInt("security_type", 0)
                suspendCancellableCoroutine { cont ->
                    api.networkManager.addWifiSsidByWifiConfiguration(config, object : IUnifiedCallback.Stub() {
                        override fun onCall(result: String?) { cont.resume(CommandResult.Success(result?.let { JSONObject().put("result", it).toString() })) }
                    })
                }
            }
            "setWifiStaticIp" -> {
                suspendCancellableCoroutine { cont ->
                    api.networkManager.setWifiStaticIpV2(
                        json.getBoolean("status"),
                        json.optString("ipAddr", ""),
                        json.optString("gateway", ""),
                        json.optInt("networkPrefixLength", 24),
                        json.optString("dns1", ""),
                        json.optString("dns2", ""),
                        json.optBoolean("reconnect", false),
                        object : IUnifiedCallback.Stub() {
                            override fun onCall(result: String?) { cont.resume(CommandResult.Success(result?.let { JSONObject().put("result", it).toString() })) }
                        }
                    )
                }
            }
            "resetNetworkSettings" -> { api.networkManager.resetNetworkSettings(); CommandResult.Success() }
            "installWlanCertificate" -> {
                val bytes = Base64.decode(json.getString("certData"), Base64.DEFAULT)
                api.networkManager.installWlanCertificate(json.getString("name"), bytes, json.optString("password", ""))
                CommandResult.Success()
            }
            "setCustomNtpServer" -> { api.networkManager.setCustomNtpServer(json.getString("server")); CommandResult.Success() }
            "getCustomNtpServer" -> CommandResult.Success(JSONObject().put("result", api.networkManager.customNtpServer).toString())
            "turnOnWifiHotspot" -> {
                api.networkManager.turnOnWifiHotspot(json.getString("ssid"), json.getString("preShareKey"), json.getInt("keyManagement"))
                CommandResult.Success()
            }
            "turnOffWifiHotspot" -> { api.networkManager.turnOffWifiHotspot(); CommandResult.Success() }
            "isWifiHotspotEnable" -> CommandResult.Success(JSONObject().put("result", api.networkManager.isWifiHotspotEnable).toString())
            "showWifiHotspotSettings" -> { api.networkManager.showWifiHotspotSettings(); CommandResult.Success() }
            "disableWifiHotspotAndHideSettings" -> { api.networkManager.disableWifiHotspotAndHideSettings(); CommandResult.Success() }
            "enableEthernet" -> { api.networkManager.enableEthernet(json.getBoolean("enable")); CommandResult.Success() }
            "getTrafficOfEachApp" -> {
                val list = api.networkManager.getTrafficOfEachApp(json.getInt("networkType"), json.getLong("startTime"), json.getLong("endTime"))
                val arr = JSONArray()
                list?.forEach { item ->
                    val db = item.dataBucket
                    arr.put(JSONObject().apply {
                        put("packageName", item.packageName)
                        put("uid", item.uid)
                        if (db != null) {
                            put("rxBytes", db.rxBytes)
                            put("txBytes", db.txBytes)
                            put("rxPackets", db.rxPackets)
                            put("txPackets", db.txPackets)
                            put("beginTimeStamp", db.beginTimeStamp)
                            put("endTimeStamp", db.endTimeStamp)
                        }
                    })
                }
                CommandResult.Success(JSONObject().put("result", arr).toString())
            }
            "queryTrafficUsageSummary" -> {
                val bucket = api.networkManager.queryTrafficUsageSummary(json.getInt("networkType"), json.getLong("startTime"), json.getLong("endTime"))
                if (bucket == null) {
                    CommandResult.Success()
                } else {
                    CommandResult.Success(JSONObject().put("result", JSONObject().apply {
                        put("rxBytes", bucket.rxBytes)
                        put("txBytes", bucket.txBytes)
                        put("rxPackets", bucket.rxPackets)
                        put("txPackets", bucket.txPackets)
                        put("beginTimeStamp", bucket.beginTimeStamp)
                        put("endTimeStamp", bucket.endTimeStamp)
                    }).toString())
                }
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
