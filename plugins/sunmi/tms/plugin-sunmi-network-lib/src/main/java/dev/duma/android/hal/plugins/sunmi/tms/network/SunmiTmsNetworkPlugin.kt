package dev.duma.android.hal.plugins.sunmi.tms.network

import android.content.Context
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.tms.network.handler.NetworkManagerHandler
import dev.duma.android.hal.plugins.sunmi.tms.base.BaseTmsPlugin

class SunmiTmsNetworkPlugin(context: Context? = null) : BaseTmsPlugin(context) {

    override val pluginId = "sunmi.tms.network"
    override val version = 1

    private val networkHandler by lazy { NetworkManagerHandler(tmsApi) }

    override fun getCapabilities() = listOf("sunmi.tms.network")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi TMS (Network)",
        version = version,
        capabilities = getCapabilities(),
        methods = buildMethodList(),
        events = emptyList()
    )

    override suspend fun execute(method: String, params: String): String = guardedExecute {
        networkHandler.handle(method, params)
    }

    private fun buildMethodList() = listOf(
        MethodDescriptor("sunmi.tms.network.enableMobileNetwork", "Enables or disables mobile data for SIM slot", "sunmi.tms.network",
            exampleParameters = """{"slotIdx":0,"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.getDefaultDataSlotIndex", "Gets default data SIM card slot index", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":0}"""),
        MethodDescriptor("sunmi.tms.network.setDefaultDataSlotIndex", "Sets default data SIM card slot", "sunmi.tms.network",
            exampleParameters = """{"slotIndex":0}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.setMobileDataLocked", "Locks or unlocks mobile data button in settings", "sunmi.tms.network",
            exampleParameters = """{"locked":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.getMobileDataStatus", "Gets overall mobile data on/off status", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.network.getMobileDataStatusBySlot", "Gets mobile data status for specific slot", "sunmi.tms.network",
            exampleParameters = """{"slotIndex":0}""",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.network.getDataRoamingEnabled", "Gets data roaming status for slot", "sunmi.tms.network",
            exampleParameters = """{"slotIndex":0}""",
            exampleOutput = """{"status":"ok","result":false}"""),
        MethodDescriptor("sunmi.tms.network.setDataRoamingEnabled", "Sets data roaming for slot", "sunmi.tms.network",
            exampleParameters = """{"slotIndex":0,"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.isActiveSlotIndex", "Checks if SIM slot is active", "sunmi.tms.network",
            exampleParameters = """{"slotIndex":0}""",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.network.enableWIFI", "Enables or disables Wi-Fi", "sunmi.tms.network",
            exampleParameters = """{"isEnable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.connectWifiSsid", "Connects to saved Wi-Fi network. Async.", "sunmi.tms.network",
            exampleParameters = """{"ssid":"MyWifi"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.removeWifiSsid", "Removes saved Wi-Fi network. Async.", "sunmi.tms.network",
            exampleParameters = """{"ssid":"MyWifi"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.forgetSavedWifi", "Forgets all saved Wi-Fi networks", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.addNetwork", "Saves Wi-Fi SSID. Returns networkId.", "sunmi.tms.network",
            exampleParameters = """{"ssid":"MyWifi","preSharedKey":"password123"}""",
            exampleOutput = """{"status":"ok","result":1}"""),
        MethodDescriptor("sunmi.tms.network.updateNetwork", "Modifies saved Wi-Fi. Returns networkId.", "sunmi.tms.network",
            exampleParameters = """{"ssid":"MyWifi","preSharedKey":"newpass","networkId":1}""",
            exampleOutput = """{"status":"ok","result":1}"""),
        MethodDescriptor("sunmi.tms.network.removeNetwork", "Deletes saved Wi-Fi by networkId", "sunmi.tms.network",
            exampleParameters = """{"networkId":1}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.getConfiguredNetworks", "Gets all saved Wi-Fi networks", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":[{"ssid":"MyWifi","networkId":1}]}"""),
        MethodDescriptor("sunmi.tms.network.addWifiSsidByWifiConfiguration", "Adds Wi-Fi via full WifiConfigurationInfo. Async.", "sunmi.tms.network",
            exampleParameters = """{"ssid":"MyWifi","pwd":"password123","security_type":3}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.setWifiStaticIp", "Sets Wi-Fi static IP or DHCP. Async.", "sunmi.tms.network",
            exampleParameters = """{"status":true,"ipAddr":"192.168.1.2","gateway":"192.168.1.1","networkPrefixLength":24,"dns1":"8.8.8.8","dns2":"8.8.4.4","reconnect":false}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.resetNetworkSettings", "Resets all network configurations (Wi-Fi, BT, Mobile)", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.installWlanCertificate", "Installs WLAN certificate from base64 data", "sunmi.tms.network",
            exampleParameters = """{"name":"cert","certData":"<base64>","password":""}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.setCustomNtpServer", "Sets custom NTP server", "sunmi.tms.network",
            exampleParameters = """{"server":"pool.ntp.org"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.getCustomNtpServer", "Gets configured NTP server address", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":"pool.ntp.org"}"""),
        MethodDescriptor("sunmi.tms.network.turnOnWifiHotspot", "Enables Wi-Fi hotspot", "sunmi.tms.network",
            exampleParameters = """{"ssid":"HotspotName","preShareKey":"pass12345","keyManagement":1}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.turnOffWifiHotspot", "Disables Wi-Fi hotspot", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.isWifiHotspotEnable", "Checks if Wi-Fi hotspot is enabled", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":false}"""),
        MethodDescriptor("sunmi.tms.network.showWifiHotspotSettings", "Opens hotspot settings menu", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.disableWifiHotspotAndHideSettings", "Disables hotspot and hides its settings", "sunmi.tms.network",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.enableEthernet", "Enables or disables Ethernet", "sunmi.tms.network",
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.network.getTrafficOfEachApp", "Gets per-app network data usage (networkType: 0=mobile, 1=wifi)", "sunmi.tms.network",
            exampleParameters = """{"networkType":0,"startTime":1700000000000,"endTime":1700086400000}""",
            exampleOutput = """{"status":"ok","result":[{"packageName":"com.example.app","uid":10001,"rxBytes":1024,"txBytes":512,"rxPackets":10,"txPackets":5,"beginTimeStamp":1700000000000,"endTimeStamp":1700086400000}]}"""),
        MethodDescriptor("sunmi.tms.network.queryTrafficUsageSummary", "Gets total device data usage (networkType: 0=mobile, 1=wifi)", "sunmi.tms.network",
            exampleParameters = """{"networkType":0,"startTime":1700000000000,"endTime":1700086400000}""",
            exampleOutput = """{"status":"ok","result":{"rxBytes":10240,"txBytes":5120,"rxPackets":100,"txPackets":50,"beginTimeStamp":1700000000000,"endTimeStamp":1700086400000}}"""),
    )
}
