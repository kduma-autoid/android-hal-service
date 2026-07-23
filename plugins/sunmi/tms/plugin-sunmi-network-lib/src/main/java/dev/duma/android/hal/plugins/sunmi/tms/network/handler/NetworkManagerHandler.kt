package dev.duma.android.hal.plugins.sunmi.tms.network.handler

import android.os.Bundle
import android.util.Base64
import com.sunmi.tms.api.TMSApi
import com.sunmi.tmsmaster.aidl.networkmanager.IUnifiedCallback
import com.sunmi.tmsmaster.aidl.networkmanager.SunmiApnSetting
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
            "getCurrentApnV3" -> {
                val apn = api.networkManager.getCurrentApnV3()
                CommandResult.Success((if (apn == null) JSONObject() else JSONObject().put("result", apnToJson(apn))).toString())
            }
            "queryPreferApnBySlotIndex" -> {
                val apn = api.networkManager.queryPreferApnBySlotIndex(json.getInt("slotIndex"))
                CommandResult.Success((if (apn == null) JSONObject() else JSONObject().put("result", apnToJson(apn))).toString())
            }
            "getApnListV3" -> {
                val filter = if (json.has("filter")) apnFromJson(json.getJSONObject("filter")) else SunmiApnSetting()
                val list = api.networkManager.getApnListV3(filter)
                val arr = JSONArray()
                list?.forEach { arr.put(apnToJson(it)) }
                CommandResult.Success(JSONObject().put("result", arr).toString())
            }
            "addApnV3" -> {
                val arr = json.getJSONArray("apns")
                val apns = (0 until arr.length()).map { apnFromJson(arr.getJSONObject(it)) }
                CommandResult.Success(JSONObject().put("result", api.networkManager.addApnV3(apns)).toString())
            }
            "updateApnV3" -> CommandResult.Success(JSONObject().put("result", api.networkManager.updateApnV3(apnFromJson(json.getJSONObject("apn")))).toString())
            "deleteApnV3" -> CommandResult.Success(JSONObject().put("result", api.networkManager.deleteApnV3(apnFromJson(json.getJSONObject("apn")))).toString())
            "setApnV3" -> CommandResult.Success(JSONObject().put("result", api.networkManager.setApnV3(json.getInt("apnId"))).toString())
            "setPreferApnBySlotIndex" -> CommandResult.Success(JSONObject().put("result", api.networkManager.setPreferApnBySlotIndex(json.getInt("slotIndex"), json.getInt("apnId"))).toString())
            "addSsidToWifiSsidWhiteList" -> CommandResult.Success(JSONObject().put("result", api.networkManager.addSsidToWifiSsidWhiteList(stringList(json, "ssids"))).toString())
            "removeSsidFromWifiSsidWhiteList" -> CommandResult.Success(JSONObject().put("result", api.networkManager.removeSsidFromWifiSsidWhiteList(stringList(json, "ssids"))).toString())
            "getWifiSsidWhiteList" -> CommandResult.Success(JSONObject().put("result", JSONArray(api.networkManager.getWifiSsidWhiteList() ?: emptyList<String>())).toString())
            "enableWifiSsidWhiteList" -> CommandResult.Success(JSONObject().put("result", api.networkManager.enableWifiSsidWhiteList(json.getBoolean("enable"))).toString())
            "isWifiSsidWhiteListEnabled" -> CommandResult.Success(JSONObject().put("result", api.networkManager.isWifiSsidWhiteListEnabled()).toString())
            "requestRouteToIp" -> CommandResult.Success(JSONObject().put("result", api.networkManager.requestRouteToIp(json.getString("ip"), json.getInt("type"), json.getBoolean("enable"))).toString())
            "getRequestRouteIps" -> {
                val map = api.networkManager.getRequestRouteIps()
                val obj = JSONObject()
                map?.forEach { (k, v) -> obj.put(k.toString(), v) }
                CommandResult.Success(JSONObject().put("result", obj).toString())
            }
            "setDataAutoSwitch" -> CommandResult.Success(JSONObject().put("result", api.networkManager.setDataAutoSwitch(json.getBoolean("enable"))).toString())
            else -> CommandResult.unsupportedMethod(method)
        }
    }

    private fun stringList(json: JSONObject, key: String): List<String> {
        val arr = json.getJSONArray(key)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** Serializes the commonly-used subset of a [SunmiApnSetting] to JSON (null fields omitted). */
    private fun apnToJson(apn: SunmiApnSetting): JSONObject = JSONObject().apply {
        putOpt("_id", apn._id)
        putOpt("name", apn.name)
        putOpt("apn", apn.apn)
        putOpt("numeric", apn.numeric)
        putOpt("mcc", apn.mcc)
        putOpt("mnc", apn.mnc)
        putOpt("user", apn.user)
        putOpt("password", apn.password)
        putOpt("server", apn.server)
        putOpt("proxy", apn.proxy)
        putOpt("port", apn.port)
        putOpt("mmsc", apn.mmsc)
        putOpt("mmsproxy", apn.mmsproxy)
        putOpt("mmsport", apn.mmsport)
        putOpt("authtype", apn.authtype)
        putOpt("type", apn.type)
        putOpt("protocol", apn.protocol)
        putOpt("roaming_protocol", apn.roaming_protocol)
        putOpt("carrier_enabled", apn.carrier_enabled)
        putOpt("bearer", apn.bearer)
        putOpt("mvno_type", apn.mvno_type)
        putOpt("mvno_match_data", apn.mvno_match_data)
        putOpt("current", apn.current)
    }

    /** Builds a [SunmiApnSetting] from JSON, setting only the fields present. */
    private fun apnFromJson(json: JSONObject): SunmiApnSetting = SunmiApnSetting().apply {
        if (json.has("_id")) _id = json.getInt("_id")
        if (json.has("name")) name = json.getString("name")
        if (json.has("apn")) apn = json.getString("apn")
        if (json.has("numeric")) numeric = json.getString("numeric")
        if (json.has("mcc")) mcc = json.getString("mcc")
        if (json.has("mnc")) mnc = json.getString("mnc")
        if (json.has("user")) user = json.getString("user")
        if (json.has("password")) password = json.getString("password")
        if (json.has("server")) server = json.getString("server")
        if (json.has("proxy")) proxy = json.getString("proxy")
        if (json.has("port")) port = json.getString("port")
        if (json.has("mmsc")) mmsc = json.getString("mmsc")
        if (json.has("mmsproxy")) mmsproxy = json.getString("mmsproxy")
        if (json.has("mmsport")) mmsport = json.getString("mmsport")
        if (json.has("authtype")) authtype = json.getInt("authtype")
        if (json.has("type")) type = json.getString("type")
        if (json.has("protocol")) protocol = json.getString("protocol")
        if (json.has("roaming_protocol")) roaming_protocol = json.getString("roaming_protocol")
        if (json.has("carrier_enabled")) carrier_enabled = json.getBoolean("carrier_enabled")
        if (json.has("bearer")) bearer = json.getInt("bearer")
        if (json.has("mvno_type")) mvno_type = json.getString("mvno_type")
        if (json.has("mvno_match_data")) mvno_match_data = json.getString("mvno_match_data")
        if (json.has("current")) current = json.getInt("current")
    }
}
