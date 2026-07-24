package dev.duma.android.hal.plugins.sunmi.tms.software

import android.content.Context
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.stripExperimental
import dev.duma.android.hal.plugins.sunmi.tms.software.handler.*
import dev.duma.android.hal.plugins.sunmi.tms.base.BaseTmsPlugin

class SunmiTmsSoftwarePlugin(context: Context? = null) : BaseTmsPlugin(context) {

    override val pluginId = "sunmi.tms.software"
    override val version = 1

    private val softwareHandler by lazy { SoftwareManagerHandler(tmsApi, ::emitEvent) }
    private val packageInfoHandler by lazy { PackageInfoHandler(tmsApi) }

    override fun getCapabilities() = listOf("sunmi.tms.software")

    override fun getDescriptor() = fullDescriptor().let {
        if (BuildConfig.WITH_EXPERIMENTAL) it else it.stripExperimental()
    }

    private fun fullDescriptor() = PluginDescriptor.withFlatLists(
        pluginId = pluginId,
        name = "Sunmi: TMS (Software)",
        version = version,
        experimental = true,
        capabilities = getCapabilities(),
        methods = buildMethodList(),
        events = listOf(
            EventDescriptor("sunmi.tms.software.installSuccess", "App installation succeeded", "sunmi.tms.software",
                exampleEvent = """{"packageName":"com.example.app"}"""),
            EventDescriptor("sunmi.tms.software.installFail", "App installation failed", "sunmi.tms.software",
                exampleEvent = """{"packageName":"com.example.app","errorId":-2}"""),
            EventDescriptor("sunmi.tms.software.uninstallSuccess", "App uninstallation succeeded", "sunmi.tms.software",
                exampleEvent = """{"packageName":"com.example.app"}"""),
            EventDescriptor("sunmi.tms.software.uninstallFail", "App uninstallation failed", "sunmi.tms.software",
                exampleEvent = """{"packageName":"com.example.app","errorId":-1}"""),
        )
    )

    override suspend fun onExecute(method: String, params: String): CommandResult = guardedExecute {
        val withoutPlugin = method.removePrefix("sunmi.tms.software.")
        if (withoutPlugin.startsWith("packages.")) {
            packageInfoHandler.handle(method, params)
        } else {
            softwareHandler.handle(method, params)
        }
    }

    private fun buildMethodList() = listOf(
        MethodDescriptor("sunmi.tms.software.installApp", "Silently installs APK. Async — emits install events.", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"appFilePath":"/sdcard/app.apk"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.installAppV2", "Silently installs APK with autoStart option. Async.", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"appFilePath":"/sdcard/app.apk","autoStart":true}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.uninstallApp", "Silently uninstalls app. Async — emits uninstall events.", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.killApp", "Force-stops an app", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.restartApp", "Restarts an app", "sunmi.tms.software",
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.setBatteryOptimizationWhitelist", "Sets battery optimization whitelist (comma-separated package names)", "sunmi.tms.software",
            exampleParameters = """{"whitelist":"com.example.a,com.example.b"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.setAppEnabled", "Enables or disables an app", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app","enabled":true}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.grantAppPermissions", "Grants dynamic permissions to an app", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app","permissions":"android.permission.CAMERA"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.revokeAppPermission", "Revokes dynamic permissions from an app", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app","permissions":"android.permission.CAMERA"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.getRequestPermissions", "Gets permissions requested by an app", "sunmi.tms.software",
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{"result":"android.permission.CAMERA,android.permission.INTERNET"}"""),
        MethodDescriptor("sunmi.tms.software.allowAlertWindowPermission", "Grants floating window permission", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.prohibitUninstall", "Sets uninstall prohibition for an app", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app","allowUninstall":false}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.getProhibitUninstallList", "Gets semicolon-separated list of uninstall-prohibited apps", "sunmi.tms.software",
            exampleParameters = "{}",
            exampleOutput = """{"result":"com.example.a;com.example.b"}"""),
        MethodDescriptor("sunmi.tms.software.enableAutoStartApp", "Enables or disables auto-start feature", "sunmi.tms.software",
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.isAutoStartAppEnabled", "Checks if auto-start feature is enabled", "sunmi.tms.software",
            exampleParameters = "{}",
            exampleOutput = """{"result":true}"""),
        MethodDescriptor("sunmi.tms.software.setAutoStartApp", "Sets auto-start app (only one allowed)", "sunmi.tms.software",
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.getAutoStartApp", "Gets auto-start app package name", "sunmi.tms.software",
            exampleParameters = "{}",
            exampleOutput = """{"result":"com.example.app"}"""),
        MethodDescriptor("sunmi.tms.software.clearAutoStartApp", "Clears auto-start app", "sunmi.tms.software",
            exampleParameters = "{}",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.clearApplicationUserData", "Clears app user data and cache. Async.", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.deleteApplicationCacheFiles", "Deletes app cache only. Async.", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.isForeground", "Checks if an app is in foreground", "sunmi.tms.software",
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{"result":true}"""),
        MethodDescriptor("sunmi.tms.software.addAppToCommonAppLockList", "Adds app lock (type: -1=mixed plaintext, 0=4-digit MD5, 1=8-digit MD5)", "sunmi.tms.software",
            exampleParameters = """{"packageNames":["com.example.app"],"type":-1,"password":"1234"}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.removeAppFromCommonAppLockList", "Removes app lock", "sunmi.tms.software",
            exampleParameters = """{"packageNames":["com.example.app"]}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.getCommonAppLockList", "Gets list of locked app package names", "sunmi.tms.software",
            exampleParameters = "{}",
            exampleOutput = """{"result":["com.example.app"]}"""),
        MethodDescriptor("sunmi.tms.software.isCommonAppLock", "Checks if an app has a lock", "sunmi.tms.software",
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{"result":true}"""),
        MethodDescriptor("sunmi.tms.software.setNotificationsEnabledForPackage", "Sets app notification permission", "sunmi.tms.software",
            exampleParameters = """{"packageName":"com.example.app","enabled":true}""",
            exampleOutput = """{}"""),
        MethodDescriptor("sunmi.tms.software.packages.getSystemAllPackageInfo", "Gets all installed app info as JSON array. Blocking.", "sunmi.tms.software",
            exampleParameters = "{}",
            exampleOutput = """{"result":"[{\"packageName\":\"com.example.app\",\"versionName\":\"1.0\"}]"}"""),
        MethodDescriptor("sunmi.tms.software.removeAllRecentTasks", "Removes all tasks from the recent apps list", "sunmi.tms.software",
            superRequired = true,
            exampleParameters = "{}",
            exampleOutput = """{"result":0}"""),
    )
}
