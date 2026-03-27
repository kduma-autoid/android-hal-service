package dev.duma.android.hal.service.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.duma.android.hal.transport.core.EventTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main dashboard activity showing HAL Service status, registered plugins, transports,
 * broadcast event config, and token management. Provides controls to start/stop the
 * service, toggle transports, configure broadcast events, and revoke tokens.
 */
class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    private lateinit var overlayStatusText: TextView
    private lateinit var overlayButton: Button
    private lateinit var notifStatusText: TextView
    private lateinit var notifButton: Button
    private lateinit var serviceStatusText: TextView
    private lateinit var transportContainer: LinearLayout
    private lateinit var broadcastContainer: LinearLayout
    private lateinit var pluginContainer: LinearLayout
    private lateinit var tokenContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        layout.addView(TextView(this).apply {
            text = "HAL Service Dashboard"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        })

        // Permissions section
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
        serviceStatusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }
        layout.addView(serviceStatusText)

        layout.addView(Button(this).apply {
            text = "Start Service"
            setOnClickListener {
                val intent = Intent(this@DashboardActivity, HalService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                serviceStatusText.text = "Service status: starting..."
                // Service initializes asynchronously — poll until ready
                pollServiceReady()
            }
        })

        layout.addView(Button(this).apply {
            text = "Stop Service"
            setOnClickListener {
                stopService(Intent(this@DashboardActivity, HalService::class.java))
                serviceStatusText.text = "Service status: stopped"
                refreshDynamicSections()
            }
        })

        // Endpoint info
        layout.addView(TextView(this).apply {
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
        })

        // Dynamic sections
        transportContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        broadcastContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pluginContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tokenContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layout.addView(transportContainer)
        layout.addView(broadcastContainer)
        layout.addView(pluginContainer)
        layout.addView(tokenContainer)

        scrollView.addView(layout)
        setContentView(scrollView)

        updatePermissionStatus()
        requestMissingPermissions()
        autoStartService()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateServiceStatus()
        refreshDynamicSections()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    private fun updateServiceStatus() {
        serviceStatusText.text = if (HalService.isServiceRunning) {
            "Service status: running"
        } else {
            "Service status: stopped"
        }
    }

    private fun refreshDynamicSections() {
        buildTransportSection()
        buildBroadcastSection()
        buildPluginSection()
        loadTokenSection()
    }

    // --- Transport Section ---

    private fun buildTransportSection() {
        transportContainer.removeAllViews()
        transportContainer.addView(sectionHeader("Transports"))

        val registry = HalService.transportRegistry
        if (registry == null) {
            transportContainer.addView(notRunningText())
            return
        }

        val commandTransports = registry.getCommandTransports()
        val eventTransports = registry.getEventTransports()

        if (commandTransports.isNotEmpty()) {
            transportContainer.addView(subHeader("Command Transports"))
            for (transport in commandTransports) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 8, 16, 8)
                }

                row.addView(TextView(this).apply {
                    text = "${transport.displayName} (${transport.transportId})"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                row.addView(statusBadge(transport.isRunning))

                // IntentTransport toggle
                if (transport.transportId == "intent") {
                    try {
                        val intentTransportClass = Class.forName("dev.duma.android.hal.transport.intent.IntentTransport")
                        val field = intentTransportClass.getDeclaredField("isTransportEnabled")
                        val companion = intentTransportClass.getDeclaredField("Companion").get(null)
                        val getter = companion.javaClass.getMethod("getIsTransportEnabled")
                        val setter = companion.javaClass.getMethod("setIsTransportEnabled", Boolean::class.java)
                        val currentValue = getter.invoke(companion) as Boolean

                        @Suppress("UseSwitchCompatOrMaterialCode")
                        row.addView(Switch(this).apply {
                            isChecked = currentValue
                            setOnCheckedChangeListener { _, isChecked ->
                                setter.invoke(companion, isChecked)
                            }
                        })
                    } catch (_: Exception) { }
                }

                transportContainer.addView(row)
            }
        }

        if (eventTransports.isNotEmpty()) {
            transportContainer.addView(subHeader("Event Transports"))
            for (transport in eventTransports) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 8, 16, 8)
                }

                row.addView(TextView(this).apply {
                    text = "${transport.displayName} (${transport.transportId})"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                row.addView(statusBadge(transport.isRunning))

                if (transport.isToggleable) {
                    @Suppress("UseSwitchCompatOrMaterialCode")
                    row.addView(Switch(this).apply {
                        isChecked = transport.isEnabled
                        setOnCheckedChangeListener { _, isChecked ->
                            transport.isEnabled = isChecked
                        }
                    })
                }

                transportContainer.addView(row)
            }
        }
    }

    // --- Broadcast Events Section ---

    private fun buildBroadcastSection() {
        broadcastContainer.removeAllViews()
        broadcastContainer.addView(sectionHeader("Broadcast Events"))

        val pluginReg = HalService.pluginRegistry
        val config = HalService.broadcastConfig
        if (pluginReg == null || config == null) {
            broadcastContainer.addView(notRunningText())
            return
        }

        broadcastContainer.addView(TextView(this).apply {
            text = "Select events forwarded via Android Broadcast:"
            textSize = 13f
            setPadding(16, 0, 16, 8)
        })

        val allEvents = pluginReg.getSupportedDescriptors()
            .flatMap { it.events }
            .distinctBy { it.name }

        if (allEvents.isEmpty()) {
            broadcastContainer.addView(TextView(this).apply {
                text = "No events available"
                textSize = 13f
                setPadding(16, 4, 16, 4)
                setTextColor(Color.GRAY)
            })
            return
        }

        for (event in allEvents) {
            broadcastContainer.addView(CheckBox(this).apply {
                text = "${event.name} — ${event.description}"
                textSize = 13f
                isChecked = config.isEventEnabled(event.name)
                setPadding(16, 0, 16, 0)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) config.enableEvent(event.name)
                    else config.disableEvent(event.name)
                }
            })
        }
    }

    // --- Plugin Section ---

    private fun buildPluginSection() {
        pluginContainer.removeAllViews()
        pluginContainer.addView(sectionHeader("Plugins"))

        val pluginReg = HalService.pluginRegistry
        if (pluginReg == null) {
            pluginContainer.addView(notRunningText())
            return
        }

        val descriptors = pluginReg.getAllDescriptors()
        if (descriptors.isEmpty()) {
            pluginContainer.addView(TextView(this).apply {
                text = "No plugins registered"
                textSize = 13f
                setPadding(16, 4, 16, 4)
                setTextColor(Color.GRAY)
            })
            return
        }

        val unsupportedIds = pluginReg.getUnsupportedPluginIds()

        for (desc in descriptors) {
            val isUnsupported = desc.pluginId in unsupportedIds
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                if (isUnsupported) alpha = 0.5f
            }

            block.addView(TextView(this).apply {
                text = "${desc.name} (${desc.pluginId}) v${desc.version}"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            })

            block.addView(TextView(this).apply {
                text = "Capabilities: ${desc.capabilities.joinToString(", ")}"
                textSize = 13f
            })

            val infoParts = mutableListOf<String>()
            if (desc.methods.isNotEmpty()) infoParts.add("${desc.methods.size} methods")
            if (desc.events.isNotEmpty()) infoParts.add("${desc.events.size} events")
            if (isUnsupported) infoParts.add("UNSUPPORTED")

            if (infoParts.isNotEmpty()) {
                block.addView(TextView(this).apply {
                    text = infoParts.joinToString(", ")
                    textSize = 13f
                    if (isUnsupported) {
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#C62828"))
                    } else {
                        setTextColor(Color.DKGRAY)
                    }
                })
            }

            pluginContainer.addView(block)
            pluginContainer.addView(divider())
        }
    }

    // --- Token Section ---

    private fun loadTokenSection() {
        tokenContainer.removeAllViews()
        tokenContainer.addView(sectionHeader("Tokens"))

        val dao = HalService.tokenDao
        if (dao == null) {
            tokenContainer.addView(notRunningText())
            return
        }

        tokenContainer.addView(Button(this).apply {
            text = "Refresh tokens"
            setOnClickListener { loadTokenSection() }
        })

        val loadingText = TextView(this).apply {
            text = "Loading..."
            textSize = 13f
            setPadding(16, 8, 16, 8)
        }
        tokenContainer.addView(loadingText)

        lifecycleScope.launch {
            val tokens = withContext(Dispatchers.IO) { dao.getAll() }
            loadingText.visibility = View.GONE

            if (tokens.isEmpty()) {
                tokenContainer.addView(TextView(this@DashboardActivity).apply {
                    text = "No active tokens"
                    textSize = 13f
                    setPadding(16, 4, 16, 4)
                    setTextColor(Color.GRAY)
                })
                return@launch
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

            for (token in tokens) {
                val block = LinearLayout(this@DashboardActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 12, 16, 12)
                }

                block.addView(TextView(this@DashboardActivity).apply {
                    text = "Client: ${token.clientId} (${token.clientType})"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                })

                block.addView(TextView(this@DashboardActivity).apply {
                    text = "Permissions: ${token.permissions}"
                    textSize = 13f
                })

                block.addView(TextView(this@DashboardActivity).apply {
                    val grantedDate = dateFormat.format(Date(token.grantedAt))
                    text = "Granted by: ${token.grantedBy} at $grantedDate"
                    textSize = 13f
                })

                block.addView(TextView(this@DashboardActivity).apply {
                    text = if (token.expiresAt != null) {
                        "Expires: ${dateFormat.format(Date(token.expiresAt))}"
                    } else {
                        "Expires: never"
                    }
                    textSize = 13f
                })

                block.addView(TextView(this@DashboardActivity).apply {
                    val masked = token.token.take(8) + "..."
                    text = "Token: $masked"
                    textSize = 12f
                    setTextColor(Color.GRAY)
                })

                block.addView(Button(this@DashboardActivity).apply {
                    text = "Revoke"
                    setOnClickListener {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { dao.deleteByToken(token.token) }
                            loadTokenSection()
                        }
                    }
                })

                tokenContainer.addView(block)
                tokenContainer.addView(divider())
            }
        }
    }

    // --- UI helpers ---

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 32, 0, 8)
    }

    private fun subHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setPadding(16, 12, 0, 4)
    }

    private fun statusBadge(running: Boolean): TextView = TextView(this).apply {
        text = if (running) "Running" else "Stopped"
        textSize = 13f
        setTextColor(if (running) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        setPadding(16, 0, 16, 0)
    }

    private fun notRunningText(): TextView = TextView(this).apply {
        text = "Service not running"
        textSize = 13f
        setPadding(16, 8, 16, 8)
        setTextColor(Color.GRAY)
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(16, 0, 16, 0) }
        setBackgroundColor(Color.LTGRAY)
    }

    private fun autoStartService() {
        if (!HalService.isServiceRunning) {
            val intent = Intent(this, HalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceStatusText.text = "Service status: starting..."
            pollServiceReady()
        }
    }

    private fun pollServiceReady() {
        lifecycleScope.launch {
            repeat(10) {
                delay(500)
                if (HalService.isServiceRunning) {
                    updateServiceStatus()
                    refreshDynamicSections()
                    return@launch
                }
            }
            serviceStatusText.text = "Service status: failed to start"
        }
    }

    // --- Permission handling ---

    private fun requestMissingPermissions() {
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

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    private fun updatePermissionStatus() {
        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatusText.text = if (canOverlay) {
            "Overlay permission: GRANTED"
        } else {
            "Overlay permission: NOT GRANTED (grant dialogs will use notifications)"
        }
        overlayButton.isEnabled = !canOverlay

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
            View.VISIBLE
        } else {
            View.GONE
        }
    }
}
