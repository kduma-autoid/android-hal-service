package dev.duma.android.hal.plugins.sunmi.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.docker.SunmiDockerPlugin

/**
 * Android Service exposing [SunmiDockerPlugin] via AIDL for out-of-process usage
 * in the main sunmi bundle.
 */
class SunmiDockerService : Service() {
    override fun onBind(intent: Intent): IBinder {
        return PluginServiceWrapper(SunmiDockerPlugin(applicationContext))
    }
}
