package dev.duma.android.hal.plugins.sunmi.printerx.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.sunmi.printerx.PrinterSdk
import com.sunmi.printerx.api.PrintResult
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

fun success(data: Any? = null): String {
    val obj = JSONObject().put("status", "ok")
    if (data != null) obj.put("result", data)
    return obj.toString()
}

fun error(code: String, message: String): String =
    JSONObject().put("error", code).put("message", message).toString()

fun unsupportedMethod(method: String): String =
    error("unsupported_method", "Method not supported: $method")

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
): String = suspendCancellableCoroutine { cont ->
    val result = object : PrintResult() {
        override fun onResult(resultCode: Int, message: String?) {
            val response = JSONObject()
                .put("status", "ok")
                .put("resultCode", resultCode)
                .put("message", message ?: "")
            if (cont.isActive) cont.resume(response.toString())
        }
    }
    block(result)
}

/**
 * Gets printer by ID or default, returns error string if not found.
 */
fun requirePrinter(json: JSONObject): Pair<PrinterSdk.Printer?, String?> {
    val id = json.optString("printerId").takeIf { it.isNotBlank() }
    val printer = SharedPrinterManager.getPrinter(id)
    return if (printer == null) {
        null to error("printer_not_found", "No printer available${if (id != null) " with id: $id" else ""}")
    } else {
        printer to null
    }
}
