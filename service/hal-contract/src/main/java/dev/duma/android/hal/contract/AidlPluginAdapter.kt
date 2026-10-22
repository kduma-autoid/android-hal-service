package dev.duma.android.hal.contract

import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapter wrapping IHardwarePlugin (Binder, out-of-process) into the HalPlugin interface (in-process).
 * Used by hal-service to communicate with plugins running in separate processes
 * (e.g. plugin-sunmi-bundle APK). Moves execute() to Dispatchers.IO, parses PluginDescriptor from JSON.
 */
class AidlPluginAdapter(
    private val binder: IHardwarePlugin
) : HalPlugin {

    /**
     * The remote callback is retained by the other process. Keep the exact Binder object so it can
     * be unregistered, and gate delivery as well: unregister is asynchronous from the point of view
     * of an already-running Binder transaction.
     */
    private class EventCallbackRegistration(
        private val callback: HalPluginEventCallback
    ) {
        val active = AtomicBoolean(true)
        val binderCallback = object : IPluginEventCallback.Stub() {
            override fun onEvent(eventName: String, jsonData: String) {
                if (active.get()) callback.onEvent(eventName, jsonData)
            }

            override fun onError(deviceType: String, code: Int, message: String) {
                if (active.get()) callback.onError(deviceType, code, message)
            }
        }
    }

    private val callbackLock = Any()
    private var callbackGeneration = 0L
    private var eventCallbackRegistration: EventCallbackRegistration? = null

    override val pluginId: String
        get() = binder.pluginId

    override val version: Int
        get() = binder.version

    override fun isSupported(): Boolean = binder.isSupported

    override fun getCapabilities(): List<String> = binder.capabilities

    override fun getDescriptor(): PluginDescriptor {
        val json = binder.descriptorJson
        return Json.decodeFromString(json)
    }

    override fun initialize(pluginContext: PluginContext) {
        val pluginContextBinder = object : IPluginContext.Stub() {
            override fun execute(method: String, jsonParams: String): CommandResult {
                return runBlocking(Dispatchers.IO) {
                    pluginContext.execute(method, jsonParams)
                }
            }

            override fun getAvailableCapabilities(): List<String> {
                return pluginContext.getAvailableCapabilities()
            }

            override fun hasCapability(capability: String): Boolean {
                return pluginContext.hasCapability(capability)
            }

            override fun setPluginAvailable(available: Boolean) {
                pluginContext.setPluginAvailable(available)
            }
        }
        binder.initialize(pluginContextBinder)
    }

    override suspend fun execute(method: String, params: String): CommandResult {
        return withContext(Dispatchers.IO) {
            binder.execute(method, params)
        }
    }

    override fun setEventCallback(callback: HalPluginEventCallback?) {
        val next = callback?.let(::EventCallbackRegistration)
        val generation: Long
        val old: EventCallbackRegistration?
        synchronized(callbackLock) {
            generation = ++callbackGeneration
            old = eventCallbackRegistration
            old?.active?.set(false)
            eventCallbackRegistration = next
        }
        if (old != null) safeUnregister(old)

        if (next == null) return

        // Binder calls intentionally happen outside the atomic state transition. A Binder endpoint
        // can re-enter the host, and a slow unregister/register must not serialize or deadlock a
        // concurrent clear. The active bit and generation check make a late transaction harmless.
        if (!isCurrentCallback(next, generation)) {
            next.active.set(false)
            safeUnregister(next)
            return
        }
        try {
            binder.registerEventCallback(next.binderCallback)
        } catch (_: RemoteException) {
            clearFailedCallback(next, generation)
            safeUnregister(next)
        } catch (_: RuntimeException) {
            clearFailedCallback(next, generation)
            safeUnregister(next)
        }

        // A newer setEventCallback may have won while registerEventCallback was in flight. Remove
        // this losing registration from the remote side; its callback gate was already closed.
        if (!isCurrentCallback(next, generation)) {
            next.active.set(false)
            synchronized(callbackLock) {
                if (eventCallbackRegistration === next) eventCallbackRegistration = null
            }
            safeUnregister(next)
        }
    }

    private fun isCurrentCallback(registration: EventCallbackRegistration, generation: Long): Boolean =
        synchronized(callbackLock) {
            eventCallbackRegistration === registration && callbackGeneration == generation
        }

    private fun clearFailedCallback(registration: EventCallbackRegistration, generation: Long) {
        synchronized(callbackLock) {
            registration.active.set(false)
            if (eventCallbackRegistration === registration && callbackGeneration == generation) {
                eventCallbackRegistration = null
            }
        }
    }

    private fun safeUnregister(registration: EventCallbackRegistration) {
        try {
            binder.unregisterEventCallback(registration.binderCallback)
        } catch (_: RemoteException) {
            // The remote service is already gone.
        } catch (_: RuntimeException) {
            // Keep clear/dispose safe for old Binder implementations as well.
        }
    }

    override fun dispose() {
        // HalPlugin.dispose is also used when a remote process disconnects. Do not leave the
        // callback object retained by a still-running/late Binder endpoint.
        setEventCallback(null)
    }
}
