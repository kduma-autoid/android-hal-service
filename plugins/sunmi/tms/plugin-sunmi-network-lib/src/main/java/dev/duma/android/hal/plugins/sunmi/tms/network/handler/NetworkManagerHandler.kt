package dev.duma.android.hal.plugins.sunmi.tms.network.handler

import android.os.Bundle
import android.util.Base64
import com.sunmi.tms.api.TMSApi
import com.sunmi.tmsmaster.aidl.networkmanager.IUnifiedCallback
import com.sunmi.tmsmaster.aidl.networkmanager.WifiConfigurationInfo
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

internal class NetworkManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): String {
        val op = method.removePrefix("sunmi.tms.network.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "enableMobileNetwork" -> { api.networkManager.enableMobileNetwork(json.getInt("slotIdx"), json.getBoolean("enable")); success() }
            "getDefaultDataSlotIndex" -> success(api.networkManager.defaultDataSlotIndex)
            "setDefaultDataSlotIndex" -> { api.networkManager.setDefaultDataSlotIndex(json.getInt("slotIndex")); success() }
            "setMobileDataLocked" -> { api.networkManager.setMobileDataLocked(json.getBoolean("locked")); success() }
            "getMobileDataStatus" -> success(api.networkManager.mobileDataStatus)
            "getMobileDataStatusBySlot" -> success(api.networkManager.getMobileDataStatusBySlot(json.getInt("slotIndex")))
            "getDataRoamingEnabled" -> success(api.networkManager.getDataRoamingEnabled(json.getInt("slotIndex")))
            "setDataRoamingEnabled" -> { api.networkManager.setDataRoamingEnabled(json.getInt("slotIndex"), json.getBoolean("enable")); success() }
            "isActiveSlotIndex" -> success(api.networkManager.isActiveSlotIndex(json.getInt("slotIndex")))
            "enableWIFI" -> { api.networkManager.enableWifi(json.getBoolean("isEnable")); success() }
            "connectWifiSsid" -> {
                suspendCancellableCoroutine { cont ->
                    api.networkManager.connectWifiSsid(json.getString("ssid"), object : IUnifiedCallback.Stub() {
                        override fun onCall(result: String?) { cont.resume(success(result)) }
                    })
                }
            }
            "removeWifiSsid" -> {
                suspendCancellableCoroutine { cont ->
                    api.networkManager.removeWifiSsid(json.getString("ssid"), object : IUnifiedCallback.Stub() {
                        override fun onCall(result: String?) { cont.resume(success(result)) }
                    })
                }
            }
            "forgetSavedWifi" -> { api.networkManager.forgetSavedWifi(); success() }
            "addNetwork" -> {
                val bundle = Bundle().apply {
                    putString("ssid", json.getString("ssid"))
                    putString("preSharedKey", json.optString("preSharedKey", ""))
                }
                success(api.networkManager.addNetwork(bundle))
            }
            "updateNetwork" -> {
                val bundle = Bundle().apply {
                    putString("ssid", json.getString("ssid"))
                    putString("preSharedKey", json.optString("preSharedKey", ""))
                    putInt("networkId", json.getInt("networkId"))
                }
                success(api.networkManager.updateNetwork(bundle))
            }
            "removeNetwork" -> { api.networkManager.removeNetwork(json.getInt("networkId")); success() }
            "getConfiguredNetworks" -> {
                val bundles = api.networkManager.configuredNetworks
                val arr = JSONArray()
                bundles?.forEach { b ->
                    arr.put(JSONObject().apply {
                        put("ssid", b.getString("ssid"))
                        put("networkId", b.getInt("networkId"))
                    })
                }
                success(arr)
            }
            "addWifiSsidByWifiConfiguration" -> {
                val config = WifiConfigurationInfo()
                config.ssid = json.getString("ssid")
                config.pwd = json.optString("pwd", "")
                config.security_type = json.optInt("security_type", 0)
                suspendCancellableCoroutine { cont ->
                    api.networkManager.addWifiSsidByWifiConfiguration(config, object : IUnifiedCallback.Stub() {
                        override fun onCall(result: String?) { cont.resume(success(result)) }
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
                            override fun onCall(result: String?) { cont.resume(success(result)) }
                        }
                    )
                }
            }
            "resetNetworkSettings" -> { api.networkManager.resetNetworkSettings(); success() }
            "installWlanCertificate" -> {
                val bytes = Base64.decode(json.getString("certData"), Base64.DEFAULT)
                api.networkManager.installWlanCertificate(json.getString("name"), bytes, json.optString("password", ""))
                success()
            }
            "setCustomNtpServer" -> { api.networkManager.setCustomNtpServer(json.getString("server")); success() }
            "getCustomNtpServer" -> success(api.networkManager.customNtpServer)
            "turnOnWifiHotspot" -> {
                api.networkManager.turnOnWifiHotspot(json.getString("ssid"), json.getString("preShareKey"), json.getInt("keyManagement"))
                success()
            }
            "turnOffWifiHotspot" -> { api.networkManager.turnOffWifiHotspot(); success() }
            "isWifiHotspotEnable" -> success(api.networkManager.isWifiHotspotEnable)
            "showWifiHotspotSettings" -> { api.networkManager.showWifiHotspotSettings(); success() }
            "disableWifiHotspotAndHideSettings" -> { api.networkManager.disableWifiHotspotAndHideSettings(); success() }
            "enableEthernet" -> { api.networkManager.enableEthernet(json.getBoolean("enable")); success() }
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
                success(arr)
            }
            "queryTrafficUsageSummary" -> {
                val bucket = api.networkManager.queryTrafficUsageSummary(json.getInt("networkType"), json.getLong("startTime"), json.getLong("endTime"))
                if (bucket == null) {
                    success(null)
                } else {
                    success(JSONObject().apply {
                        put("rxBytes", bucket.rxBytes)
                        put("txBytes", bucket.txBytes)
                        put("rxPackets", bucket.rxPackets)
                        put("txPackets", bucket.txPackets)
                        put("beginTimeStamp", bucket.beginTimeStamp)
                        put("endTimeStamp", bucket.endTimeStamp)
                    })
                }
            }
            else -> unsupportedMethod(method)
        }
    }
}
