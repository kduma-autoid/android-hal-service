package dev.duma.android.hal.plugins.sunmi.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.printerx.manager.SunmiPrinterXManagerPlugin

class SunmiPrinterXManagerService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiPrinterXManagerPlugin(applicationContext))
}
