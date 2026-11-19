package dev.duma.android.hal.plugins.dymo.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.dymo.m10.DymoM10Plugin

/**
 * Android Service exposing [DymoM10Plugin] via AIDL for out-of-process usage. hal-service binds it
 * while running, which keeps this process — and the plugin's USB connection and read loop — alive.
 * The USB permission is requested for (and held by) this app.
 */
class DymoService : Service() {
    private var wrapper: PluginServiceWrapper? = null

    override fun onBind(intent: Intent): IBinder {
        return PluginServiceWrapper(DymoM10Plugin(applicationContext), applicationContext)
            .also { wrapper = it }
    }

    override fun onDestroy() {
        wrapper?.dispose()
        wrapper = null
        super.onDestroy()
    }
}
