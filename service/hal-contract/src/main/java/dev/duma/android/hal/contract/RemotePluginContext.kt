package dev.duma.android.hal.contract

import android.content.Context
import android.os.RemoteException
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

    override suspend fun execute(method: String, params: String): CommandResult {
        return withContext(Dispatchers.IO) {
            try {
                binder.execute(method, params)
            } catch (e: RemoteException) {
                CommandResult.unavailable("hal-service connection lost")
            }
        }
    }

    override fun getAvailableCapabilities(): List<String> {
        return try {
            binder.availableCapabilities
        } catch (e: RemoteException) {
            emptyList()
        }
    }

    override fun hasCapability(capability: String): Boolean {
        return try {
            binder.hasCapability(capability)
        } catch (e: RemoteException) {
            false
        }
    }

    override fun setPluginAvailable(available: Boolean) {
        try {
            // This method was appended to the AIDL interface. A host built against the
            // previous interface may reject the transaction; availability is advisory, so
            // that must degrade to a no-op rather than taking down the plugin process.
            binder.setPluginAvailable(available)
        } catch (_: RemoteException) {
            // Dead or old hal-service host.
        } catch (_: RuntimeException) {
            // A few old Binder implementations surface an unsupported transaction as a
            // runtime exception instead of RemoteException.
        }
    }

    override fun emitEvent(eventName: String, jsonData: String) {
        // Not supported in remote context
    }

    override fun onEvent(pattern: String, callback: (eventName: String, jsonData: String) -> Unit) {
        // Not supported in remote context
    }
}
