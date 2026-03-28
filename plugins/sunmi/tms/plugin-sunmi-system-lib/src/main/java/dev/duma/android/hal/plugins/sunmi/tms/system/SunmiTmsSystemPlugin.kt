package dev.duma.android.hal.plugins.sunmi.tms.system

import android.content.Context
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.tms.system.handler.*
import dev.duma.android.hal.plugins.sunmi.tms.base.BaseTmsPlugin

class SunmiTmsSystemPlugin(context: Context? = null) : BaseTmsPlugin(context) {

    override val pluginId = "sunmi.tms.system"
    override val version = 1

    private val systemHandler by lazy { SystemManagerHandler(tmsApi, ::emitEvent) }
    private val systemUiHandler by lazy { SystemUiManagerHandler(tmsApi) }

    override fun getCapabilities() = listOf("sunmi.tms.system")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi TMS (System)",
        version = version,
        capabilities = getCapabilities(),
        methods = buildMethodList(),
        events = listOf(
            EventDescriptor("sunmi.tms.system.updateProgress", "OTA update progress", "sunmi.tms.system",
                exampleEvent = """{"progress":50}"""),
            EventDescriptor("sunmi.tms.system.updateFail", "OTA update failed", "sunmi.tms.system",
                exampleEvent = """{"info":"Update verification failed"}"""),
        )
    )

    override suspend fun execute(method: String, params: String): String = guardedExecute {
        val withoutPlugin = method.removePrefix("sunmi.tms.system.")
        if (withoutPlugin.startsWith("system_ui.")) {
            systemUiHandler.handle(method, params)
        } else {
            systemHandler.handle(method, params)
        }
    }

    private fun buildMethodList() = listOf(
        // system (shortened prefix)
        MethodDescriptor("sunmi.tms.system.updateSystem", "OTA update from file. Async — emits updateProgress and updateFail events.", "sunmi.tms.system",
            superRequired = true,
            exampleParameters = """{"systemPath":"/sdcard/ota.zip"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.setDefaultLauncher", "Sets default launcher app", "sunmi.tms.system",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.launcher"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.doScreenshot", "Takes device screenshot. Returns base64-encoded PNG.", "sunmi.tms.system",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":"iVBORw0KGgoAAAANSUhEUgAA..."}"""),
        MethodDescriptor("sunmi.tms.system.enableCommonAppLock", "Enables or disables general app lock feature", "sunmi.tms.system",
            superRequired = true,
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.isCommonAppLockEnabled", "Checks if general app lock is enabled", "sunmi.tms.system",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.system.setSystemLanguage", "Sets system language", "sunmi.tms.system",
            superRequired = true,
            exampleParameters = """{"language":"en-US"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.disableSecurityPCI24HoursReboot", "Enables or disables PCI 24-hour reboot", "sunmi.tms.system",
            superRequired = true,
            exampleParameters = """{"disable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.set24HourRebootRegular", "Sets PCI reboot schedule (type: 1=original, 2=scheduled)", "sunmi.tms.system",
            superRequired = true,
            exampleParameters = """{"type":2,"hour":3,"minute":0,"second":0}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.setSecurityCenterPwd", "Sets Security Center password (6-16 chars)", "sunmi.tms.system",
            superRequired = true,
            exampleParameters = """{"pwd":"123456"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.enableAdb", "Enables or disables ADB debugging", "sunmi.tms.system",
            superRequired = true,
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.isAdbEnabled", "Checks if ADB is enabled", "sunmi.tms.system",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.system.queryAppUsageState", "Queries app usage stats (intervalType: 0=daily,1=weekly,2=monthly,3=yearly,4=adaptive)", "sunmi.tms.system",
            exampleParameters = """{"intervalType":0,"beginTime":1700000000000,"endTime":1700086400000}""",
            exampleOutput = """{"status":"ok","result":[{"packageName":"com.example.app","beginTimeStamp":1700000000000,"endTimeStamp":1700086400000,"lastTimeUsed":1700050000000,"totalTimeInForeground":3600000,"launchCount":5,"lastEvent":1}]}"""),
        MethodDescriptor("sunmi.tms.system.getSystemPackageList", "Gets system app package names with icons", "sunmi.tms.system",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":["com.android.settings","com.android.phone"]}"""),
        // system_ui
        MethodDescriptor("sunmi.tms.system.system_ui.showNavigationBarBackButton", "Shows or hides navigation back button", "sunmi.tms.system",
            exampleParameters = """{"show":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.showNavigationBarHomeButton", "Shows or hides navigation home button", "sunmi.tms.system",
            exampleParameters = """{"show":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.showNavigationBarRecentsButton", "Shows or hides navigation recents button", "sunmi.tms.system",
            exampleParameters = """{"show":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.showNavigationBar", "Shows or hides entire navigation bar", "sunmi.tms.system",
            exampleParameters = """{"show":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.showStatusBar", "Shows or hides status bar", "sunmi.tms.system",
            exampleParameters = """{"show":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.enableNavigationBarBackButton", "Enables or disables navigation back button", "sunmi.tms.system",
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.isNavigationBarBackButtonEnabled", "Checks if navigation back button is enabled", "sunmi.tms.system",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.enableNavigationBarHomeButton", "Enables or disables navigation home button", "sunmi.tms.system",
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.isNavigationBarHomeButtonEnabled", "Checks if navigation home button is enabled", "sunmi.tms.system",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.enableNavigationBarRecentsButton", "Enables or disables navigation recents button", "sunmi.tms.system",
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.isNavigationBarRecentsButtonEnabled", "Checks if navigation recents button is enabled", "sunmi.tms.system",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.enableNotificationPanel", "Allows or forbids status bar pull-down", "sunmi.tms.system",
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.clickableNavigationBar", "Sets navigation bar clickable state", "sunmi.tms.system",
            exampleParameters = """{"clickable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.system.system_ui.setAirplaneModeBarClickable", "Sets airplane mode button clickable state", "sunmi.tms.system",
            exampleParameters = """{"clickable":true}""",
            exampleOutput = """{"status":"ok"}"""),
    )
}
