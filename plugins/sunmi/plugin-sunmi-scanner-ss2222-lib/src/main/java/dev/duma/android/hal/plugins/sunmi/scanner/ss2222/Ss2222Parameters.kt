package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** The same policy guards named configuration and every diagnostic write path. */
internal object Ss2222Parameters {
    val symbologies = linkedMapOf(
        "EAN13" to 0x03, "EAN8" to 0x04, "UPC_A" to 0x01, "UPC_E" to 0x02,
        "CODE128" to 0x08, "GS1_128" to 0x0E, "CODE39" to 0x00, "ITF" to 0x06,
        "CODABAR" to 0x07, "QR_CODE" to 0xF025, "DATA_MATRIX" to 0xF024,
        "PDF417" to 0x0F, "CODE11" to 0x0A, "GS1_DATABAR" to 0xF052,
    )
    val volumes = listOf("high", "medium", "low", "mute")
    const val GOOD_READ_BEEP = 0x38
    const val VOLUME = 0x8C
    val configurableIds: Set<Int> = symbologies.values.toSet() + GOOD_READ_BEEP + VOLUME

    fun checkKeys(params: JsonObject, allowed: Set<String>) {
        require(params.keys.all { it in allowed }) {
            "Unknown parameters: ${(params.keys - allowed).joinToString()}"
        }
    }

    fun integer(params: JsonObject, key: String): Int {
        val value = params[key] as? JsonPrimitive
        require(value != null && !value.isString && value.intOrNull != null) { "$key must be an integer" }
        return value.intOrNull!!
    }

    fun string(params: JsonObject, key: String): String {
        val value = params[key] as? JsonPrimitive
        require(value != null && value.isString) { "$key must be a string" }
        return value.content
    }

    private fun boolean(params: JsonObject, key: String): Boolean {
        val value = params[key] as? JsonPrimitive
        require(value != null && !value.isString && value.booleanOrNull != null) { "$key must be a boolean" }
        return value.booleanOrNull!!
    }

    fun config(params: JsonObject): Map<Int, Int> {
        checkKeys(params, setOf("symbologies", "goodReadBeep", "beepVolume"))
        val values = linkedMapOf<Int, Int>()
        if ("symbologies" in params) {
            val codes = params["symbologies"] as? JsonObject
                ?: throw IllegalArgumentException("symbologies must be an object")
            checkKeys(codes, symbologies.keys)
            codes.keys.forEach { key -> values[symbologies.getValue(key)] = if (boolean(codes, key)) 1 else 0 }
        }
        if ("goodReadBeep" in params) values[GOOD_READ_BEEP] = if (boolean(params, "goodReadBeep")) 1 else 0
        if ("beepVolume" in params) {
            val volume = volumes.indexOf(string(params, "beepVolume"))
            require(volume >= 0) { "beepVolume must be high, medium, low or mute" }
            values[VOLUME] = volume
        }
        return validateWrites(values)
    }

    fun parameters(params: JsonObject): Map<Int, Int> {
        checkKeys(params, setOf("parameters"))
        val entries = params["parameters"] as? JsonArray
            ?: throw IllegalArgumentException("parameters must be an array of {id, value}")
        require(entries.size <= configurableIds.size) { "Too many parameters" }
        val values = linkedMapOf<Int, Int>()
        for (entry in entries) {
            val obj = entry as? JsonObject ?: throw IllegalArgumentException("Each parameter must be an object")
            checkKeys(obj, setOf("id", "value"))
            val id = integer(obj, "id")
            require(!values.containsKey(id)) { "Duplicate parameter: $id" }
            values[id] = integer(obj, "value")
        }
        return validateWrites(values)
    }

    fun validateWrites(values: Map<Int, Int>): Map<Int, Int> {
        require(values.isNotEmpty()) { "At least one setting is required" }
        for ((id, value) in values) {
            require(id in configurableIds) { "Parameter $id is not writable" }
            require(value in if (id == VOLUME) 0..3 else 0..1) { "Invalid value for parameter $id" }
        }
        return values
    }

    fun readable(id: Int): Int {
        require(id in Ss2222ReadParameters.ids) { "Parameter $id was not identified in the SS2222 sweep" }
        return id
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

    fun hexBytes(value: String): ByteArray {
        require(value.length <= 1024) { "SSI payload is too long" }
        val hex = value.filterNot { it.isWhitespace() }
        require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" }) { "data must contain hexadecimal byte pairs" }
        require(hex.length <= 502) { "SSI payload cannot exceed 251 bytes" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Diagnostics accept SSI payloads, never HID reports, checksums or a caller-supplied status. */
    fun raw(params: JsonObject): RawOperation {
        checkKeys(params, setOf("opcode", "data"))
        val opcode = integer(params, "opcode")
        val data = if ("data" in params) hexBytes(string(params, "data")) else byteArrayOf()
        return when (opcode) {
            0xA3, 0xE9, 0xEA -> {
                require(data.isEmpty()) { "Opcode $opcode takes no data" }
                RawOperation(opcode)
            }
            0xE6 -> {
                require(data.size == 1 && data[0].toInt() in 0..1) { "BEEP supports only 00 (ok) or 01 (error)" }
                RawOperation(opcode, beepError = data[0].toInt() == 1)
            }
            0xC7 -> {
                val (id, end) = parameterId(data, 0)
                require(end == data.size) { "Read exactly one parameter at a time" }
                RawOperation(opcode, parameter = readable(id))
            }
            0xC6 -> {
                require(data.isNotEmpty() && (data[0].toInt() and 0xff) == 0xff) { "PARAM_SEND must start with FF" }
                val values = linkedMapOf<Int, Int>()
                var offset = 1
                while (offset < data.size) {
                    val (id, end) = parameterId(data, offset)
                    require(end < data.size) { "Missing parameter value" }
                    require(!values.containsKey(id)) { "Duplicate parameter: $id" }
                    values[id] = data[end].toInt() and 0xff
                    offset = end + 1
                }
                RawOperation(opcode, writes = validateWrites(values))
            }
            else -> throw IllegalArgumentException("SSI opcode $opcode is not permitted")
        }
    }

    private fun parameterId(data: ByteArray, offset: Int): Pair<Int, Int> {
        require(offset < data.size) { "Missing parameter number" }
        val first = data[offset].toInt() and 0xff
        if (first < 0xf0) return first to offset + 1
        require(offset + 1 < data.size) { "Incomplete extended parameter number" }
        return ((first shl 8) or (data[offset + 1].toInt() and 0xff)) to offset + 2
    }

    data class RawOperation(
        val opcode: Int,
        val parameter: Int? = null,
        val writes: Map<Int, Int> = emptyMap(),
        val beepError: Boolean = false,
    )
}
