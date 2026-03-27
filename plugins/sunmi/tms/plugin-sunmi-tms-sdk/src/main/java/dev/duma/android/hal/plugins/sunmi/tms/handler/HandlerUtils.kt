package dev.duma.android.hal.plugins.sunmi.tms.handler

import org.json.JSONObject

fun success(data: Any? = null): String {
    val obj = JSONObject().put("status", "ok")
    if (data != null) obj.put("result", data)
    return obj.toString()
}

fun error(code: String, message: String): String =
    JSONObject().put("error", code).put("message", message).toString()

fun unsupportedMethod(method: String): String =
    error("unsupported_method", "Method not supported: $method")
