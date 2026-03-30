package dev.duma.android.hal.plugins.sunmi.scanner.inner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver for Sunmi scanner events.
 * Listens for scan data, scan start, and scan stop broadcasts.
 * Coexists with the SDK's decodeCallback — this provides source_byte and scan lifecycle events.
 */
internal class ScannerBroadcastReceiver(
    private val onScan: (data: String, sourceBytes: String) -> Unit,
    private val onScanStart: () -> Unit,
    private val onScanStop: () -> Unit
) : BroadcastReceiver() {

    companion object {
        private const val ACTION_DATA = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"
        private const val ACTION_START = "com.sunmi.scanner.ACTION_SCAN_START"
        private const val ACTION_END = "com.sunmi.scanner.ACTION_SCAN_END"

        fun buildIntentFilter(): IntentFilter = IntentFilter().apply {
            addAction(ACTION_DATA)
            addAction(ACTION_START)
            addAction(ACTION_END)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DATA -> {
                val code = try { intent.getStringExtra("data") } catch (_: Exception) { null }
                val rawBytes = try { intent.getByteArrayExtra("source_byte") } catch (_: Exception) { null }
                val sourceBase64 = if (rawBytes != null) Base64.encodeToString(rawBytes, Base64.NO_WRAP) else ""
                if (!code.isNullOrEmpty()) {
                    onScan(code, sourceBase64)
                }
            }
            ACTION_START -> onScanStart()
            ACTION_END -> onScanStop()
        }
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(context, this, buildIntentFilter(), ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregister(context: Context) {
        try { context.unregisterReceiver(this) } catch (_: Exception) { }
    }
}
