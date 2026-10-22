package dev.duma.android.hal.service.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.duma.android.hal.contract.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Debug-only probe. Uses the existing HAL instance rather than creating another SDK listener.
 * The overlay is drawn in this Activity's ordinary window, without overlay/system permissions.
 */
class NfcAntennaDiagnosticActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private lateinit var details: TextView
    private lateinit var overlay: AntennaOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (12 * resources.displayMetrics.density).toInt()
        details = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xDD182030.toInt())
            setPadding(padding, padding, padding, padding)
            addView(details)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@NfcAntennaDiagnosticActivity).apply {
                text = "Show / hide diagnostic details"
                setOnClickListener {
                    scroll.visibility = if (scroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            })
            addView(Button(this@NfcAntennaDiagnosticActivity).apply {
                text = "Open HAL dashboard"
                setOnClickListener {
                    startActivity(Intent(this@NfcAntennaDiagnosticActivity, DashboardActivity::class.java))
                }
            })
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(padding, insets.systemWindowInsetTop, padding, insets.systemWindowInsetBottom)
                insets
            }
        }
        overlay = AntennaOverlay(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(32, 40, 50))
            addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // A non-interactive, transparent drawing surface; controls remain usable below it.
            addView(
                this@NfcAntennaDiagnosticActivity.overlay,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
            )
        })
    }

    override fun onStart() {
        super.onStart()
        refreshJob = scope.launch {
            while (isActive) {
                val (snapshot, text) = withContext(Dispatchers.IO) { readDiagnostics() }
                overlay.snapshot = snapshot
                details.text = text
                delay(500)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        overlay.snapshot = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun readDiagnostics(): Pair<JSONObject?, String> {
        val header = buildString {
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}")
            appendLine("targetSdk=${applicationInfo.targetSdkVersion}; cyan=resolved, amber=estimated")
            appendLine("Full-screen pixels; the view origin is subtracted before drawing.")
            appendLine("Compare the outline with Sunmi's marker in each rotation.")
            for (key in listOf(
                "device_mgr_nfc_watermark_location",
                "device_mgr_nfc_cur_controller",
                "sub_display_device_info",
            )) {
                val value = try {
                    Settings.Global.getString(contentResolver, key) ?: "<missing>"
                } catch (e: SecurityException) {
                    "<read denied: ${e.message}>"
                } catch (e: Exception) {
                    "<read failed: ${e.message}>"
                }
                appendLine("$key=$value")
            }
        }
        val registry = HalService.pluginRegistry
            ?: return null to "$header\nHAL is not running. Open the dashboard and start HAL, then return."
        return try {
            when (val result = registry.executeOnPlugin("sunmi.nfc.getAntennaLocations", "{}")) {
                is CommandResult.Success -> {
                    val snapshot = JSONObject(result.body ?: "{}")
                    snapshot to "$header\n${snapshot.toString(2)}"
                }
                is CommandResult.Failure -> null to "$header\n${result.code}: ${result.message}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to "$header\nRead failed: ${e.message}"
        }
    }

    private class AntennaOverlay(context: Context) : View(context) {
        var snapshot: JSONObject? = null
            set(value) {
                field = value
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * resources.displayMetrics.density
        }
        private val origin = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val modules = snapshot?.optJSONArray("modules") ?: return
            getLocationOnScreen(origin)
            for (index in 0 until modules.length()) {
                val location = modules.optJSONObject(index)?.optJSONObject("location") ?: continue
                if (location.optInt("displayId", -1) != display?.displayId) continue
                val rect = location.optJSONObject("rect") ?: continue
                val x = rect.optInt("x") - origin[0]
                val y = rect.optInt("y") - origin[1]
                val estimated = location.optBoolean("estimated")
                paint.color = if (estimated) Color.rgb(255, 193, 7) else Color.CYAN
                paint.pathEffect = if (estimated) DashPathEffect(floatArrayOf(12f, 8f), 0f) else null
                canvas.drawRect(
                    x.toFloat(), y.toFloat(),
                    (x + rect.optInt("width")).toFloat(), (y + rect.optInt("height")).toFloat(), paint,
                )
            }
        }
    }
}
