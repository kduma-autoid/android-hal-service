package dev.duma.android.hal.service.service

import dev.duma.android.hal.service.R
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.service.plugin.PluginRegistry

/**
 * Detail screen for a registered interface: its methods/events (from the contract) plus the list of
 * ALL implementor plugins — including dynamically-unavailable and unsupported ones (greyed) — with
 * per-implementor reorder (↑/↓, top = default provider) and an enable/disable switch. Changes persist
 * via [PluginRegistry.setInterfaceOrder] / [PluginRegistry.setInterfaceEnabled].
 */
class InterfaceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INTERFACE_ID = "interface_id"
    }

    private var interfaceId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        interfaceId = intent.getStringExtra(EXTRA_INTERFACE_ID) ?: ""
        val reg = HalService.pluginRegistry
        val contract = if (interfaceId.isNotEmpty()) reg?.getInterfaceContract(interfaceId) else null

        if (reg == null || contract == null) {
            val toolbar = createToolbar(interfaceId.ifEmpty { "Interface" })
            val layout = paddedColumn()
            layout.addView(emptyText("Interface not found"))
            setContentWithToolbar(toolbar, ScrollView(this).apply { addView(layout) })
            return
        }

        val toolbar = createToolbar(contract.interfaceId)
        val layout = paddedColumn()

        layout.addView(TextView(this).apply {
            text = "Interface: ${contract.interfaceId}"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        layout.addView(infoRow("Version", "${contract.version}"))
        if (contract.features.isNotEmpty()) {
            layout.addView(infoRow("Features", contract.features.joinToString(", ") { it.key }))
        }

        layout.addView(sectionHeader("Methods (${contract.methods.size})"))
        if (contract.methods.isEmpty()) layout.addView(emptyText("No methods"))
        for (method in contract.methods) layout.addView(buildMethodBlock(method))

        if (contract.events.isNotEmpty()) {
            layout.addView(sectionHeader("Events (${contract.events.size})"))
            for (event in contract.events) layout.addView(buildEventBlock(event))
        }

        layout.addView(sectionHeader("Implementors"))
        layout.addView(TextView(this).apply {
            text = "Order sets the default provider (top). Toggle to enable/disable a provider."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 4)
        })
        val implContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(implContainer)
        rebuildImplementors(reg, implContainer)

        setContentWithToolbar(toolbar, ScrollView(this).apply { addView(layout) })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun rebuildImplementors(reg: PluginRegistry, container: LinearLayout) {
        container.removeAllViews()
        val impls = reg.getAllInterfaceImplementors(interfaceId)
        if (impls.isEmpty()) {
            container.addView(emptyText("No implementors"))
            return
        }
        impls.forEachIndexed { index, ref ->
            container.addView(buildImplementorRow(reg, container, impls, index, ref))
        }
    }

    private fun buildImplementorRow(
        reg: PluginRegistry,
        container: LinearLayout,
        impls: List<PluginRegistry.ProviderRef>,
        index: Int,
        ref: PluginRegistry.ProviderRef
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            if (!ref.available || !ref.supported) alpha = 0.5f
            addView(divider())
        }

        val upBtn = Button(this).apply {
            text = "↑"
            isEnabled = index > 0
            setOnClickListener { moveAndRebuild(reg, container, impls, index, -1) }
        }
        val downBtn = Button(this).apply {
            text = "↓"
            isEnabled = index < impls.size - 1
            setOnClickListener { moveAndRebuild(reg, container, impls, index, +1) }
        }

        val sourceText = when (ref.source) {
            PluginRegistry.PluginSource.EXTERNAL -> "External"
            PluginRegistry.PluginSource.BUILT_IN -> "Built-in"
            else -> "Unknown"
        }
        val status = when {
            !ref.supported -> "unsupported"
            !ref.available -> "unavailable"
            !ref.enabled -> "disabled"
            ref.isDefault -> "DEFAULT"
            else -> "available"
        }
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = ref.pluginId
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = "$sourceText · priority ${ref.priority} · $status"
                textSize = 12f
                setTextColor(if (ref.isDefault) Color.parseColor("#1565C0") else Color.GRAY)
            })
        }

        val toggle = Switch(this).apply {
            isChecked = ref.enabled
            setOnCheckedChangeListener { _, checked ->
                reg.setInterfaceEnabled(interfaceId, ref.pluginId, checked)
                rebuildImplementors(reg, container)
            }
        }

        row.addView(upBtn)
        row.addView(downBtn)
        row.addView(infoCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 12 })
        row.addView(toggle)
        return row
    }

    private fun moveAndRebuild(
        reg: PluginRegistry,
        container: LinearLayout,
        impls: List<PluginRegistry.ProviderRef>,
        index: Int,
        delta: Int
    ) {
        val ids = impls.map { it.pluginId }.toMutableList()
        val target = index + delta
        if (target < 0 || target >= ids.size) return
        val tmp = ids[index]
        ids[index] = ids[target]
        ids[target] = tmp
        reg.setInterfaceOrder(interfaceId, ids)
        rebuildImplementors(reg, container)
    }

    // ---- UI helpers (mirrors PluginDetailActivity) ------------------------------------------

    private fun paddedColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 16, 32, 32)
    }

    private fun createToolbar(title: String): Toolbar {
        return Toolbar(this).apply {
            val ta = context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorPrimary))
            setBackgroundColor(ta.getColor(0, Color.parseColor("#6200EE")))
            ta.recycle()
            setTitleTextColor(Color.WHITE)
            this.title = title
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(v.paddingLeft, insets.systemWindowInsetTop, v.paddingRight, v.paddingBottom)
                insets
            }
        }
    }

    private fun setContentWithToolbar(toolbar: Toolbar, content: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = true
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.navigationBarColor = Color.BLACK
        }
        window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.purple_700)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setTint(Color.WHITE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun buildMethodBlock(method: MethodDescriptor): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(divider())
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = method.name
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 8, 0, 0)
            })
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = method.description
                textSize = 13f
                setPadding(0, 0, 0, 2)
            })
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = "Permission: ${method.requiredPermission}"
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            method.exampleParameters.takeIf { it.isNotEmpty() }?.let { addView(codeBlock("Parameters", it)) }
            method.exampleOutput.takeIf { it.isNotEmpty() }?.let { addView(codeBlock("Output", it)) }
        }
    }

    private fun buildEventBlock(event: EventDescriptor): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(divider())
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = event.name
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 8, 0, 2)
            })
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = event.description
                textSize = 13f
                setPadding(0, 0, 0, 2)
            })
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = "Permission: ${event.requiredPermission}"
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            event.exampleEvent.takeIf { it.isNotEmpty() }?.let { addView(codeBlock("Example", it)) }
        }
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 24, 0, 8)
    }

    private fun infoRow(label: String, value: String): TextView = TextView(this).apply {
        text = "$label: $value"
        textSize = 13f
        setPadding(0, 2, 0, 2)
    }

    private fun emptyText(msg: String): TextView = TextView(this).apply {
        text = msg
        textSize = 13f
        setTextColor(Color.GRAY)
        setPadding(0, 4, 0, 4)
    }

    private fun codeBlock(label: String, content: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 4, 0, 4)
        addView(TextView(this@InterfaceDetailActivity).apply {
            text = "$label:"
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        addView(HorizontalScrollView(this@InterfaceDetailActivity).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            addView(TextView(this@InterfaceDetailActivity).apply {
                text = prettyPrintJson(content)
                textSize = 12f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextColor(Color.parseColor("#D4D4D4"))
                setPadding(16, 12, 16, 12)
            })
        })
    }

    private fun prettyPrintJson(raw: String): String {
        return try {
            when (val element = org.json.JSONTokener(raw).nextValue()) {
                is org.json.JSONObject -> element.toString(2)
                is org.json.JSONArray -> element.toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { setMargins(0, 4, 0, 4) }
        setBackgroundColor(Color.LTGRAY)
    }
}
