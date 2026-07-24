package dev.duma.android.hal.plugins.sunmi.printerx.sdk

import android.content.Context
import android.content.Intent
import dev.duma.android.hal.contract.BaseHalPlugin
import dev.duma.android.hal.contract.CommandResult
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
) : BaseHalPlugin() {

    private var _eventCallback: HalPluginEventCallback? = null
    protected val mutex = Mutex()

    override fun isSupported(): Boolean {
        val ctx = context ?: return false
        val intent = Intent("woyou.aidlservice.jiuiv5.IWoyouService")
            .setPackage("woyou.aidlservice.jiuiv5")
        return ctx.packageManager.resolveService(intent, 0) != null
    }

    override fun initialize(pluginContext: PluginContext) {
        this.context?.let { SharedPrinterManager.acquire(it) }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        _eventCallback = callback
    }

    protected fun emitEvent(event: String, payload: String) {
        _eventCallback?.onEvent(event, payload)
    }

    override suspend fun onExecute(method: String, params: String): CommandResult {
        val json = if (params.isBlank() || params == "{}") JSONObject() else JSONObject(params)
        return try {
            handleExecute(method, params, json)
        } catch (e: Exception) {
            CommandResult.internalError(e.message ?: "Unknown error")
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
    ): CommandResult

    /**
     * Mutex-guarded SDK call. Use for operations that should be serialised
     * (queries, synchronous SDK calls). Skip for long-running async operations
     * (printTrans, printCanvas, printFile, cashDrawer.open) to avoid blocking.
     */
    protected suspend fun guardedExecute(block: suspend () -> CommandResult): CommandResult =
        mutex.withLock {
            try { block() } catch (e: Exception) { CommandResult.internalError(e.message ?: "Unknown error") }
        }
}
