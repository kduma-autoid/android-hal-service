package dev.duma.android.hal.service.auth

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.duma.android.hal.service.R
import kotlinx.coroutines.CompletableDeferred

/**
 * Transparent activity that shows a permission grant dialog to the user.
 * Used as notification fallback when SYSTEM_ALERT_WINDOW is not granted.
 * Result is delivered via [pendingResult] CompletableDeferred in companion object.
 */
class GrantPermissionActivity : AppCompatActivity() {

    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: "Unknown"
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val origin = intent.getStringExtra(EXTRA_ORIGIN)

        val callerInfo = when {
            origin != null -> "Origin: $origin"
            packageName != null -> {
                val appLabel = try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    packageName
                }
                "$appLabel ($packageName)"
            }
            else -> "Unknown caller"
        }

        val message = "\"$clientId\" ($callerInfo) is requesting access to hardware services."

        AlertDialog.Builder(this)
            .setTitle(R.string.grant_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.grant_dialog_allow_permanent) { _, _ ->
                completeWith(GrantDecision.AllowPermanent)
            }
            .setNeutralButton(R.string.grant_dialog_allow_once) { _, _ ->
                completeWith(GrantDecision.AllowDay)
            }
            .setNegativeButton(R.string.grant_dialog_deny) { _, _ ->
                completeWith(GrantDecision.Deny)
            }
            .setCancelable(false)
            .show()
    }

    private fun completeWith(decision: GrantDecision) {
        if (!decided) {
            decided = true
            pendingResult?.complete(decision)
            pendingResult = null
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!decided) {
            pendingResult?.complete(GrantDecision.Deny)
            pendingResult = null
        }
    }

    companion object {
        const val EXTRA_CLIENT_ID = "client_id"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ORIGIN = "origin"

        var pendingResult: CompletableDeferred<GrantDecision>? = null
    }
}
