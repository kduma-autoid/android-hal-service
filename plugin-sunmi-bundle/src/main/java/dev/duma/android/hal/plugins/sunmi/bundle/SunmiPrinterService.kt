package dev.duma.android.hal.plugins.sunmi.bundle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.duma.android.hal.contract.PluginServiceWrapper
import dev.duma.android.hal.plugins.sunmi.printer.SunmiPrinterPlugin

/**
 * Android Service exposing [SunmiPrinterPlugin] via AIDL for out-of-process usage.
 * Used when the plugin is deployed as a standalone bundle APK rather than compiled
 * into hal-service.
 */
class SunmiPrinterService : Service() {
    override fun onBind(intent: Intent): IBinder {
        return PluginServiceWrapper(SunmiPrinterPlugin())
    }
}
