package dev.duma.android.hal.plugins.sunmi.scanner.common

import org.json.JSONObject

/**
 * Shared JSON response helpers for scanner plugins.
 */
object ScannerResponseHelper {

    fun success(): String =
        JSONObject().put("status", "ok").toString()

    fun success(key: String, value: Any?): String =
        JSONObject().put("status", "ok").put(key, value).toString()

    fun success(json: JSONObject): String {
        json.put("status", "ok")
        return json.toString()
    }

    fun started(): String =
        JSONObject().put("status", "scanning").toString()

    fun error(code: String, message: String): String =
        JSONObject()
            .put("error", code)
            .put("message", message)
            .toString()
}
