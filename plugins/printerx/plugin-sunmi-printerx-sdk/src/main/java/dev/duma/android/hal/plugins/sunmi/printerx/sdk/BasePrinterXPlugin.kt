package dev.duma.android.hal.plugins.sunmi.printerx.sdk

import android.content.Context
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.PluginContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Thin base class for all PrinterX plugins.
 * Provides: SharedPrinterManager lifecycle, Mutex-guarded execution,
 * event emission, and common helpers.
 *
 * Subclasses implement [handleExecute] for domain-specific method routing.
 */
abstract class BasePrinterXPlugin(
    protected val context: Context? = null
) : HalPlugin {

    private var _eventCallback: HalPluginEventCallback? = null
    protected val mutex = Mutex()

    override fun isSupported(): Boolean = true

    override fun initialize(context: PluginContext) {
        this.context?.let { SharedPrinterManager.acquire(it) }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        _eventCallback = callback
    }

    protected fun emitEvent(event: String, payload: String) {
        _eventCallback?.onEvent(event, payload)
    }

    override suspend fun execute(method: String, params: String): String {
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return try {
            handleExecute(method, params, json)
        } catch (e: Exception) {
            error("sdk_error", e.message ?: "Unknown error")
        }
    }

    /**
     * Subclass routing. Called from [execute] with parsed JSON params.
     * Use [guardedExecute] for SDK calls that require Mutex protection.
     */
    protected abstract suspend fun handleExecute(
        method: String,
        params: String,
        json: JSONObject
    ): String

    /**
     * Mutex-guarded SDK call. Use for operations that should be serialised
     * (queries, synchronous SDK calls). Skip for long-running async operations
     * (printTrans, printCanvas, printFile, cashDrawer.open) to avoid blocking.
     */
    protected suspend fun guardedExecute(block: suspend () -> String): String =
        mutex.withLock {
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
