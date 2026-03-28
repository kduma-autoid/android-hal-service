package dev.duma.android.hal.service.service

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.duma.android.hal.service.plugin.PluginRegistry

class PluginDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val pluginReg = HalService.pluginRegistry

        if (pluginId == null || pluginReg == null) {
            supportActionBar?.title = "Plugin"
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 32)
            }
            layout.addView(TextView(this).apply {
                text = "Plugin not found"
                textSize = 14f
                setTextColor(Color.GRAY)
            })
            setContentView(ScrollView(this).apply { addView(layout) })
            return
        }

        val desc = pluginReg.getAllDescriptors().find { it.pluginId == pluginId }
        if (desc == null) {
            supportActionBar?.title = pluginId
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 32)
            }
            layout.addView(TextView(this).apply {
                text = "Plugin descriptor not available"
                textSize = 14f
                setTextColor(Color.GRAY)
            })
            setContentView(ScrollView(this).apply { addView(layout) })
            return
        }

        supportActionBar?.title = desc.name

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

        // Methods
        layout.addView(sectionHeader("Methods (${desc.methods.size})"))
        if (desc.methods.isEmpty()) {
            layout.addView(emptyText("No methods"))
        } else {
            for (method in desc.methods) {
                layout.addView(divider())
                layout.addView(TextView(this).apply {
                    text = method.name
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 8, 0, 0)
                })
                if (method.superRequired) {
                    layout.addView(LinearLayout(this).apply {
                        setPadding(0, 4, 0, 4)
                        addView(TextView(this@PluginDetailActivity).apply {
                            text = "SUPER REQUIRED"
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.parseColor("#E65100"))
                            setPadding(12, 4, 12, 4)
                        })
                    })
                }
                layout.addView(TextView(this).apply {
                    text = method.description
                    textSize = 13f
                    setPadding(0, 0, 0, 2)
                })
                layout.addView(TextView(this).apply {
                    text = "Permission: ${method.requiredPermission}"
                    textSize = 12f
                    setTextColor(Color.GRAY)
                })
                method.exampleParameters?.let { params ->
                    layout.addView(codeBlock("Parameters", params))
                }
                method.exampleOutput?.let { output ->
                    layout.addView(codeBlock("Output", output))
                }
            }
        }

        // Events
        layout.addView(sectionHeader("Events (${desc.events.size})"))
        if (desc.events.isEmpty()) {
            layout.addView(emptyText("No events"))
        } else {
            for (event in desc.events) {
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
                event.exampleEvent?.let { example ->
                    layout.addView(codeBlock("Example", example))
                }
            }
        }

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
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
