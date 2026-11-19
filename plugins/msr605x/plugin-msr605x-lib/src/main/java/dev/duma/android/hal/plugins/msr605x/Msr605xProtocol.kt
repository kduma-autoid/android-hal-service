package dev.duma.android.hal.plugins.msr605x

/**
 * Wire protocol of the MSR605X magnetic stripe reader/writer — framing, command bytes, request
 * builders and response parsers. Pure Kotlin (no Android types) so it is unit-testable on the JVM.
 *
 * Ported from the `msr605x.py` driver by Bartłomiej "Magnetic-Fox" Węgrzyn (2026). Behaviour of the
 * parsers deliberately mirrors that driver (including its raw-mode quirks, see [parseRawRead]).
 */
object Msr605xProtocol {

    const val VENDOR_ID = 0x0801
    const val PRODUCT_ID = 0x0003

    /** Size of a HID report on the wire: one header byte + [PAYLOAD_SIZE] payload bytes. */
    const val PACKET_SIZE = 64
    const val PAYLOAD_SIZE = 63

    /**
     * Payload bytes per *outgoing* packet. The reference driver sends at most 62 data bytes per
     * report (header + 62, zero-padded to 64); the device itself replies with up to [PAYLOAD_SIZE].
     */
    const val TX_PAYLOAD_SIZE = 62

    private const val HEADER_FIRST = 0x80
    private const val HEADER_LAST = 0x40
    private const val HEADER_LENGTH_MASK = 0x3F

    const val ESC: Byte = 0x1B
    const val FS: Byte = 0x1C

    // --- Commands ---
    val CMD_RESET = esc('a')
    val CMD_READ = esc('r')
    val CMD_WRITE = esc('w')
    val CMD_COMM_TEST = esc('e')
    val CMD_ALL_LED_OFF = esc(0x81)
    val CMD_ALL_LED_ON = esc(0x82)
    val CMD_GREEN_LED_ON = esc(0x83)
    val CMD_YELLOW_LED_ON = esc(0x84)
    val CMD_RED_LED_ON = esc(0x85)
    val CMD_LEAD_ZERO_SET = esc('z')
    val CMD_LEAD_ZERO_CHECK = esc('l')
    val CMD_ERASE_CARD = esc('c')
    val CMD_BPI_SELECT = esc('b')
    val CMD_READ_RAW = esc('m')
    val CMD_WRITE_RAW = esc('n')
    val CMD_GET_MODEL = esc('t')
    val CMD_GET_FW_VERSION = esc('v')
    val CMD_BPC_SET = esc('o')
    val CMD_SET_HICO = esc('x')
    val CMD_SET_LOCO = esc('y')
    val CMD_GET_COERCIVITY = esc('d')

    /**
     * Hard reset, sent as a HID *feature* report: header `0xC2` (first|last, 2 bytes) + `ESC a`.
     * Works even when the device is stuck waiting for a swipe.
     */
    val FEATURE_HARD_RESET = byteArrayOf(0xC2.toByte(), ESC, 'a'.code.toByte())

    // --- Data block markers ---
    private val START_SEQUENCE = esc('s')
    private val TRACK_START = arrayOf(esc(0x01), esc(0x02), esc(0x03))
    private val END_SEQUENCE_W = byteArrayOf('?'.code.toByte(), FS)
    private val END_SEQUENCE = END_SEQUENCE_W + ESC
    private val NO_DATA = esc('+')
    private val BAD_DATA = esc('*')

    // --- Status bytes ---
    const val STATUS_OK = '0'
    const val STATUS_RW_ERROR = '1'
    const val STATUS_CMD_FORMAT_ERROR = '2'
    const val STATUS_CMD_INVALID = '4'
    const val STATUS_SWIPE_ERROR = '9'
    private const val STATUS_FAIL = 'A'

    // --- ISO 7811 track constraints (sentinels excluded — the device adds them) ---
    private const val ISO1_ALPHABET = " #$()-./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^"
    private const val ISO23_ALPHABET = "0123456789="
    private val ISO_ALPHABET = arrayOf(ISO1_ALPHABET, ISO23_ALPHABET, ISO23_ALPHABET)
    private val ISO_MAX_SIZE = intArrayOf(76, 37, 104)
    private val START_SENTINEL = charArrayOf('%', ';', ';')
    private const val END_SENTINEL = '?'

    private fun esc(c: Char) = byteArrayOf(ESC, c.code.toByte())
    private fun esc(b: Int) = byteArrayOf(ESC, b.toByte())

    // ------------------------------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------------------------------

    /**
     * Splits [data] into 64-byte HID packets of at most [TX_PAYLOAD_SIZE] data bytes. Byte 0 of each
     * packet is the header: bit 7 marks the first packet, bit 6 the last, bits 0-5 the payload
     * length; the rest is zero-padded.
     */
    fun framePackets(data: ByteArray): List<ByteArray> {
        val chunks = if (data.isEmpty()) listOf(ByteArray(0))
        else (data.indices step TX_PAYLOAD_SIZE).map { data.copyOfRange(it, minOf(it + TX_PAYLOAD_SIZE, data.size)) }
        return chunks.mapIndexed { i, chunk ->
            val header = (if (i == 0) HEADER_FIRST else 0) or
                (if (i == chunks.lastIndex) HEADER_LAST else 0) or
                chunk.size
            ByteArray(PACKET_SIZE).also {
                it[0] = header.toByte()
                chunk.copyInto(it, 1)
            }
        }
    }

    /** Wraps a feature-report payload (header + data) to the full packet size. */
    fun featurePacket(payload: ByteArray): ByteArray = payload.copyOf(PACKET_SIZE)

    /**
     * Reassembles a response from incoming HID packets, honouring the header length (so padding is
     * dropped) and the "last" bit (so the caller can stop reading as soon as a response is complete).
     */
    class PacketAssembler {
        private val buffer = java.io.ByteArrayOutputStream()

        /** True once a packet with the "last" bit has been received. */
        var complete = false
            private set

        val hasData: Boolean get() = buffer.size() > 0

        fun add(packet: ByteArray) {
            if (packet.isEmpty()) return
            val header = packet[0].toInt() and 0xFF
            val length = minOf(header and HEADER_LENGTH_MASK, packet.size - 1)
            buffer.write(packet, 1, length)
            if (header and HEADER_LAST != 0) complete = true
        }

        fun bytes(): ByteArray = buffer.toByteArray()
    }

    // ------------------------------------------------------------------------------------------
    // Request builders
    // ------------------------------------------------------------------------------------------

    /** `ESC w` + ISO data block. Tracks are given without sentinels; null/empty tracks are skipped. */
    fun buildIsoWrite(track1: String?, track2: String?, track3: String?): ByteArray =
        CMD_WRITE + isoDataBlock(track1, track2, track3)

    private fun isoDataBlock(track1: String?, track2: String?, track3: String?): ByteArray {
        var out = START_SEQUENCE
        listOf(track1, track2, track3).forEachIndexed { i, track ->
            out += TRACK_START[i]
            if (track != null) out += track.toByteArray(Charsets.US_ASCII)
        }
        return out + END_SEQUENCE_W
    }

    /**
     * `ESC n` + raw data block. Like the Python driver, every byte is bit-reversed (sent LSB first)
     * and prefixed per track with its length byte. Each track can hold at most 255 bytes.
     */
    fun buildRawWrite(track1: ByteArray?, track2: ByteArray?, track3: ByteArray?): ByteArray {
        var out = CMD_WRITE_RAW + START_SEQUENCE
        listOf(track1, track2, track3).forEachIndexed { i, track ->
            val lsb = (track ?: ByteArray(0)).map { reverseBits(it) }.toByteArray()
            require(lsb.size <= 0xFF) { "Track ${i + 1} raw data longer than 255 bytes" }
            out += TRACK_START[i] + lsb.size.toByte() + lsb
        }
        return out + END_SEQUENCE_W
    }

    /** `ESC c <mask>` — bit 0 track 1, bit 1 track 2, bit 2 track 3. */
    fun buildErase(track1: Boolean, track2: Boolean, track3: Boolean): ByteArray {
        var mask = 0
        if (track1) mask = mask or 0x01
        if (track2) mask = mask or 0x02
        if (track3) mask = mask or 0x04
        return CMD_ERASE_CARD + mask.toByte()
    }

    /** `ESC b <setting>` for one track (1-3) at 210 or 75 bits per inch. */
    fun buildBpi(track: Int, bpi: Int): ByteArray {
        require(bpi == 210 || bpi == 75) { "BPI must be 210 or 75" }
        val setting = when (track) {
            1 -> if (bpi == 210) 0xA1 else 0xA0
            2 -> if (bpi == 210) 0xD2 else 0x4B
            3 -> if (bpi == 210) 0xC1 else 0xC0
            else -> throw IllegalArgumentException("Track must be 1, 2 or 3")
        }
        return CMD_BPI_SELECT + setting.toByte()
    }

    /** `ESC o t1 t2 t3` — bits per character per track (5-8). */
    fun buildBpc(track1: Int, track2: Int, track3: Int): ByteArray {
        listOf(track1, track2, track3).forEach { require(it in 5..8) { "Bits per character must be 5-8" } }
        return CMD_BPC_SET + byteArrayOf(track1.toByte(), track2.toByte(), track3.toByte())
    }

    /** `ESC z a b` — leading zeros for tracks 1&3 and track 2 (0-255). */
    fun buildLeadingZeros(track13: Int, track2: Int): ByteArray {
        require(track13 in 0..255 && track2 in 0..255) { "Leading zeros must be 0-255" }
        return CMD_LEAD_ZERO_SET + byteArrayOf(track13.toByte(), track2.toByte())
    }

    fun reverseBits(b: Byte): Byte = (Integer.reverse(b.toInt() and 0xFF) ushr 24).toByte()

    /** Validates ISO track data (no sentinels) against the track's alphabet and max length. */
    fun isValidIso(track: Int, data: String): Boolean {
        val i = track - 1
        require(i in 0..2) { "Track must be 1, 2 or 3" }
        return data.length <= ISO_MAX_SIZE[i] && data.all { it in ISO_ALPHABET[i] }
    }

    // ------------------------------------------------------------------------------------------
    // Response parsers
    // ------------------------------------------------------------------------------------------

    /**
     * Result of a card read. A track is its decoded data (sentinels stripped), `""` when the track
     * is empty, or `null` when it could not be read. [status] is the device status character
     * (`'0'` = OK), or null if the response was not a data block at all.
     */
    data class ReadResult<T>(val status: Char?, val track1: T?, val track2: T?, val track3: T?) {
        val ok: Boolean get() = status == STATUS_OK
    }

    /** Parses the response to `ESC r` (ISO mode). */
    fun parseIsoRead(response: ByteArray): ReadResult<String> {
        val block = splitDataBlock(response) ?: return ReadResult(null, null, null, null)
        val tracks = block.tracks.mapIndexed { i, t -> decodeIsoTrack(t, START_SENTINEL[i]) }
        return ReadResult(block.status, tracks[0], tracks[1], tracks[2])
    }

    private fun decodeIsoTrack(track: ByteArray, startSentinel: Char): String? {
        if (track.contentEquals(NO_DATA) || track.isEmpty()) return ""
        if (track.contentEquals(BAD_DATA)) return null
        val text = String(track, Charsets.ISO_8859_1)
        return if (text.length >= 2 && text.first() == startSentinel && text.last() == END_SENTINEL) {
            text.substring(1, text.length - 1)
        } else {
            text
        }
    }

    /**
     * Parses the response to `ESC m` (raw mode). Mirrors the Python driver: the byte after each
     * `ESC 0n` marker is a length, and the driver takes `length - 1` data bytes after it. Read data
     * is returned as the device sends it (not bit-reversed).
     */
    fun parseRawRead(response: ByteArray): ReadResult<ByteArray> {
        val block = splitDataBlock(response) ?: return ReadResult(null, null, null, null)
        val tracks = block.tracks.map { t ->
            if (t.isEmpty()) return@map ByteArray(0)
            val size = ((t[0].toInt() and 0xFF) - 1).coerceIn(0, t.size - 1)
            t.copyOfRange(1, 1 + size)
        }
        return ReadResult(block.status, tracks[0], tracks[1], tracks[2])
    }

    private class DataBlock(val tracks: List<ByteArray>, val status: Char?)

    /**
     * Splits `ESC s ESC 01 .. ESC 02 .. ESC 03 .. ? FS ESC <status>` into its three tracks, locating
     * the markers by search like the Python driver does.
     */
    private fun splitDataBlock(r: ByteArray): DataBlock? {
        if (r.size < 2 || r[0] != START_SEQUENCE[0] || r[1] != START_SEQUENCE[1]) return null
        val p1 = indexOf(r, TRACK_START[0], 2).takeIf { it >= 0 } ?: return null
        val p2 = indexOf(r, TRACK_START[1], p1 + 2).takeIf { it >= 0 } ?: return null
        val p3 = indexOf(r, TRACK_START[2], p2 + 2).takeIf { it >= 0 } ?: return null
        val end = indexOf(r, END_SEQUENCE, p3 + 2).takeIf { it >= 0 } ?: return null
        val status = r.getOrNull(end + 3)?.toInt()?.toChar()
        return DataBlock(
            listOf(r.copyOfRange(p1 + 2, p2), r.copyOfRange(p2 + 2, p3), r.copyOfRange(p3 + 2, end)),
            status
        )
    }

    /** Status of a write/erase-style response `ESC <status>`, or null when malformed. */
    fun parseStatus(response: ByteArray): Char? =
        if (response.size >= 2 && response[0] == ESC) response[1].toInt().toChar() else null

    /** True when the response is `ESC 0`. */
    fun isOk(response: ByteArray): Boolean = parseStatus(response) == STATUS_OK

    /** `ESC <model> S` → model character, or null. */
    fun parseModel(response: ByteArray): String? =
        if (response.size >= 3 && response[0] == ESC && response[2] == 'S'.code.toByte()) {
            response[1].toInt().toChar().toString()
        } else null

    /** `ESC <8 chars>` → firmware version string, or null. */
    fun parseFirmware(response: ByteArray): String? =
        if (response.size >= 9 && response[0] == ESC) String(response, 1, 8, Charsets.US_ASCII) else null

    /** `ESC h` → "hi", `ESC l` → "lo", otherwise null. */
    fun parseCoercivity(response: ByteArray): String? = when {
        response.size < 2 || response[0] != ESC -> null
        response[1] == 'h'.code.toByte() -> "hi"
        response[1] == 'l'.code.toByte() -> "lo"
        else -> null
    }

    /** `ESC a b` → (tracks 1&3, track 2), or null. */
    fun parseLeadingZeros(response: ByteArray): Pair<Int, Int>? =
        if (response.size >= 3 && response[0] == ESC) {
            (response[1].toInt() and 0xFF) to (response[2].toInt() and 0xFF)
        } else null

    /** `ESC 0 t1 t2 t3` → applied bits per character, or null when the device refused. */
    fun parseBpc(response: ByteArray): Triple<Int, Int, Int>? =
        if (response.size >= 5 && isOk(response)) {
            Triple(response[2].toInt() and 0xFF, response[3].toInt() and 0xFF, response[4].toInt() and 0xFF)
        } else null

    /** `ESC y` is the comm-test success reply. */
    fun isCommTestOk(response: ByteArray): Boolean =
        response.size >= 2 && response[0] == ESC && response[1] == 'y'.code.toByte()

    /** Human-readable description of a device status character. */
    fun describeStatus(status: Char?): String = when (status) {
        STATUS_OK -> "ok"
        STATUS_RW_ERROR -> "read/write error"
        STATUS_CMD_FORMAT_ERROR -> "command format error"
        STATUS_CMD_INVALID -> "invalid command"
        STATUS_SWIPE_ERROR -> "invalid card swipe in write mode"
        STATUS_FAIL -> "command failed"
        null -> "no response"
        else -> "unknown status '$status'"
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        var i = from.coerceAtLeast(0)
        while (i <= haystack.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return -1
    }
}
