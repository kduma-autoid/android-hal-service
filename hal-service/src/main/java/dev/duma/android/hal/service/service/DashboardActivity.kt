package dev.duma.android.hal.service.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main dashboard activity showing HAL Service status, registered plugins and transports.
 * Provides controls to start/stop the service and manage permissions (overlay, notifications).
 */
class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    private lateinit var overlayStatusText: TextView
    private lateinit var overlayButton: Button
    private lateinit var notifStatusText: TextView
    private lateinit var notifButton: Button

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

        // Overlay permission section
        overlayStatusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(overlayStatusText)

        overlayButton = Button(this).apply {
            text = "Enable overlay permission"
            setOnClickListener {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }
        layout.addView(overlayButton)

        // Notification permission section
        notifStatusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        layout.addView(notifStatusText)

        notifButton = Button(this).apply {
            text = "Enable notifications"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(
                        this@DashboardActivity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATION_PERMISSION
                    )
                }
            }
        }
        layout.addView(notifButton)

        // Service controls
        val statusText = TextView(this).apply {
            text = "Service status: checking..."
            textSize = 16f
            setPadding(0, 24, 0, 16)
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

        updatePermissionStatus()
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    private fun requestMissingPermissions() {
        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }

        // Prompt for overlay permission if not granted
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    private fun updatePermissionStatus() {
        // Overlay
        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatusText.text = if (canOverlay) {
            "Overlay permission: GRANTED"
        } else {
            "Overlay permission: NOT GRANTED (grant dialogs will use notifications)"
        }
        overlayButton.isEnabled = !canOverlay

        // Notifications
        val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        notifStatusText.text = if (canNotify) {
            "Notification permission: GRANTED"
        } else {
            "Notification permission: NOT GRANTED (fallback notifications won't show)"
        }
        notifButton.isEnabled = !canNotify
        notifButton.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }
}
