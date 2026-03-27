package dev.duma.android.hal.plugins.sunmi.tms.base

import android.content.Context
import com.sunmi.tms.api.TMSApi
import com.sunmi.tms.api.TMSServiceConnection
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.PluginContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

abstract class BaseTmsPlugin(
    protected val context: Context? = null
) : HalPlugin {

    protected val tmsApi = TMSApi()
    protected var connected = false
    private var _eventCallback: HalPluginEventCallback? = null
    protected val mutex = Mutex()

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        return try {
            ctx.packageManager.getPackageInfo("com.sunmi.tmservice", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun initialize(context: PluginContext) {
        this.context?.let { ctx ->
            tmsApi.connect(ctx, object : TMSServiceConnection {
                override fun onServiceConnected() { connected = true }
                override fun onServiceDisconnected() { connected = false }
            })
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        _eventCallback = callback
    }

    protected fun emitEvent(event: String, payload: String) {
        _eventCallback?.onEvent(event, payload)
    }

    protected suspend fun guardedExecute(block: suspend () -> String): String =
        mutex.withLock {
            if (!connected) return@withLock error("service_disconnected", "TMSApi not connected")
            try { block() } catch (e: Exception) { error("sdk_error", e.message ?: "Unknown error") }
        }

    protected fun success(data: Any? = null): String {
        val obj = JSONObject().put("status", "ok")
        if (data != null) obj.put("result", data)
        return obj.toString()
    }

    protected fun error(code: String, message: String): String =
        JSONObject().put("error", code).put("message", message).toString()

    protected fun unsupportedMethod(method: String): String =
        error("unsupported_method", "Method not supported: $method")
}
