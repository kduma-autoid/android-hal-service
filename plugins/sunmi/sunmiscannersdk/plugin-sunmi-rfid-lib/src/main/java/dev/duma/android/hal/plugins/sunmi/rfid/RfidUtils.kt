package dev.duma.android.hal.plugins.sunmi.rfid

/**
 * Converts hex string (e.g. "AABBCCDD") to ByteArray.
 */
internal fun hexStringToBytes(hex: String): ByteArray {
    val clean = hex.replace(" ", "").replace(":", "")
    if (clean.isEmpty()) return ByteArray(0)
    require(clean.length % 2 == 0) { "Invalid hex string (odd length): '$hex'" }
    require(clean.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
        "Invalid hex string (non-hex characters): '$hex'"
    }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
