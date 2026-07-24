package dev.duma.android.hal.plugins.sunmi.scanner.camera

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.sunmi.scanner.sdk.CameraScanner
import kotlinx.coroutines.CompletableDeferred

/**
 * Transparent proxy Activity that launches the Sunmi CameraScanner SDK.
 * Started by [SunmiCameraScannerPlugin] when trigger is called; the user only sees
 * the camera scanner UI from the SDK. Results are passed back via [pendingResult].
 */
class CameraScannerProxyActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 10001

        @Volatile
        var pendingResult: CompletableDeferred<kotlin.Pair<String, String>?>? = null

        @Volatile
        var currentConfig: Bundle? = null

        @Volatile
        var activeInstance: CameraScannerProxyActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this

        val config = currentConfig ?: Bundle()
        CameraScanner.startCameraScannerActivityForResult(this, REQUEST_CODE, config)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                val result = CameraScanner.getScannerResult(data)
                if (result != null) {
                    pendingResult?.complete(kotlin.Pair(result.first ?: "", result.second ?: ""))
                } else {
                    pendingResult?.complete(null)
                }
            } else {
                pendingResult?.complete(null)
            }
        } else {
            pendingResult?.complete(null)
        }

        activeInstance = null
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        pendingResult?.complete(null)
        activeInstance = null
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        super.onDestroy()
    }
}
