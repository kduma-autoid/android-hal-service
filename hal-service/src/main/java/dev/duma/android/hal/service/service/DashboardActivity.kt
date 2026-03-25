package dev.duma.android.hal.service.service

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Main dashboard activity showing HAL Service status, registered plugins and transports.
 * Provides controls to start/stop the service. Basic programmatic layout —
 * will be improved with proper XML layouts in a later stage.
 */
class DashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val titleText = TextView(this).apply {
            text = "HAL Service Dashboard"
            textSize = 24f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(titleText)

        val statusText = TextView(this).apply {
            text = "Service status: checking..."
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(statusText)

        val startButton = Button(this).apply {
            text = "Start Service"
            setOnClickListener {
                val intent = Intent(this@DashboardActivity, HalService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                statusText.text = "Service status: starting..."
            }
        }
        layout.addView(startButton)

        val stopButton = Button(this).apply {
            text = "Stop Service"
            setOnClickListener {
                stopService(Intent(this@DashboardActivity, HalService::class.java))
                statusText.text = "Service status: stopped"
            }
        }
        layout.addView(stopButton)

        val infoText = TextView(this).apply {
            text = buildString {
                appendLine("Port: ${HalService.PORT}")
                appendLine("WebSocket: ws://localhost:${HalService.PORT}/ws")
                appendLine("HTTP API: http://localhost:${HalService.PORT}/api")
                appendLine()
                appendLine("Endpoints:")
                appendLine("  POST /api/token — Request token")
                appendLine("  POST /api/execute — Execute command")
                appendLine("  GET /api/health — Health check")
                appendLine("  GET /api/status — Service status")
                appendLine("  GET /api/describe — API description")
            }
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        layout.addView(infoText)

        scrollView.addView(layout)
        setContentView(scrollView)
    }
}
