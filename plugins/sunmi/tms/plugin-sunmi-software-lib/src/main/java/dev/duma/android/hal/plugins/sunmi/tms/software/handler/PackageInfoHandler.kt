package dev.duma.android.hal.plugins.sunmi.tms.software.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.contract.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class PackageInfoHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): CommandResult {
        val op = method.removePrefix("sunmi.tms.software.packages.")
        return when (op) {
            "getSystemAllPackageInfo" -> withContext(Dispatchers.IO) {
                CommandResult.Success(JSONObject().put("result", api.packageCTPA.systemAllPackageInfo).toString())
            }
            else -> CommandResult.unsupportedMethod(method)
        }
    }
}
