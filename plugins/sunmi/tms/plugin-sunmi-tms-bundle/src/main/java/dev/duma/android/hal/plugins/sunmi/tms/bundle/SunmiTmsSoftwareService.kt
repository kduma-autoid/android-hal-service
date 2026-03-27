package dev.duma.android.hal.plugins.sunmi.tms.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.tms.software.SunmiTmsSoftwarePlugin

class SunmiTmsSoftwareService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiTmsSoftwarePlugin(applicationContext))
}
