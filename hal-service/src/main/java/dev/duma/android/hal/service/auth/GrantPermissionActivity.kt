package dev.duma.android.hal.service.auth

import android.app.AlertDialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CompletableDeferred

/**
 * Transparent activity that shows a permission grant dialog to the user.
 * Displayed when a client requests a token without a developer key.
 * Result is delivered via [pendingResult] CompletableDeferred in companion object.
 */
class GrantPermissionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: "Unknown"
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val origin = intent.getStringExtra(EXTRA_ORIGIN)

        val callerInfo = when {
            packageName != null -> "App: $packageName"
            origin != null -> "Origin: $origin"
            else -> "Unknown caller"
        }

        AlertDialog.Builder(this)
            .setTitle("Permission Request")
            .setMessage("\"$clientId\" ($callerInfo) is requesting access to hardware services.")
            .setPositiveButton("Allow permanently") { _, _ ->
                pendingResult?.complete(GrantDecision.AllowPermanent)
                finish()
            }
            .setNeutralButton("Allow for today") { _, _ ->
                pendingResult?.complete(GrantDecision.AllowDay)
                finish()
            }
            .setNegativeButton("Deny") { _, _ ->
                pendingResult?.complete(GrantDecision.Deny)
                finish()
            }
            .setOnCancelListener {
                pendingResult?.complete(GrantDecision.Deny)
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_CLIENT_ID = "client_id"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ORIGIN = "origin"

        var pendingResult: CompletableDeferred<GrantDecision>? = null
    }
}
