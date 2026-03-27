package dev.duma.android.hal.plugins.sunmi.tms.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.tms.kiosk.SunmiTmsKioskPlugin

class SunmiTmsKioskService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiTmsKioskPlugin(applicationContext))
}
