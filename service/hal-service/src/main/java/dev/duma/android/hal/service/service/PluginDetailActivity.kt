package dev.duma.android.hal.service.service

import dev.duma.android.hal.service.R
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import dev.duma.android.hal.contract.allEvents
import dev.duma.android.hal.contract.allMethods
import dev.duma.android.hal.service.plugin.PluginRegistry

class PluginDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val pluginReg = HalService.pluginRegistry

        if (pluginId == null || pluginReg == null) {
            val toolbar = createToolbar("Plugin")
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 32)
            }
            layout.addView(TextView(this).apply {
                text = "Plugin not found"
                textSize = 14f
                setTextColor(Color.GRAY)
            })
            setContentWithToolbar(toolbar, ScrollView(this).apply { addView(layout) })
            return
        }

        val desc = pluginReg.getAllDescriptors().find { it.pluginId == pluginId }
        if (desc == null) {
            val toolbar = createToolbar(pluginId)
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 32)
            }
            layout.addView(TextView(this).apply {
                text = "Plugin descriptor not available"
                textSize = 14f
                setTextColor(Color.GRAY)
            })
            setContentWithToolbar(toolbar, ScrollView(this).apply { addView(layout) })
            return
        }

        val toolbar = createToolbar(desc.name)

        val isUnsupported = pluginId in pluginReg.getUnsupportedPluginIds()
        val info = pluginReg.getPluginInfo(pluginId)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 32)
        }

        // Header
        layout.addView(TextView(this).apply {
            text = desc.name
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        layout.addView(TextView(this).apply {
            text = desc.pluginId
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 8)
        })

        // Info rows
        layout.addView(infoRow("Version", "${desc.version}"))

        val sourceText = when (info?.source) {
            PluginRegistry.PluginSource.EXTERNAL -> "External (${info.packageName})"
            PluginRegistry.PluginSource.BUILT_IN -> "Built-in"
            else -> "Unknown"
        }
        layout.addView(infoRow("Source", sourceText))

        layout.addView(infoRow("Capabilities", desc.capabilities.joinToString(", ")))

        if (isUnsupported) {
            layout.addView(TextView(this).apply {
                text = "UNSUPPORTED ON THIS DEVICE"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#C62828"))
                setPadding(0, 8, 0, 8)
            })
        }

        val isExperimental = desc.experimental || desc.allMethods.any { it.experimental }
        if (isExperimental) {
            layout.addView(TextView(this).apply {
                text = "EXPERIMENTAL PLUGIN"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#E65100"))
                setPadding(0, 8, 0, 8)
            })

            val expConfig = HalService.experimentalConfig
            if (expConfig != null) {
                layout.addView(CheckBox(this).apply {
                    text = "Enable experimental methods for this plugin"
                    textSize = 14f
                    isChecked = expConfig.isPluginEnabled(pluginId)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) expConfig.enablePlugin(pluginId)
                        else expConfig.disablePlugin(pluginId)
                    }
                })
            }
        }

        // Groups — each group contains methods and/or events
        val totalMethods = desc.allMethods.size
        val totalEvents = desc.allEvents.size
        layout.addView(sectionHeader("API ($totalMethods methods, $totalEvents events)"))

        if (desc.groups.isEmpty() || (totalMethods == 0 && totalEvents == 0)) {
            layout.addView(emptyText("No methods or events"))
        }

        for (group in desc.groups) {
            if (group.methods.isEmpty() && group.events.isEmpty()) continue

            // Group header (if named)
            if (group.name != null) {
                layout.addView(TextView(this).apply {
                    text = group.name
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 20, 0, 4)
                    setTextColor(Color.parseColor("#1565C0"))
                })
            }

            // Methods within this group — split into regular and experimental
            val regularMethods = if (desc.experimental) emptyList() else group.methods.filter { !it.experimental }
            val experimentalMethods = if (desc.experimental) group.methods else group.methods.filter { it.experimental }

            for (method in regularMethods) {
                layout.addView(buildMethodBlock(method, isExperimental = false))
            }

            if (experimentalMethods.isNotEmpty()) {
                val expContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }
                for (method in experimentalMethods) {
                    expContainer.addView(buildMethodBlock(method, isExperimental = true))
                }

                val expLabel = "${experimentalMethods.size} experimental method${if (experimentalMethods.size != 1) "s" else ""}"
                val toggleBtn = Button(this).apply {
                    text = expLabel
                    setOnClickListener {
                        if (expContainer.visibility == View.GONE) {
                            expContainer.visibility = View.VISIBLE
                            text = "Hide experimental methods"
                        } else {
                            expContainer.visibility = View.GONE
                            text = expLabel
                        }
                    }
                }
                layout.addView(toggleBtn)
                layout.addView(expContainer)
            }

            // Events within this group
            for (event in group.events) {
                layout.addView(divider())
                layout.addView(TextView(this).apply {
                    text = event.name
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 8, 0, 2)
                })
                layout.addView(TextView(this).apply {
                    text = event.description
                    textSize = 13f
                    setPadding(0, 0, 0, 2)
                })
                layout.addView(TextView(this).apply {
                    text = "Permission: ${event.requiredPermission}"
                    textSize = 12f
                    setTextColor(Color.GRAY)
                })
                event.exampleEvent.takeIf { it.isNotEmpty() }?.let { example ->
                    layout.addView(codeBlock("Example", example))
                }
            }
        }

        setContentWithToolbar(toolbar, ScrollView(this).apply { addView(layout) })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun buildMethodBlock(method: dev.duma.android.hal.contract.MethodDescriptor, isExperimental: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(divider())
            addView(TextView(this@PluginDetailActivity).apply {
                text = method.name
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 8, 0, 0)
            })

            val hasBadges = method.superRequired || isExperimental || method.experimental
            if (hasBadges) {
                addView(LinearLayout(this@PluginDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 4)

                    if (method.superRequired) {
                        addView(TextView(this@PluginDetailActivity).apply {
                            text = "SUPER REQUIRED"
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.parseColor("#E65100"))
                            setPadding(12, 4, 12, 4)
                        })
                    }
                    if (isExperimental || method.experimental) {
                        addView(TextView(this@PluginDetailActivity).apply {
                            text = "EXPERIMENTAL"
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.parseColor("#F57F17"))
                            setPadding(12, 4, 12, 4)
                            if (method.superRequired) {
                                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 8
                            }
                        })
                    }
                })
            }

            addView(TextView(this@PluginDetailActivity).apply {
                text = method.description
                textSize = 13f
                setPadding(0, 0, 0, 2)
            })
            addView(TextView(this@PluginDetailActivity).apply {
                text = "Permission: ${method.requiredPermission}"
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            method.exampleParameters.takeIf { it.isNotEmpty() }?.let { params ->
                addView(codeBlock("Parameters", params))
            }
            method.exampleOutput.takeIf { it.isNotEmpty() }?.let { output ->
                addView(codeBlock("Output", output))
            }
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
        addView(TextView(this@PluginDetailActivity).apply {
            text = "$label:"
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        addView(HorizontalScrollView(this@PluginDetailActivity).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            addView(TextView(this@PluginDetailActivity).apply {
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
            val element = org.json.JSONTokener(raw).nextValue()
            when (element) {
                is org.json.JSONObject -> element.toString(2)
                is org.json.JSONArray -> element.toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            setMargins(0, 4, 0, 4)
        }
        setBackgroundColor(Color.LTGRAY)
    }
}
