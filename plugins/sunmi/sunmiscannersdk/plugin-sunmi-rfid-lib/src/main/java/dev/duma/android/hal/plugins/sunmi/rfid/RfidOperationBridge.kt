package dev.duma.android.hal.plugins.sunmi.rfid

import com.sunmi.rfid.ReaderCall
import com.sunmi.rfid.constant.ParamCts
import com.sunmi.rfid.entity.DataParameter
import dev.duma.android.hal.contract.CommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the Sunmi RFID SDK's callback-based async model to coroutines.
 *
 * Encapsulates:
 * - Pending operation tracking (CompletableDeferred per command byte)
 * - ReaderCall implementation (onSuccess/onTag/onFailed)
 * - awaitResult helper for synchronous-style command execution
 */
internal class RfidOperationBridge(
    private val emitEvent: (String, String) -> Unit
) {
    private val pendingOps = ConcurrentHashMap<Byte, CompletableDeferred<String>>()

    val readerCall = object : ReaderCall() {

        override fun onSuccess(cmd: Byte, params: DataParameter?) {
            val payload = RfidPayloadSerializer.buildSuccessPayload(cmd, params)
            val deferred = pendingOps.remove(cmd)
            if (deferred != null) {
                deferred.complete(payload)
            } else {
                emitEvent(EVENT_OPERATION_SUCCESS, payload)
            }
        }

        override fun onTag(cmd: Byte, state: Byte, tag: DataParameter?) {
            val isNew = state == ParamCts.FOUND_TAG
            val payload = RfidPayloadSerializer.buildTagPayload(cmd, isNew, tag)
            emitEvent(EVENT_TAG_FOUND, payload)
        }

        override fun onFailed(cmd: Byte, errorCode: Byte, msg: String?) {
            val payload = JSONObject()
                .put("cmd", cmd.toInt() and 0xFF)
                .put("errorCode", errorCode.toInt() and 0xFF)
                .put("message", msg ?: "Unknown error")
                .toString()
            val deferred = pendingOps.remove(cmd)
            if (deferred != null) {
                deferred.complete(payload)
            } else {
                emitEvent(EVENT_OPERATION_ERROR, payload)
            }
        }
    }

    suspend fun awaitResult(cmd: Byte, timeout: Long = 5000L, block: () -> Unit): CommandResult {
        val deferred = CompletableDeferred<String>()
        pendingOps[cmd] = deferred
        return try {
            block()
            CommandResult.Success(withTimeout(timeout) { deferred.await() })
        } catch (e: TimeoutCancellationException) {
            CommandResult.timeout("Operation timed out after ${timeout}ms")
        } finally {
            pendingOps.remove(cmd)
        }
    }

    fun started(): CommandResult =
        CommandResult.Success(JSONObject().put("status", "started").toString())

    companion object {
        const val EVENT_TAG_FOUND = "sunmi.rfid.tagFound"
        const val EVENT_OPERATION_SUCCESS = "sunmi.rfid.operationSuccess"
        const val EVENT_OPERATION_ERROR = "sunmi.rfid.operationError"
    }
}
