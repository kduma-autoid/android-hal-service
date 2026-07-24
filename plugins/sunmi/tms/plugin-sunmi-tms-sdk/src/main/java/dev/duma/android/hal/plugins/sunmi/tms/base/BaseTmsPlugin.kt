package dev.duma.android.hal.plugins.sunmi.tms.base

import android.content.Context
import com.sunmi.tms.api.TMSApi
import com.sunmi.tms.api.TMSServiceConnection
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.allMethods
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class BaseTmsPlugin(
    protected val context: Context? = null
) : HalPlugin {

    protected val tmsApi = TMSApi()
    protected var connected = false
    private var _eventCallback: HalPluginEventCallback? = null
    protected var pluginContext: PluginContext? = null
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

    override fun initialize(pluginContext: PluginContext) {
        this.pluginContext = pluginContext
        this.context?.let { ctx ->
            tmsApi.connect(ctx, object : TMSServiceConnection {
                override fun onServiceConnected() { connected = true; onTmsConnected() }
                override fun onServiceDisconnected() { connected = false; onTmsDisconnected() }
            })
        }
    }

    /** Called once the TMS service connection is established. Override to run post-connect checks. */
    protected open fun onTmsConnected() {}

    /** Called when the TMS service connection is lost. */
    protected open fun onTmsDisconnected() {}

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        _eventCallback = callback
    }

    protected fun emitEvent(event: String, payload: String) {
        _eventCallback?.onEvent(event, payload)
    }

    /**
     * Descriptor guard shared by all TMS plugins. Rejects any method absent from the plugin's
     * (stability-filtered) descriptor — including experimental methods stripped from a `stable`
     * build — so a plugin used directly as a library cannot invoke hidden methods by name.
     */
    final override suspend fun execute(method: String, params: String): CommandResult {
        if (getDescriptor().allMethods.none { it.name == method }) {
            return CommandResult.unsupportedMethod(method)
        }
        return onExecute(method, params)
    }

    /** Handle a method already validated to be declared in this plugin's descriptor. */
    protected abstract suspend fun onExecute(method: String, params: String): CommandResult

    protected suspend fun guardedExecute(block: suspend () -> CommandResult): CommandResult =
        mutex.withLock {
            if (!connected) return@withLock CommandResult.unavailable("TMSApi not connected")
            try { block() } catch (e: Exception) { CommandResult.internalError(e.message ?: "Unknown error") }
        }
}
