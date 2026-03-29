package dev.duma.android.hal.plugins.sunmi.printerx.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.printerx.printer.SunmiPrinterXPrinterPlugin

class SunmiPrinterXPrinterService : Service() {
    override fun onBind(intent: Intent): IBinder =
        PluginServiceWrapper(SunmiPrinterXPrinterPlugin(applicationContext))
}
