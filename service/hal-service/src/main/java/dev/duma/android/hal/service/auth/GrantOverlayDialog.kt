package dev.duma.android.hal.service.auth

import android.app.AlertDialog
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred

/**
 * Shows a permission grant dialog as a system overlay using WindowManager with
 * TYPE_APPLICATION_OVERLAY. Displays over any app without requiring an Activity.
 * Requires SYSTEM_ALERT_WINDOW permission (Settings.canDrawOverlays).
 */
object GrantOverlayDialog {

    /** Shows the dialog; returns a function dismissing it (no-op once it was answered). */
    fun show(
        context: Context,
        clientId: String,
        packageName: String?,
        origin: String?,
        requestedPermissions: List<String>?,
        deferred: CompletableDeferred<GrantDecision>,
        /** When the request times out (epoch ms); shown as a countdown on Deny. */
        deadlineMs: Long
    ): () -> Unit {
        val callerInfo = when {
            origin != null -> "Origin: $origin"
            packageName != null -> {
                val appLabel = try {
                    val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    context.packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    packageName
                }
                "$appLabel ($packageName)"
            }
            else -> "Unknown caller"
        }

        val permissionsInfo = if (requestedPermissions != null) {
            "\n\nRequested permissions:\n${requestedPermissions.joinToString("\n") { "  \u2022 $it" }}"
        } else {
            "\n\nRequested permissions: all (*)"
        }

        val message = "\"$clientId\" ($callerInfo) is requesting access to hardware services.$permissionsInfo"

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Permission Request")
            .setMessage(message)
            .setPositiveButton("Allow permanently") { _, _ ->
                deferred.complete(GrantDecision.AllowPermanent)
            }
            .setNeutralButton("Allow for today") { _, _ ->
                deferred.complete(GrantDecision.AllowDay)
            }
            .setNegativeButton("Deny") { _, _ ->
                deferred.complete(GrantDecision.Deny)
            }
            .setCancelable(false)
            .create()

        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        )

        dialog.show()

        val deny = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val countdown = DenyCountdown(deadlineMs, deny.text.toString()) { deny.text = it }
        dialog.setOnDismissListener { countdown.stop() }
        countdown.start()

        return { if (dialog.isShowing) dialog.dismiss() }
    }
}
