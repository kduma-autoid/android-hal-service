package dev.duma.android.hal.plugins.msr605x.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.msr605x.Msr605xPlugin

/**
 * Android Service exposing [Msr605xPlugin] via AIDL for out-of-process usage. hal-service binds it
 * while running, which keeps this process — and the plugin's USB connection and background
 * listening — alive. The USB permission is requested for (and held by) this app.
 */
class Msr605xService : Service() {
    private var wrapper: PluginServiceWrapper? = null

    override fun onBind(intent: Intent): IBinder {
        return PluginServiceWrapper(Msr605xPlugin(applicationContext), applicationContext)
            .also { wrapper = it }
    }

    override fun onDestroy() {
        wrapper?.dispose()
        wrapper = null
        super.onDestroy()
    }
}
