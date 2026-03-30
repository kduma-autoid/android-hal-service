package dev.duma.android.hal.plugins.sunmi.sunmiscannersdk.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.rfid.SunmiRfidPlugin

class SunmiRfidService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiRfidPlugin(applicationContext))
}
