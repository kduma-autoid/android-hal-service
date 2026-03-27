package dev.duma.android.hal.transport.broadcast

import android.content.Context
import android.content.Intent
import dev.duma.android.hal.transport.core.EventTransport
import dev.duma.android.hal.transport.core.TransportConfig

/**
 * Broadcast transport for pushing events as Android broadcasts. Events are filtered
 * per-event configuration (enabledBroadcastEvents) and global toggle (isEnabled).
 * Broadcasts are public — any app with a matching BroadcastReceiver receives them.
 */
class BroadcastTransport : EventTransport {

    override val transportId = "broadcast"
    override val displayName = "Android Broadcast"
    override val isToggleable = true
    override var isEnabled = true
    override val isRunning: Boolean get() = running

    private var running = false
    private var context: Context? = null
    private var eventFilter: ((String) -> Boolean)? = null

    override fun start(config: TransportConfig) {
        this.context = config.context
        this.eventFilter = config.broadcastEventFilter
            ?: { it in config.enabledBroadcastEvents }
        running = true
    }

    override fun stop() {
        running = false
        context = null
        eventFilter = null
    }

    override fun pushEvent(eventName: String, jsonData: String) {
        if (!isEnabled) return
        if (eventFilter?.invoke(eventName) != true) return

        val intent = Intent("dev.duma.hal.event.$eventName").apply {
            putExtra("event", eventName)
            putExtra("data", jsonData)
        }
        context?.sendBroadcast(intent)
    }
}
