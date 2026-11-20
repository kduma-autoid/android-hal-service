package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import android.app.Activity
import android.os.Bundle

/**
 * Target of `USB_DEVICE_ATTACHED` for the SS2222 in both its USB modes. It shows nothing and closes at
 * once: its only purpose is Android's "always open with" choice, which makes the system grant USB
 * permission to this app on every later attach. Without it each reconnect — and each switch between
 * keyboard and data mode, which re-enumerates the scanner under another identity — asks the user again.
 */
class Ss2222UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
