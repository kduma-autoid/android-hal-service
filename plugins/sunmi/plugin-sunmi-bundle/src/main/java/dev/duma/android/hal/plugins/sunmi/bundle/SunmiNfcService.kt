package dev.duma.android.hal.plugins.sunmi.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.nfc.SunmiNfcPlugin

/**
 * Android Service exposing [SunmiNfcPlugin] via AIDL for out-of-process usage
 * in the main sunmi bundle.
 */
class SunmiNfcService : Service() {
    private var wrapper: PluginServiceWrapper? = null

    override fun onBind(intent: Intent): IBinder {
        return wrapper ?: PluginServiceWrapper(
            SunmiNfcPlugin(applicationContext),
            applicationContext
        ).also { wrapper = it }
    }

    override fun onDestroy() {
        wrapper?.dispose()
        wrapper = null
        super.onDestroy()
    }
}
