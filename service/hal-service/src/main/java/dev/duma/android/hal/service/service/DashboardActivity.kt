package dev.duma.android.hal.service.service

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import dev.duma.android.hal.service.plugin.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main dashboard activity with TabLayout for organized display of HAL Service status,
 * transports, plugins, broadcast config, and token management.
 */
class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    private lateinit var tabLayout: TabLayout
    private lateinit var contentFrame: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "HAL Service"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        tabLayout = TabLayout(this).apply {
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
        }
        tabLayout.addTab(tabLayout.newTab().setText("Dashboard"))
        tabLayout.addTab(tabLayout.newTab().setText("Transports"))
        tabLayout.addTab(tabLayout.newTab().setText("Plugins"))
        tabLayout.addTab(tabLayout.newTab().setText("Tokens"))
        root.addView(tabLayout)

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        root.addView(contentFrame)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = refreshCurrentTab()
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) = refreshCurrentTab()
        })

        setContentView(root)
        tabLayout.selectTab(tabLayout.getTabAt(0))

        requestMissingPermissions()
        autoStartService()
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentTab()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (tabLayout.selectedTabPosition == 0) refreshCurrentTab()
    }

    private fun refreshCurrentTab() {
        contentFrame.removeAllViews()
        when (tabLayout.selectedTabPosition) {
            0 -> contentFrame.addView(buildDashboardTab())
            1 -> contentFrame.addView(buildTransportsTab())
            2 -> contentFrame.addView(buildPluginsTab())
            3 -> contentFrame.addView(buildTokensTab())
        }
    }

    // ==================== Tab 1: Dashboard ====================

    private fun buildDashboardTab(): View {
        val layout = tabContent()

        // Service status
        val running = HalService.isServiceRunning
        layout.addView(TextView(this).apply {
            text = if (running) "Service status: running" else "Service status: stopped"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (running) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            setPadding(0, 0, 0, 16)
        })

        // Start/Stop
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        row.addView(Button(this).apply {
            text = "Start"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener {
                val intent = Intent(this@DashboardActivity, HalService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
                pollServiceReady()
            }
        })
        row.addView(Button(this).apply {
            text = "Stop"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            setOnClickListener {
                stopService(Intent(this@DashboardActivity, HalService::class.java))
                refreshCurrentTab()
            }
        })
        layout.addView(row)

        // Permissions
        layout.addView(sectionHeader("Permissions"))

        val canOverlay = Settings.canDrawOverlays(this)
        layout.addView(TextView(this).apply {
            text = "Overlay: " + if (canOverlay) "GRANTED" else "NOT GRANTED"
            textSize = 14f
            setTextColor(if (canOverlay) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            setPadding(0, 4, 0, 4)
        })
        if (!canOverlay) {
            layout.addView(Button(this).apply {
                text = "Enable overlay permission"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            })
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val canNotify = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            layout.addView(TextView(this).apply {
                text = "Notifications: " + if (canNotify) "GRANTED" else "NOT GRANTED"
                textSize = 14f
                setTextColor(if (canNotify) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
                setPadding(0, 4, 0, 4)
            })
            if (!canNotify) {
                layout.addView(Button(this).apply {
                    text = "Enable notifications"
                    setOnClickListener {
                        ActivityCompat.requestPermissions(
                            this@DashboardActivity,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_NOTIFICATION_PERMISSION
                        )
                    }
                })
            }
        }

        // Super permissions toggle
        layout.addView(sectionHeader("Security"))
        val superPrefs = getSharedPreferences("hal_super", MODE_PRIVATE)
        @Suppress("UseSwitchCompatOrMaterialCode")
        layout.addView(Switch(this).apply {
            text = "Allow super permissions via user dialog"
            textSize = 14f
            isChecked = superPrefs.getBoolean("allow_super_via_dialog", false)
            setOnCheckedChangeListener { _, isChecked ->
                superPrefs.edit().putBoolean("allow_super_via_dialog", isChecked).apply()
            }
        })
        layout.addView(TextView(this).apply {
            text = "When disabled, super permissions can only be granted via developer key JWT."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 0)
        })

        // Endpoint info
        layout.addView(sectionHeader("Endpoints"))
        layout.addView(TextView(this).apply {
            text = buildString {
                appendLine("Port: ${HalService.PORT}")
                appendLine("WebSocket: ws://localhost:${HalService.PORT}/ws")
                appendLine("HTTP API: http://localhost:${HalService.PORT}/api")
                appendLine()
                appendLine("POST /api/token — Request token")
                appendLine("POST /api/execute — Execute command")
                appendLine("GET  /api/health — Health check")
                appendLine("GET  /api/status — Service status")
                appendLine("GET  /api/describe — API description")
            }
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        })

        return wrapInScrollView(layout)
    }

    // ==================== Tab 2: Transports ====================

    private fun buildTransportsTab(): View {
        val layout = tabContent()

        val registry = HalService.transportRegistry
        if (registry == null) {
            layout.addView(notRunningText())
            return wrapInScrollView(layout)
        }

        val commandTransports = registry.getCommandTransports()
        val eventTransports = registry.getEventTransports()

        if (commandTransports.isNotEmpty()) {
            layout.addView(sectionHeader("Command Transports"))
            for (transport in commandTransports) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }

                row.addView(TextView(this).apply {
                    text = "${transport.displayName}"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                row.addView(statusBadge(transport.isRunning))

                if (transport.transportId == "intent") {
                    try {
                        val cls = Class.forName("dev.duma.android.hal.transport.intent.IntentTransport")
                        val companion = cls.getDeclaredField("Companion").get(null)
                        val getter = companion.javaClass.getMethod("getIsTransportEnabled")
                        val setter = companion.javaClass.getMethod("setIsTransportEnabled", Boolean::class.java)

                        @Suppress("UseSwitchCompatOrMaterialCode")
                        row.addView(Switch(this).apply {
                            isChecked = getter.invoke(companion) as Boolean
                            setOnCheckedChangeListener { _, isChecked -> setter.invoke(companion, isChecked) }
                        })
                    } catch (_: Exception) { }
                }

                layout.addView(row)
                layout.addView(divider())
            }
        }

        if (eventTransports.isNotEmpty()) {
            layout.addView(sectionHeader("Event Transports"))
            for (transport in eventTransports) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }

                row.addView(TextView(this).apply {
                    text = "${transport.displayName}"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                row.addView(statusBadge(transport.isRunning))

                if (transport.isToggleable) {
                    @Suppress("UseSwitchCompatOrMaterialCode")
                    row.addView(Switch(this).apply {
                        isChecked = transport.isEnabled
                        setOnCheckedChangeListener { _, isChecked -> transport.isEnabled = isChecked }
                    })
                }

                layout.addView(row)
                layout.addView(divider())
            }
        }

        // Broadcast events config
        val pluginReg = HalService.pluginRegistry
        val config = HalService.broadcastConfig
        if (pluginReg != null && config != null) {
            val allEvents = pluginReg.getSupportedDescriptors()
                .flatMap { it.events }
                .distinctBy { it.name }

            if (allEvents.isNotEmpty()) {
                layout.addView(sectionHeader("Broadcast Events"))
                layout.addView(TextView(this).apply {
                    text = "Events forwarded via Android Broadcast:"
                    textSize = 13f
                    setPadding(0, 0, 0, 8)
                })

                for (event in allEvents) {
                    layout.addView(CheckBox(this).apply {
                        text = "${event.name} — ${event.description}"
                        textSize = 13f
                        isChecked = config.isEventEnabled(event.name)
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) config.enableEvent(event.name)
                            else config.disableEvent(event.name)
                        }
                    })
                }
            }
        }

        return wrapInScrollView(layout)
    }

    // ==================== Tab 3: Plugins ====================

    private fun buildPluginsTab(): View {
        val layout = tabContent()

        val pluginReg = HalService.pluginRegistry
        if (pluginReg == null) {
            layout.addView(notRunningText())
            return wrapInScrollView(layout)
        }

        val descriptors = pluginReg.getAllDescriptors()
        val unsupportedIds = pluginReg.getUnsupportedPluginIds()
        val supported = descriptors.filter { it.pluginId !in unsupportedIds }
        val unsupported = descriptors.filter { it.pluginId in unsupportedIds }

        if (supported.isEmpty() && unsupported.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "No plugins registered"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
        } else {
            for (desc in supported) {
                layout.addView(buildPluginBlock(desc, pluginReg, isUnsupported = false))
                layout.addView(divider())
            }

            if (unsupported.isNotEmpty()) {
                val unsupportedContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }

                for (desc in unsupported) {
                    unsupportedContainer.addView(buildPluginBlock(desc, pluginReg, isUnsupported = true))
                    unsupportedContainer.addView(divider())
                }

                val toggleButton = Button(this).apply {
                    text = "${unsupported.size} unsupported plugin${if (unsupported.size != 1) "s" else ""}"
                    setOnClickListener {
                        if (unsupportedContainer.visibility == View.GONE) {
                            unsupportedContainer.visibility = View.VISIBLE
                            text = "Hide unsupported plugins"
                        } else {
                            unsupportedContainer.visibility = View.GONE
                            text = "${unsupported.size} unsupported plugin${if (unsupported.size != 1) "s" else ""}"
                        }
                    }
                }

                layout.addView(toggleButton)
                layout.addView(unsupportedContainer)
            }
        }

        return wrapInScrollView(layout)
    }

    private fun buildPluginBlock(desc: dev.duma.android.hal.contract.PluginDescriptor, pluginReg: PluginRegistry, isUnsupported: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            if (isUnsupported) alpha = 0.5f
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.attr.selectableItemBackground.let { attr ->
                val ta = obtainStyledAttributes(intArrayOf(attr))
                val resId = ta.getResourceId(0, 0)
                ta.recycle()
                resId
            })
            setOnClickListener {
                startActivity(Intent(this@DashboardActivity, PluginDetailActivity::class.java).apply {
                    putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, desc.pluginId)
                })
            }

            addView(TextView(this@DashboardActivity).apply {
                text = desc.name
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            })

            addView(TextView(this@DashboardActivity).apply {
                text = desc.pluginId
                textSize = 13f
                setTextColor(Color.GRAY)
            })

            addView(TextView(this@DashboardActivity).apply {
                text = "Version: ${desc.version}"
                textSize = 13f
            })

            val info = pluginReg.getPluginInfo(desc.pluginId)
            val sourceText = when (info?.source) {
                PluginRegistry.PluginSource.EXTERNAL -> "External (${info.packageName})"
                PluginRegistry.PluginSource.BUILT_IN -> "Built-in"
                else -> "Unknown"
            }
            addView(TextView(this@DashboardActivity).apply {
                text = "Source: $sourceText"
                textSize = 13f
            })

            addView(TextView(this@DashboardActivity).apply {
                text = "Capabilities: ${desc.capabilities.joinToString(", ")}"
                textSize = 13f
            })

            val infoParts = mutableListOf<String>()
            if (desc.methods.isNotEmpty()) infoParts.add("${desc.methods.size} methods")
            if (desc.events.isNotEmpty()) infoParts.add("${desc.events.size} events")
            if (isUnsupported) infoParts.add("UNSUPPORTED")

            if (infoParts.isNotEmpty()) {
                addView(TextView(this@DashboardActivity).apply {
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
        }
    }

    // ==================== Tab 4: Tokens ====================

    private fun buildTokensTab(): View {
        val dao = HalService.tokenDao
        if (dao == null) {
            val layout = tabContent()
            layout.addView(notRunningText())
            return wrapInScrollView(layout)
        }

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DashboardActivity)
            setPadding(32, 16, 32, 32)
            clipToPadding = false
        }

        val swipeRefresh = SwipeRefreshLayout(this).apply {
            addView(recyclerView)
        }

        val adapter = TokenAdapter()
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val position = vh.adapterPosition
                val token = adapter.tokens[position]
                AlertDialog.Builder(this@DashboardActivity)
                    .setTitle("Revoke token")
                    .setMessage("Revoke token for client \"${token.clientId}\"?")
                    .setPositiveButton("Revoke") { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { dao.deleteByToken(token.token) }
                            loadTokens(adapter, swipeRefresh)
                        }
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        adapter.notifyItemChanged(position)
                    }
                    .setOnCancelListener {
                        adapter.notifyItemChanged(position)
                    }
                    .show()
            }
        })
        touchHelper.attachToRecyclerView(recyclerView)

        swipeRefresh.setOnRefreshListener { loadTokens(adapter, swipeRefresh) }
        loadTokens(adapter, swipeRefresh)
        return swipeRefresh
    }

    private fun loadTokens(adapter: TokenAdapter, swipeRefresh: SwipeRefreshLayout) {
        swipeRefresh.isRefreshing = true
        val dao = HalService.tokenDao ?: return

        lifecycleScope.launch {
            val tokens = withContext(Dispatchers.IO) { dao.getAll() }
            swipeRefresh.isRefreshing = false
            adapter.tokens = tokens
            adapter.notifyDataSetChanged()
        }
    }

    private inner class TokenAdapter : RecyclerView.Adapter<TokenAdapter.VH>() {
        var tokens: List<dev.duma.android.hal.service.auth.TokenEntity> = emptyList()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        inner class VH(val layout: LinearLayout) : RecyclerView.ViewHolder(layout)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            }
            return VH(layout)
        }

        override fun getItemCount() = tokens.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val token = tokens[position]
            holder.layout.removeAllViews()

            holder.layout.addView(TextView(this@DashboardActivity).apply {
                text = "Client: ${token.clientId} (${token.clientType})"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })

            val permList = token.permissions.split(",")
            val permText = "Permissions:\n" + permList.joinToString("\n") { "  \u2022 $it" }
            holder.layout.addView(TextView(this@DashboardActivity).apply {
                text = permText
                textSize = 13f
            })

            val bindingParts = mutableListOf<String>()
            token.boundPackageName?.let { bindingParts.add("App: $it") }
            token.boundCertHash?.let { bindingParts.add("Cert: ${it.take(12)}...") }
            token.boundOrigin?.let { bindingParts.add("Origin: $it") }
            if (bindingParts.isNotEmpty()) {
                holder.layout.addView(TextView(this@DashboardActivity).apply {
                    text = bindingParts.joinToString("\n")
                    textSize = 13f
                })
            }

            holder.layout.addView(TextView(this@DashboardActivity).apply {
                text = "Granted by: ${token.grantedBy} at ${dateFormat.format(Date(token.grantedAt))}"
                textSize = 13f
            })

            holder.layout.addView(TextView(this@DashboardActivity).apply {
                text = if (token.expiresAt != null) "Expires: ${dateFormat.format(Date(token.expiresAt))}" else "Expires: never"
                textSize = 13f
            })

            val tokenPreview = "${token.token.take(5)}...${token.token.takeLast(5)}"
            holder.layout.addView(TextView(this@DashboardActivity).apply {
                text = "Token: $tokenPreview"
                textSize = 12f
                setTextColor(Color.GRAY)
            })

            holder.layout.addView(TextView(this@DashboardActivity).apply {
                text = "Swipe to revoke"
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 4, 0, 0)
            })
        }
    }

    // ==================== Helpers ====================

    private fun tabContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 16, 32, 32)
    }

    private fun wrapInScrollView(content: View): ScrollView = ScrollView(this).apply {
        addView(content)
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 24, 0, 8)
    }

    private fun statusBadge(running: Boolean): TextView = TextView(this).apply {
        text = if (running) "Running" else "Stopped"
        textSize = 13f
        setTextColor(if (running) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        setPadding(16, 0, 16, 0)
    }

    private fun notRunningText(): TextView = TextView(this).apply {
        text = "Service not running"
        textSize = 14f
        setPadding(0, 16, 0, 0)
        setTextColor(Color.GRAY)
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            setMargins(0, 4, 0, 4)
        }
        setBackgroundColor(Color.LTGRAY)
    }

    // ==================== Service lifecycle ====================

    private fun autoStartService() {
        if (!HalService.isServiceRunning) {
            val intent = Intent(this, HalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            pollServiceReady()
        }
    }

    private fun pollServiceReady() {
        lifecycleScope.launch {
            repeat(10) {
                delay(500)
                if (HalService.isServiceRunning) {
                    refreshCurrentTab()
                    return@launch
                }
            }
        }
    }

    // ==================== Permission handling ====================

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
