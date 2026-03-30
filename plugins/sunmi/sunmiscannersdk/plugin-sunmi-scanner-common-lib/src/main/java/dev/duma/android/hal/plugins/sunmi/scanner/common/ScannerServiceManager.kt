package dev.duma.android.hal.plugins.sunmi.scanner.common

import android.content.Context
import com.sunmi.scanner.sdk.ScannerManager
import com.sunmi.sdk.ServiceConnectStatus

/**
 * Reference-counting wrapper around [ScannerManager] singleton.
 * Multiple plugins can safely [acquire]/[release] without interfering with each other.
 * The underlying SDK service is initialized on the first acquire and released when the
 * last consumer releases.
 */
object ScannerServiceManager {

    private val lock = Any()
    private var refCount = 0

    fun acquire(context: Context) {
        synchronized(lock) {
            if (refCount == 0) {
                ScannerManager.getInstance().init(context.applicationContext)
            }
            refCount++
        }
    }

    fun release() {
        synchronized(lock) {
            if (refCount > 0) {
                refCount--
                if (refCount == 0) {
                    ScannerManager.getInstance().release()
                }
            }
        }
    }

    fun isConnected(): Boolean =
        ScannerManager.getInstance().isServiceConnect()

    fun addConnectionListener(listener: ServiceConnectStatus) {
        ScannerManager.getInstance().addServiceConnectStatus(listener)
    }

    fun removeConnectionListener(listener: ServiceConnectStatus) {
        ScannerManager.getInstance().removeServiceConnectStatus(listener)
    }
}
