package dev.duma.android.hal.plugins.sunmi.nfc

import android.content.Context
import android.database.ContentObserver
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Surface

internal interface NfcAntennaEnvironment {
    fun readSettings(): SettingsSnapshot
    fun readDisplay(): DisplaySnapshot
    fun start(onChanged: () -> Unit)
    fun stop()
}

internal class AndroidNfcAntennaEnvironment(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper())
) : NfcAntennaEnvironment {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private var onChanged: (() -> Unit)? = null
    private var settingsObserverRegistered = false
    private var displayListenerRegistered = false

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onChanged?.invoke()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onChanged?.invoke()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onChanged?.invoke()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onChanged?.invoke()
        }
    }

    override fun readSettings(): SettingsSnapshot {
        var locationReadable = true
        val location = try {
            Settings.Global.getString(resolver, KEY_WATERMARK_LOCATION)
        } catch (_: Exception) {
            locationReadable = false
            null
        }
        val controller = try {
            when (Settings.Global.getString(resolver, KEY_CURRENT_CONTROLLER)?.trim()?.toIntOrNull()) {
                0 -> NfcController.BUILTIN
                1 -> NfcController.EXTERNAL
                else -> NfcController.UNKNOWN
            }
        } catch (_: Exception) {
            NfcController.UNKNOWN
        }
        val externalInfo = try {
            Settings.Global.getString(resolver, KEY_EXTERNAL_DISPLAY_INFO)
        } catch (_: Exception) {
            null
        }
        return SettingsSnapshot(
            readable = locationReadable,
            locationJson = location,
            controller = controller,
            externalDisplayInfoJson = externalInfo
        )
    }

    @Suppress("DEPRECATION")
    override fun readDisplay(): DisplaySnapshot {
        val display = try {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (_: Exception) {
            null
        } ?: return DisplaySnapshot.Unavailable

        return try {
            val rotationDegrees = when (display.rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> return DisplaySnapshot.UnsupportedMetrics
            }
            val realSize = Point().also(display::getRealSize)
            val mode = display.mode
            val naturalWidth = mode.physicalWidth
            val naturalHeight = mode.physicalHeight
            val expectedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) naturalHeight else naturalWidth
            val expectedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) naturalWidth else naturalHeight

            if (
                naturalWidth <= 0 || naturalHeight <= 0 || realSize.x <= 0 || realSize.y <= 0 ||
                realSize.x != expectedWidth || realSize.y != expectedHeight
            ) {
                DisplaySnapshot.UnsupportedMetrics
            } else {
                DisplaySnapshot.Available(
                    DisplayMetricsSnapshot(
                        displayId = display.displayId,
                        naturalWidthPx = naturalWidth,
                        naturalHeightPx = naturalHeight,
                        displayWidthPx = realSize.x,
                        displayHeightPx = realSize.y,
                        rotationDegrees = rotationDegrees
                    )
                )
            }
        } catch (_: Exception) {
            DisplaySnapshot.UnsupportedMetrics
        }
    }

    override fun start(onChanged: () -> Unit) {
        stop()
        this.onChanged = onChanged
        for (key in listOf(KEY_WATERMARK_LOCATION, KEY_CURRENT_CONTROLLER, KEY_EXTERNAL_DISPLAY_INFO)) {
            try {
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, settingsObserver)
                settingsObserverRegistered = true
            } catch (_: Exception) {
                // Access to one vendor key must not disable observations of the others.
            }
        }
        try {
            displayManager?.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = displayManager != null
        } catch (_: Exception) {
            displayListenerRegistered = false
        }
    }

    override fun stop() {
        if (settingsObserverRegistered) {
            try {
                resolver.unregisterContentObserver(settingsObserver)
            } catch (_: Exception) {
                // Idempotent teardown.
            }
            settingsObserverRegistered = false
        }
        if (displayListenerRegistered) {
            try {
                displayManager?.unregisterDisplayListener(displayListener)
            } catch (_: Exception) {
                // Idempotent teardown.
            }
            displayListenerRegistered = false
        }
        onChanged = null
    }

    internal companion object {
        const val KEY_WATERMARK_LOCATION = "device_mgr_nfc_watermark_location"
        const val KEY_CURRENT_CONTROLLER = "device_mgr_nfc_cur_controller"
        const val KEY_EXTERNAL_DISPLAY_INFO = "sub_display_device_info"
    }
}

internal object UnavailableNfcAntennaEnvironment : NfcAntennaEnvironment {
    override fun readSettings() = SettingsSnapshot.UNREADABLE
    override fun readDisplay(): DisplaySnapshot = DisplaySnapshot.Unavailable
    override fun start(onChanged: () -> Unit) = Unit
    override fun stop() = Unit
}
