package dev.duma.android.hal.plugins.sunmi.sunmiperipher.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.nfc.SunmiNfcPlugin

/**
 * Android Service exposing [SunmiNfcPlugin] via AIDL for out-of-process usage.
 */
class SunmiNfcService : Service() {
    override fun onBind(intent: Intent): IBinder {
        return PluginServiceWrapper(SunmiNfcPlugin(applicationContext))
    }
}
