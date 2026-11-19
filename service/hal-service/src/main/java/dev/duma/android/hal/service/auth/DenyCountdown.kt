package dev.duma.android.hal.service.auth

import android.os.Handler
import android.os.Looper

/**
 * Shows the time left to answer a permission dialog on its Deny button — "Deny (42)" — so the user
 * knows the dialog won't wait forever (when it runs out, the request ends without access).
 * [deadlineMs] is wall-clock epoch ms, so it stays right when the dialog opens late (from a notification).
 */
internal class DenyCountdown(
    private val deadlineMs: Long,
    private val label: String,
    private val setText: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val leftMs = deadlineMs - System.currentTimeMillis()
            val seconds = (leftMs + 999) / 1000
            setText(if (seconds > 0) "$label ($seconds)" else label)
            if (seconds > 0) {
                // Next update right when the shown number changes.
                val untilNext = leftMs % 1000
                handler.postDelayed(this, if (untilNext > 0) untilNext else 1000)
            }
        }
    }

    fun start() = tick.run()

    fun stop() = handler.removeCallbacks(tick)
}
