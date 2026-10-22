package dev.duma.android.hal.plugins.sunmi.scanner.ss2222.driver

internal object SsiCodec {
    const val SOURCE_HOST = 0x04
    const val SOURCE_SCANNER = 0x00
    const val STATUS_TEMPORARY = 0x00

    const val REQUEST_REVISION = 0xA3
    const val REPLY_REVISION = 0xA4
    const val PARAM_SEND = 0xC6
    const val PARAM_REQUEST = 0xC7
    const val ACK = 0xD0
    const val NAK = 0xD1
    const val BEEP = 0xE6
    const val SCAN_ENABLE = 0xE9
    const val SCAN_DISABLE = 0xEA

    const val PARAM_NO_BEEP = 0xFF
    private const val MAX_DATA_SIZE = 251

    data class Packet(
        val opcode: Int,
        val source: Int,
        val status: Int,
        val data: ByteArray,
        val raw: ByteArray,
    )

    fun encode(
        opcode: Int,
        data: ByteArray = ByteArray(0),
        status: Int = STATUS_TEMPORARY,
        source: Int = SOURCE_HOST,
    ): ByteArray {
        require(opcode in 0..0xFF) { "Opcode must fit in one byte" }
        require(status in 0..0xFF) { "Status must fit in one byte" }
        require(source in 0..0xFF) { "Source must fit in one byte" }
        require(data.size <= MAX_DATA_SIZE) { "SSI payload is too large: ${data.size}" }

        val length = 4 + data.size
        val packet = ByteArray(length + 2)
        packet[0] = length.toByte()
        packet[1] = opcode.toByte()
        packet[2] = source.toByte()
        packet[3] = status.toByte()
        data.copyInto(packet, destinationOffset = 4)
        val checksum = -(0 until length).sumOf { packet[it].toInt() and 0xFF } and 0xFFFF
        packet[length] = (checksum ushr 8).toByte()
        packet[length + 1] = checksum.toByte()
        return packet
    }

    fun decode(bytes: ByteArray): Packet {
        if (bytes.size < 6) throw FramingException("SSI packet is shorter than 6 bytes")
        val length = bytes[0].toInt() and 0xFF
        if (length < 4 || length + 2 != bytes.size) {
            throw FramingException("Invalid SSI length $length for ${bytes.size} bytes")
        }
        val sum = (0 until length).sumOf { bytes[it].toInt() and 0xFF }
        val checksum = ((bytes[length].toInt() and 0xFF) shl 8) or
            (bytes[length + 1].toInt() and 0xFF)
        if (((sum + checksum) and 0xFFFF) != 0) {
            throw FramingException("Invalid SSI checksum")
        }
        return Packet(
            opcode = bytes[1].toInt() and 0xFF,
            source = bytes[2].toInt() and 0xFF,
            status = bytes[3].toInt() and 0xFF,
            data = bytes.copyOfRange(4, length),
            raw = bytes.copyOf(),
        )
    }

    fun decodeOrNull(bytes: ByteArray): Packet? = try {
        decode(bytes)
    } catch (_: FramingException) {
        null
    }

    /** A malformed scanner-originated SSI reply must not be surfaced as barcode data. */
    fun looksLikeScannerPacket(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val opcode = bytes[1].toInt() and 0xFF
        val source = bytes[2].toInt() and 0xFF
        return source == SOURCE_SCANNER && opcode in setOf(REPLY_REVISION, PARAM_SEND, ACK, NAK)
    }

    fun parameterBytes(id: Int): ByteArray = when {
        id in 0x00..0xEF -> byteArrayOf(id.toByte())
        id in 0xF000..0xF9FF -> byteArrayOf((id ushr 8).toByte(), id.toByte())
        else -> throw IllegalArgumentException("Unsupported SSI parameter id: 0x${id.toString(16)}")
    }

    fun parameterReplyValue(packet: Packet, id: Int): ByteArray? {
        if (
            packet.opcode != PARAM_SEND || packet.source != SOURCE_SCANNER ||
            packet.status != STATUS_TEMPORARY
        ) return null
        val idBytes = parameterBytes(id)
        if (packet.data.size <= 1 + idBytes.size) return null
        if ((packet.data[0].toInt() and 0xFF) != PARAM_NO_BEEP) return null
        if (!packet.data.copyOfRange(1, 1 + idBytes.size).contentEquals(idBytes)) return null
        return packet.data.copyOfRange(1 + idBytes.size, packet.data.size)
    }

    fun parameterWriteData(values: Map<Int, Int>): ByteArray {
        require(values.isNotEmpty()) { "At least one parameter is required" }
        val data = ArrayList<Byte>()
        data += PARAM_NO_BEEP.toByte()
        for ((id, value) in values) {
            require(value in 0..0xFF) { "Parameter value must fit in one byte" }
            data += parameterBytes(id).toList()
            data += value.toByte()
        }
        require(data.size <= MAX_DATA_SIZE) { "Combined parameter payload is too large" }
        return data.toByteArray()
    }

    fun isAck(packet: Packet): Boolean =
        packet.opcode == ACK && packet.source == SOURCE_SCANNER &&
            packet.status == STATUS_TEMPORARY && packet.data.isEmpty()

    fun isNak(packet: Packet): Boolean =
        packet.opcode == NAK && packet.source == SOURCE_SCANNER &&
            packet.status == STATUS_TEMPORARY && packet.data.size == 1

    fun isRevisionReply(packet: Packet): Boolean =
        packet.opcode == REPLY_REVISION && packet.source == SOURCE_SCANNER &&
            packet.status == STATUS_TEMPORARY && packet.data.isNotEmpty()

    fun nakReason(packet: Packet): Int? =
        packet.takeIf(::isNak)?.data?.single()?.toInt()?.and(0xFF)
}

internal class FramingException(message: String) : Exception(message)
