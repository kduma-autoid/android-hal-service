package dev.duma.android.hal.plugins.sunmi.printerx.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.sunmi.printerx.PrinterSdk
import com.sunmi.printerx.api.PrintResult
import dev.duma.android.hal.contract.CommandResult
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Decodes a base64-encoded image string to Bitmap.
 */
fun base64ToBitmap(base64: String): Bitmap {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("Failed to decode base64 bitmap")
}

/**
 * Wraps a PrintResult callback into a suspend function.
 * Waits for onResult and returns the result synchronously.
 */
suspend fun awaitPrintResult(
    block: (PrintResult) -> Unit
): CommandResult = suspendCancellableCoroutine { cont ->
    val result = object : PrintResult() {
        override fun onResult(resultCode: Int, message: String?) {
            val response = JSONObject()
                .put("resultCode", resultCode)
                .put("message", message ?: "")
            if (cont.isActive) cont.resume(CommandResult.Success(response.toString()))
        }
    }
    block(result)
}

/**
 * Gets printer by ID or default, returns error if not found.
 */
fun requirePrinter(json: JSONObject): Pair<PrinterSdk.Printer?, CommandResult.Failure?> {
    val id = json.optString("printerId").takeIf { it.isNotBlank() }
    val printer = SharedPrinterManager.getPrinter(id)
    return if (printer == null) {
        null to CommandResult.Failure(
            "printer_not_found",
            "No printer available${if (id != null) " with id: $id" else ""}",
            CommandResult.ErrorType.UNAVAILABLE
        )
    } else {
        printer to null
    }
}
