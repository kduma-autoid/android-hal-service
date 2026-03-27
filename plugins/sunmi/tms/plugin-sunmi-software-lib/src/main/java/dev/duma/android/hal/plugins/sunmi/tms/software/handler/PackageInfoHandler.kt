package dev.duma.android.hal.plugins.sunmi.tms.software.handler

import com.sunmi.tms.api.TMSApi
import dev.duma.android.hal.plugins.sunmi.tms.handler.success
import dev.duma.android.hal.plugins.sunmi.tms.handler.unsupportedMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PackageInfoHandler(private val api: TMSApi) {

    suspend fun handle(method: String, params: String): String {
        val op = method.removePrefix("sunmi.tms.software.packages.")
        return when (op) {
            "getSystemAllPackageInfo" -> withContext(Dispatchers.IO) {
                success(api.packageCTPA.systemAllPackageInfo)
            }
            else -> unsupportedMethod(method)
        }
    }
}
