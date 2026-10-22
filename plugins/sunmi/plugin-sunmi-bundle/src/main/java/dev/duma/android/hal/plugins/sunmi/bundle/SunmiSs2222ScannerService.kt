package dev.duma.android.hal.plugins.sunmi.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.scanner.ss2222.SunmiSs2222ScannerPlugin

class SunmiSs2222ScannerService : Service() {
    private var wrapper: PluginServiceWrapper? = null

    override fun onBind(intent: Intent): IBinder = wrapper ?: PluginServiceWrapper(
        SunmiSs2222ScannerPlugin(applicationContext), applicationContext
    ).also { wrapper = it }

    override fun onDestroy() {
        wrapper?.dispose()
        wrapper = null
        super.onDestroy()
    }
}
