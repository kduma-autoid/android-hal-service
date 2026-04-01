package dev.duma.android.hal.transport.intent

import android.app.Activity
import android.os.Bundle
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.transport.core.CallerContext
import kotlinx.coroutines.runBlocking

/**
 * Transparent gateway activity handling one-shot Intent commands.
 * Supports REQUEST_TOKEN and EXECUTE actions. Returns JSON result
 * in the "result" extra. Runs handler synchronously via runBlocking.
 */
class IntentGatewayActivity : Activity() {

    companion object {
        const val ACTION_REQUEST_TOKEN = "dev.duma.hal.REQUEST_TOKEN"
        const val ACTION_EXECUTE = "dev.duma.hal.EXECUTE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val handler = IntentTransport.handler
        if (handler == null || !IntentTransport.isTransportEnabled) {
            setResult(RESULT_CANCELED, android.content.Intent().apply {
                putExtra("result", """{"error":"service_unavailable","message":"Intent transport not available"}""")
            })
            finish()
            return
        }

        val callerContext = CallerContext(
            transport = "intent",
            callingUid = callingActivity?.let { 0 }
        )

        val commandResult = when (intent?.action) {
            ACTION_REQUEST_TOKEN -> {
                val serviceKey = intent.getStringExtra("serviceKey")
                val clientId = intent.getStringExtra("clientId") ?: "unknown"
                val request = buildString {
                    append("""{"clientId":"$clientId"""")
                    if (serviceKey != null) {
                        append(""","serviceKey":"$serviceKey"""")
                    }
                    append("}")
                }
                runBlocking { handler.requestToken(request, callerContext) }
            }
            ACTION_EXECUTE -> {
                val token = intent.getStringExtra("token") ?: ""
                val method = intent.getStringExtra("method") ?: ""
                val params = intent.getStringExtra("params") ?: "{}"
                runBlocking { handler.execute(token, method, params, callerContext) }
            }
            else -> CommandResult.badRequest("Unknown action")
        }

        val jsonResult = when (commandResult) {
            is CommandResult.Success -> commandResult.body ?: "{}"
            is CommandResult.Failure -> """{"error":"${commandResult.code}","message":"${commandResult.message}"}"""
        }

        setResult(RESULT_OK, android.content.Intent().apply {
            putExtra("result", jsonResult)
        })
        finish()
    }
}
