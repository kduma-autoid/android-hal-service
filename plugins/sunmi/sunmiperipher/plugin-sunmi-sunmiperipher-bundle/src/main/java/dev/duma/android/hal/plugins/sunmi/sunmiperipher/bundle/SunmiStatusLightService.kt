package dev.duma.android.hal.plugins.sunmi.sunmiperipher.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.statuslight.SunmiStatusLightPlugin

/**
 * Android Service exposing [SunmiStatusLightPlugin] via AIDL for out-of-process usage.
 */
class SunmiStatusLightService : Service() {
    private var wrapper: PluginServiceWrapper? = null

    override fun onBind(intent: Intent): IBinder {
        return PluginServiceWrapper(SunmiStatusLightPlugin(applicationContext), applicationContext)
            .also { wrapper = it }
    }

    override fun onDestroy() {
        wrapper?.dispose()
        wrapper = null
        super.onDestroy()
    }
}
