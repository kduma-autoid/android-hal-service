package dev.duma.android.hal.plugins.sunmi.tms.kiosk.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import org.json.JSONObject

internal class CertificateManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): String {
        val op = method.removePrefix("sunmi.tms.kiosk.certificate.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "updateCertificate" -> { api.certificateManager.updateCertificate(json.getString("certPath")); success() }
            "getCertificateInfo" -> success(api.certificateManager.certificateInfo)
            "getTrustedFileCertChain" -> success(api.certificateManager.trustedFileCertChain)
            else -> unsupportedMethod(method)
        }
    }
}
