package dev.duma.android.hal.plugins.sunmi.tms.kiosk.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.contract.CommandResult
import org.json.JSONObject

internal class CertificateManagerHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.kiosk.certificate.")
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return when (op) {
            "updateCertificate" -> { api.certificateManager.updateCertificate(json.getString("certPath")); CommandResult.Success() }
            "getCertificateInfo" -> CommandResult.Success(JSONObject().put("result", api.certificateManager.certificateInfo).toString())
            "getTrustedFileCertChain" -> CommandResult.Success(JSONObject().put("result", api.certificateManager.trustedFileCertChain).toString())
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
