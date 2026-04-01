package dev.duma.android.hal.plugins.sunmi.rfid

/**
 * Converts hex string (e.g. "AABBCCDD") to ByteArray.
 */
internal fun hexStringToBytes(hex: String): ByteArray {
    val clean = hex.replace(" ", "").replace(":", "")
    if (clean.isEmpty()) return ByteArray(0)
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
