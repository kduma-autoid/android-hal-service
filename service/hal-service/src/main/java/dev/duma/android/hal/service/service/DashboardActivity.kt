package dev.duma.android.hal.service.service

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import dev.duma.android.hal.contract.allEvents
import dev.duma.android.hal.contract.allMethods
import dev.duma.android.hal.service.R
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
import android.widget.Toast
import dev.duma.android.hal.service.auth.DeviceKeyManager
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.Gravity
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import dev.duma.android.hal.service.plugin.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri

/**
 * Main dashboard activity with TabLayout for organized display of HAL Service status,
 * transports, plugins, broadcast config, and token management.
 */
class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var contentFrame: FrameLayout
    private var currentSection = 0

    private val sectionTitles = arrayOf("Dashboard", "Transports", "Broadcasts", "Plugins", "Tokens", "Security")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = true
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.navigationBarColor = Color.BLACK
        }
        window.statusBarColor = ContextCompat.getColor(this, R.color.purple_700)

        val toolbar = Toolbar(this).apply {
            val ta = context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorPrimary))
            setBackgroundColor(ta.getColor(0, Color.parseColor("#6200EE")))
            ta.recycle()
            setTitleTextColor(Color.WHITE)
            title = "Dashboard"
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(v.paddingLeft, insets.systemWindowInsetTop, v.paddingRight, v.paddingBottom)
                insets
            }
        }

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }

        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(contentFrame)
        }

        val navigationView = NavigationView(this).apply {
            layoutParams = DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.WRAP_CONTENT,
                DrawerLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )

            val versionText = try {
                val info = packageManager.getPackageInfo(packageName, 0)
                "v${info.versionName} (${info.versionCode})"
            } catch (_: Exception) { "" }

            addHeaderView(LinearLayout(this@DashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                val ta = context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorPrimary))
                setBackgroundColor(ta.getColor(0, Color.parseColor("#6200EE")))
                ta.recycle()
                setPadding(48, 48, 48, 32)
                setOnApplyWindowInsetsListener { v, insets ->
                    v.setPadding(v.paddingLeft, insets.systemWindowInsetTop + 48, v.paddingRight, v.paddingBottom)
                    insets
                }
                addView(TextView(this@DashboardActivity).apply {
                    text = "HAL Service"
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@DashboardActivity).apply {
                    text = versionText
                    setTextColor(Color.parseColor("#CCFFFFFF"))
                    textSize = 14f
                })
            })

            sectionTitles.forEachIndexed { index, title ->
                menu.add(0, index, index, title)
            }
            menu.setGroupCheckable(0, true, true)
            menu.findItem(0)?.isChecked = true

            // Demo link at the bottom
            val demoId = sectionTitles.size + 1
            menu.add(1, demoId, demoId, "Open Web Demo").apply {
                isCheckable = false
            }

            setNavigationItemSelectedListener { item ->
                if (item.itemId == demoId) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, "https://hal.duma.dev/".toUri()))
                    } catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@DashboardActivity, "No browser available", Toast.LENGTH_SHORT).show()
                    }
                    drawerLayout.closeDrawers()
                } else {
                    currentSection = item.itemId
                    toolbar.title = sectionTitles[currentSection]
                    refreshCurrentTab()
                    drawerLayout.closeDrawers()
                }
                true
            }
        }

        drawerLayout = DrawerLayout(this).apply {
            addView(mainContent)
            addView(navigationView)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.systemGestureExclusionRects = listOf(
                        android.graphics.Rect(0, 0, 80, v.height)
                    )
                }
            }
        }

        setSupportActionBar(toolbar)
        val toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, 0, 0)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        setContentView(drawerLayout)

        requestMissingPermissions()
        autoStartService()
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentTab()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (currentSection == 0) refreshCurrentTab()
    }

    private fun refreshCurrentTab() {
        contentFrame.removeAllViews()
        when (currentSection) {
            0 -> contentFrame.addView(buildDashboardTab())
            1 -> contentFrame.addView(buildTransportsTab())
            2 -> contentFrame.addView(buildBroadcastsTab())
            3 -> contentFrame.addView(buildPluginsTab())
            4 -> contentFrame.addView(buildTokensTab())
            5 -> contentFrame.addView(buildSecurityTab())
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

        if (commandTransports.isEmpty() && eventTransports.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "No transports registered"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
        }

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

        return wrapInScrollView(layout)
    }

    // ==================== Tab 3: Broadcasts ====================

    private fun buildBroadcastsTab(): View {
        val layout = tabContent()

        val pluginReg = HalService.pluginRegistry
        val config = HalService.broadcastConfig
        if (pluginReg == null || config == null) {
            layout.addView(notRunningText())
            return wrapInScrollView(layout)
        }

        val allEvents = pluginReg.getSupportedDescriptors()
            .flatMap { it.allEvents }
            .distinctBy { it.name }

        if (allEvents.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "No events available"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
        } else {
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

        return wrapInScrollView(layout)
    }

    // ==================== Tab 4: Plugins ====================

    private fun buildPluginsTab(): View {
        val layout = tabContent()

        val pluginReg = HalService.pluginRegistry
        if (pluginReg == null) {
            layout.addView(notRunningText())
            return wrapInScrollView(layout)
        }

        val descriptors = pluginReg.getAllDescriptors()
        val unsupportedIds = pluginReg.getUnsupportedPluginIds()
        val experimentalIds = pluginReg.getExperimentalPluginIds()

        val normal = descriptors.filter { it.pluginId !in unsupportedIds && it.pluginId !in experimentalIds }.sortedBy { it.name }
        val experimental = descriptors.filter { it.pluginId in experimentalIds && it.pluginId !in unsupportedIds }.sortedBy { it.name }
        val unsupported = descriptors.filter { it.pluginId in unsupportedIds }.sortedBy { it.name }
        val hidden = experimental + unsupported

        if (normal.isEmpty() && hidden.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "No plugins registered"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
        } else {
            for (desc in normal) {
                layout.addView(buildPluginBlock(desc, pluginReg, isUnsupported = false, isExperimental = false))
                layout.addView(divider())
            }

            if (hidden.isNotEmpty()) {
                val hiddenContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }

                for (desc in experimental) {
                    hiddenContainer.addView(buildPluginBlock(desc, pluginReg, isUnsupported = false, isExperimental = true))
                    hiddenContainer.addView(divider())
                }
                for (desc in unsupported) {
                    hiddenContainer.addView(buildPluginBlock(desc, pluginReg, isUnsupported = true, isExperimental = desc.pluginId in experimentalIds))
                    hiddenContainer.addView(divider())
                }

                val parts = mutableListOf<String>()
                if (experimental.isNotEmpty()) parts.add("${experimental.size} experimental")
                if (unsupported.isNotEmpty()) parts.add("${unsupported.size} unsupported")
                val collapsedLabel = parts.joinToString(" + ") + " plugin${if (hidden.size != 1) "s" else ""}"

                val toggleButton = Button(this).apply {
                    text = collapsedLabel
                    setOnClickListener {
                        if (hiddenContainer.visibility == View.GONE) {
                            hiddenContainer.visibility = View.VISIBLE
                            text = "Hide hidden plugins"
                        } else {
                            hiddenContainer.visibility = View.GONE
                            text = collapsedLabel
                        }
                    }
                }

                layout.addView(toggleButton)
                layout.addView(hiddenContainer)
            }
        }

        return wrapInScrollView(layout)
    }

    private fun buildPluginBlock(desc: dev.duma.android.hal.contract.PluginDescriptor, pluginReg: PluginRegistry, isUnsupported: Boolean, isExperimental: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            val isExperimentalEnabled = isExperimental && HalService.experimentalConfig?.isPluginEnabled(desc.pluginId) == true
            if (isUnsupported || (isExperimental && !isExperimentalEnabled)) alpha = 0.5f
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
            val methodCount = desc.allMethods.size
            val eventCount = desc.allEvents.size
            if (methodCount > 0) infoParts.add("$methodCount methods")
            if (eventCount > 0) infoParts.add("$eventCount events")
            if (isUnsupported) infoParts.add("UNSUPPORTED")
            if (isExperimental) infoParts.add("EXPERIMENTAL")

            if (infoParts.isNotEmpty()) {
                addView(TextView(this@DashboardActivity).apply {
                    text = infoParts.joinToString(", ")
                    textSize = 13f
                    when {
                        isUnsupported -> {
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor("#C62828"))
                        }
                        isExperimental -> {
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor("#E65100"))
                        }
                        else -> setTextColor(Color.DKGRAY)
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

        val emptyView = TextView(this).apply {
            text = "No active tokens"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(32, 32, 32, 32)
            visibility = View.GONE
        }

        val container = FrameLayout(this).apply {
            addView(recyclerView)
            addView(emptyView)
        }

        val swipeRefresh = SwipeRefreshLayout(this).apply {
            addView(container)
        }

        val adapter = TokenAdapter(emptyView, recyclerView)
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

    private inner class TokenAdapter(
        private val emptyView: View,
        private val recyclerView: RecyclerView
    ) : RecyclerView.Adapter<TokenAdapter.VH>() {
        var tokens: List<dev.duma.android.hal.service.auth.TokenEntity> = emptyList()
            set(value) {
                field = value
                emptyView.visibility = if (value.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            }
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
                val grantedByLabel = token.grantedBy.let {
                    val parts = it.split(":", limit = 2)
                    val source = parts[0].replace("_", " ")
                    if (parts.size > 1) "$source (${parts[1]})" else source
                }
                text = "Granted by: $grantedByLabel at ${dateFormat.format(Date(token.grantedAt))}"
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

    // ==================== Tab 6: Security ====================

    private fun buildSecurityTab(): View {
        val layout = tabContent()

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

        // Super permissions
        layout.addView(sectionHeader("Super Permissions"))
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
            text = "When disabled, super permissions can only be granted via service key JWT."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 0)
        })

        // Device Key
        layout.addView(sectionHeader("Device Key"))
        val deviceKeyManager = DeviceKeyManager(getSharedPreferences("hal_device_key", MODE_PRIVATE))

        val keyText = TextView(this).apply {
            text = deviceKeyManager.getSecretBase64()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 4)
            visibility = if (deviceKeyManager.isEnabled()) View.VISIBLE else View.GONE
        }

        val copyButton = Button(this).apply {
            text = "Copy key to clipboard"
            visibility = if (deviceKeyManager.isEnabled()) View.VISIBLE else View.GONE
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("HAL Device Key", deviceKeyManager.getSecretBase64()))
                Toast.makeText(this@DashboardActivity, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        @Suppress("UseSwitchCompatOrMaterialCode")
        layout.addView(Switch(this).apply {
            text = "Enable device key authentication"
            textSize = 14f
            isChecked = deviceKeyManager.isEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                deviceKeyManager.setEnabled(isChecked)
                val vis = if (isChecked) View.VISIBLE else View.GONE
                keyText.visibility = vis
                copyButton.visibility = vis
            }
        })
        layout.addView(keyText)
        layout.addView(copyButton)
        layout.addView(TextView(this).apply {
            text = "When enabled, clients can use JWT tokens signed with this device key (HS256). The key is unique to this device."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 0)
        })

        return wrapInScrollView(layout)
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

    private fun getColorFromAttr(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.BLACK)
        ta.recycle()
        return color
    }

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
