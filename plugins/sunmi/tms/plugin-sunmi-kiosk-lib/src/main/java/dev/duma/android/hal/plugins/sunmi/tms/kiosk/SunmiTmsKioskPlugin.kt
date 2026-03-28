package dev.duma.android.hal.plugins.sunmi.tms.kiosk

import android.content.Context
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.plugins.sunmi.tms.kiosk.handler.*
import dev.duma.android.hal.plugins.sunmi.tms.base.BaseTmsPlugin

class SunmiTmsKioskPlugin(context: Context? = null) : BaseTmsPlugin(context) {

    override val pluginId = "sunmi.tms.kiosk"
    override val version = 1

    private val kioskHandler by lazy { KioskManagerHandler(tmsApi) }
    private val certificateHandler by lazy { CertificateManagerHandler(tmsApi) }

    override fun getCapabilities() = listOf("sunmi.tms.kiosk")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi TMS (Kiosk)",
        version = version,
        capabilities = getCapabilities(),
        methods = buildMethodList(),
        events = emptyList()
    )

    override suspend fun execute(method: String, params: String): String = guardedExecute {
        val withoutPlugin = method.removePrefix("sunmi.tms.kiosk.")
        if (withoutPlugin.startsWith("certificate.")) {
            certificateHandler.handle(method, params)
        } else {
            kioskHandler.handle(method, params)
        }
    }

    private fun buildMethodList() = listOf(
        // kiosk (shortened prefix)
        MethodDescriptor("sunmi.tms.kiosk.enableKioskFunction", "Enables or disables entire kiosk feature", "sunmi.tms.kiosk",
            superRequired = true,
            exampleParameters = """{"enable":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.kiosk.isKioskFunctionEnabled", "Checks if kiosk feature is enabled", "sunmi.tms.kiosk",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.kiosk.getKioskModeStatus", "Gets whether device is currently in kiosk mode", "sunmi.tms.kiosk",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.kiosk.addAppToKioskList", "Adds apps to kiosk whitelist", "sunmi.tms.kiosk",
            exampleParameters = """{"packageNames":["com.example.app"]}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.kiosk.removeAppFromKioskList", "Removes apps from kiosk whitelist", "sunmi.tms.kiosk",
            exampleParameters = """{"packageNames":["com.example.app"]}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.kiosk.getKioskAppList", "Gets list of all kiosk apps. Note: call with 300ms delay after addAppToKioskList.", "sunmi.tms.kiosk",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":["com.example.app"]}"""),
        MethodDescriptor("sunmi.tms.kiosk.isKioskApp", "Checks if app is in kiosk whitelist", "sunmi.tms.kiosk",
            exampleParameters = """{"packageName":"com.example.app"}""",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.kiosk.setKioskPwdByType", "Sets kiosk exit password (type: 0=4-digit cloud, 1=8-digit cloud, 2=local)", "sunmi.tms.kiosk",
            superRequired = true,
            exampleParameters = """{"type":2,"psw":"1234"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.kiosk.setNavigationBarStatusForKiosk", "Sets nav bar behavior in kiosk mode (1=persistent Back only, 2=swipe to show)", "sunmi.tms.kiosk",
            superRequired = true,
            exampleParameters = """{"status":1}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.kiosk.getNavigationBarStatusForKiosk", "Gets nav bar status in kiosk mode", "sunmi.tms.kiosk",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":1}"""),
        MethodDescriptor("sunmi.tms.kiosk.hideStatusBarForKiosk", "Sets status bar behavior in kiosk (true=auto-hide, false=always visible)", "sunmi.tms.kiosk",
            superRequired = true,
            exampleParameters = """{"hide":true}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.kiosk.isStatusBarHiddenForKiosk", "Gets whether status bar auto-hides in kiosk mode", "sunmi.tms.kiosk",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":true}"""),
        MethodDescriptor("sunmi.tms.kiosk.switchKioskPwdByType", "Switches active exit password type (-1=Sunmi default, 0=4-digit, 1=8-digit, 2=local). Must call setKioskPwdByType first.", "sunmi.tms.kiosk",
            superRequired = true,
            exampleParameters = """{"type":2}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.kiosk.getSwitchKioskPwdByType", "Gets currently active exit password type (-1=default, 0=4-digit, 1=8-digit, 2=local)", "sunmi.tms.kiosk",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":2}"""),
        MethodDescriptor("sunmi.tms.kiosk.exitKioskMode", "Exits kiosk mode using password", "sunmi.tms.kiosk",
            superRequired = true,
            exampleParameters = """{"password":"1234"}""",
            exampleOutput = """{"status":"ok"}"""),
        // certificate
        MethodDescriptor("sunmi.tms.kiosk.certificate.updateCertificate", "Updates app signature verification cert. File must be in /sdcard/.", "sunmi.tms.kiosk",
            superRequired = true,
            exampleParameters = """{"certPath":"/sdcard/cert.crt"}""",
            exampleOutput = """{"status":"ok"}"""),
        MethodDescriptor("sunmi.tms.kiosk.certificate.getCertificateInfo", "Gets current certificate info as JSON", "sunmi.tms.kiosk",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":"{\"CertState\":1,\"Code\":0}"}"""),
        MethodDescriptor("sunmi.tms.kiosk.certificate.getTrustedFileCertChain", "Gets trusted certificate chain as JSON", "sunmi.tms.kiosk",
            exampleParameters = "{}",
            exampleOutput = """{"status":"ok","result":"{\"CertState\":1,\"Code\":0}"}"""),
    )
}
