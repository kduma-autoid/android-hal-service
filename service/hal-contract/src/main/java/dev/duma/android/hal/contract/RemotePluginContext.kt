package dev.duma.android.hal.contract

import android.content.Context
import android.os.DeadObjectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PluginContext implementation for out-of-process plugins.
 * Delegates execute/capability queries to IPluginContext binder (IPC back to hal-service).
 * Event methods (emitEvent/onEvent) are no-ops — not supported in remote context.
 * applicationContext is from the local (bundle APK) process.
 */
class RemotePluginContext(
    private val binder: IPluginContext,
    override val applicationContext: Context
) : PluginContext {

    override suspend fun execute(method: String, params: String): String {
        return withContext(Dispatchers.IO) {
            try {
                binder.execute(method, params)
            } catch (e: DeadObjectException) {
                """{"error":"service_unavailable","message":"hal-service connection lost"}"""
            }
        }
    }

    override fun getAvailableCapabilities(): List<String> {
        return try {
            binder.availableCapabilities
        } catch (e: DeadObjectException) {
            emptyList()
        }
    }

    override fun hasCapability(capability: String): Boolean {
        return try {
            binder.hasCapability(capability)
        } catch (e: DeadObjectException) {
            false
        }
    }

    override fun emitEvent(eventName: String, jsonData: String) {
        // Not supported in remote context
    }

    override fun onEvent(pattern: String, callback: (eventName: String, jsonData: String) -> Unit) {
        // Not supported in remote context
    }
}
