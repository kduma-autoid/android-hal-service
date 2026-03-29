package dev.duma.android.hal.plugins.sunmi.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.subscreen.SunmiSubScreenPlugin

/**
 * Android Service exposing [SunmiSubScreenPlugin] via AIDL for out-of-process usage
 * in the main sunmi bundle.
 */
class SunmiSubScreenService : Service() {
    override fun onBind(intent: Intent): IBinder {
        return PluginServiceWrapper(SunmiSubScreenPlugin(applicationContext), applicationContext)
    }
}
