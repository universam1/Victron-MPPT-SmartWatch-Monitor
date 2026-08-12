package de.universam.victron.protocol

/**
 * VeSmartService protocol implementation for Victron BLE GATT communication.
 *
 * This is the path-based CBOR protocol used by VictronConnect (from APK disassembly).
 * The protocol operates on service `68c10001` with control char `68c10002` and data char `68c10003`.
 *
 * Architecture:
 * - Control char (`68c10002`): opcodes + short params (client↔device)
 * - Data char (`68c10003`): CBOR-chunked path values (device→client notifications, client→device writes)
 *
 * The protocol is path-based: the device reports available paths (like `/Settings/Load/OperationMode`)
 * with integer indices. All get/set operations use these indices, never raw vreg IDs.
 */
public object VeSmartProtocol {

    // ---- Opcodes (best-effort from symbol ordering in VictronConnect binary) -----------------
    // These go on the control characteristic (68c10002) as the first byte.
    // Exact values may need adjustment after hardware testing.

    public const val OP_ERROR: Int = 0x00
    public const val OP_KEEPALIVE: Int = 0x01
    public const val OP_READY_TO_RECEIVE: Int = 0x02
    public const val OP_GET_DEVICES: Int = 0x03
    public const val OP_DEVICE_LIST: Int = 0x04  // response to GET_DEVICES
    public const val OP_GET_PATH_LIST: Int = 0x05
    public const val OP_PATH_LIST: Int = 0x06    // response
    public const val OP_GET_PATH_VALUE: Int = 0x07
    public const val OP_PATH_VALUE: Int = 0x08   // response
    public const val OP_SET_PATH_VALUE: Int = 0x09
    public const val OP_PATH_RESPONSE: Int = 0x0A // ack for set
    public const val OP_SUBSCRIBE: Int = 0x0B
    public const val OP_UNSUBSCRIBE: Int = 0x0C

    // ---- CBOR Encoding (minimal RFC 7049 subset) --------------------------------------------

    /**
     * Encodes a VeSmartService control message (sent on 68c10002).
     * Format: `[opcode, ...params]` as raw bytes (not CBOR-wrapped for control messages).
     */
    public fun encodeControlMessage(opcode: Int, vararg params: Int): ByteArray {
        val result = ByteArray(1 + params.size)
        result[0] = opcode.toByte()
        params.forEachIndexed { i, v -> result[i + 1] = v.toByte() }
        return result
    }

    /** Encode a KeepAlive message for the control characteristic. */
    public fun encodeKeepAlive(): ByteArray = encodeControlMessage(OP_KEEPALIVE)

    /** Encode a ReadyToReceive(n) message — tells device we have n free chunk slots. */
    public fun encodeReadyToReceive(freeSlots: Int = 0x10): ByteArray =
        encodeControlMessage(OP_READY_TO_RECEIVE, freeSlots)

    /** Encode a GetDevices request. */
    public fun encodeGetDevices(): ByteArray = encodeControlMessage(OP_GET_DEVICES)

    /** Encode a GetPathList request for a device instance. */
    public fun encodeGetPathList(instanceId: Int): ByteArray =
        byteArrayOf(OP_GET_PATH_LIST.toByte()) + cborEncodeUint(instanceId)

    /**
     * Encode a SetPathValue message (sent on 68c10003 as CBOR).
     *
     * CBOR structure: array(3) [ instanceId, pathIndex, value ]
     * The outer opcode byte prefixes the CBOR payload.
     */
    public fun encodeSetPathValue(instanceId: Int, pathIndex: Int, value: Int): ByteArray {
        // Opcode prefix + CBOR array of 3 elements
        val cbor = cborEncodeArray(3) +
            cborEncodeUint(instanceId) +
            cborEncodeUint(pathIndex) +
            cborEncodeUint(value)
        return byteArrayOf(OP_SET_PATH_VALUE.toByte()) + cbor
    }

    /**
     * Encode a GetPathValue message.
     */
    public fun encodeGetPathValue(instanceId: Int, pathIndex: Int): ByteArray {
        val cbor = cborEncodeArray(2) +
            cborEncodeUint(instanceId) +
            cborEncodeUint(pathIndex)
        return byteArrayOf(OP_GET_PATH_VALUE.toByte()) + cbor
    }

    // ---- CBOR Decoding (minimal) ------------------------------------------------------------

    /**
     * Parse a path list response to extract path strings and their indices.
     * The response is CBOR-encoded on the data characteristic.
     *
     * Expected structure: opcode byte + CBOR map { pathIndex: pathString, ... }
     * or: opcode byte + CBOR array of [index, path] pairs.
     *
     * Returns a map of path string → integer index.
     */
    public fun parsePathList(response: ByteArray): Map<String, Int> {
        if (response.isEmpty()) return emptyMap()
        val paths = mutableMapOf<String, Int>()
        // Skip opcode byte, then parse CBOR
        val data = if (response[0].toInt() and 0xFF == OP_PATH_LIST) {
            response.copyOfRange(1, response.size)
        } else {
            response
        }
        // Best-effort CBOR map/array parsing
        val parsed = CborDecoder(data)
        parsed.tryParsePathMap(paths)
        return paths
    }

    /**
     * Parse a path value response.
     * Returns the integer value, or null if unparseable.
     */
    public fun parsePathValue(response: ByteArray): Int? {
        if (response.size < 2) return null
        // Skip opcode byte, decode CBOR
        val data = response.copyOfRange(1, response.size)
        val decoder = CborDecoder(data)
        // Expect: array(3) [instanceId, pathIndex, value] or just the value
        return decoder.tryParseIntValue()
    }

    // ---- Minimal CBOR encoder (RFC 7049) ----------------------------------------------------

    /** Encode an unsigned integer in CBOR (major type 0). */
    public fun cborEncodeUint(value: Int): ByteArray {
        require(value >= 0) { "Only unsigned values supported" }
        return when {
            value < 24 -> byteArrayOf(value.toByte())
            value < 256 -> byteArrayOf(0x18, value.toByte())
            value < 65536 -> byteArrayOf(0x19, (value shr 8).toByte(), (value and 0xFF).toByte())
            else -> byteArrayOf(
                0x1A,
                (value shr 24).toByte(),
                (value shr 16 and 0xFF).toByte(),
                (value shr 8 and 0xFF).toByte(),
                (value and 0xFF).toByte(),
            )
        }
    }

    /** Encode a CBOR array header (major type 4). */
    public fun cborEncodeArray(length: Int): ByteArray {
        val base = 0x80
        return when {
            length < 24 -> byteArrayOf((base or length).toByte())
            length < 256 -> byteArrayOf((base or 24).toByte(), length.toByte())
            else -> byteArrayOf(
                (base or 25).toByte(),
                (length shr 8).toByte(),
                (length and 0xFF).toByte(),
            )
        }
    }

    /** Encode a CBOR map header (major type 5). */
    public fun cborEncodeMap(length: Int): ByteArray {
        val base = 0xA0
        return when {
            length < 24 -> byteArrayOf((base or length).toByte())
            length < 256 -> byteArrayOf((base or 24).toByte(), length.toByte())
            else -> byteArrayOf(
                (base or 25).toByte(),
                (length shr 8).toByte(),
                (length and 0xFF).toByte(),
            )
        }
    }

    /** Encode a UTF-8 string in CBOR (major type 3). */
    public fun cborEncodeString(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        val header = when {
            utf8.size < 24 -> byteArrayOf((0x60 or utf8.size).toByte())
            utf8.size < 256 -> byteArrayOf(0x78, utf8.size.toByte())
            else -> byteArrayOf(0x79, (utf8.size shr 8).toByte(), (utf8.size and 0xFF).toByte())
        }
        return header + utf8
    }

    // ---- Minimal CBOR decoder ---------------------------------------------------------------

    /**
     * Minimal CBOR decoder for parsing VeSmartService responses.
     */
    public class CborDecoder(private val data: ByteArray) {
        private var pos = 0

        /** Try to parse a CBOR map of { int → string } into the paths map (index → path). */
        public fun tryParsePathMap(paths: MutableMap<String, Int>) {
            if (pos >= data.size) return
            val header = data[pos].toInt() and 0xFF
            val majorType = header shr 5

            when (majorType) {
                5 -> { // Map
                    val count = readLength(header)
                    repeat(count) {
                        val index = readUint() ?: return
                        val path = readString() ?: return
                        paths[path] = index
                    }
                }
                4 -> { // Array of pairs
                    val count = readLength(header)
                    repeat(count) {
                        // Each element might be array(2) [index, path]
                        val pairHeader = peekByte() ?: return
                        if ((pairHeader shr 5) == 4) {
                            pos++ // consume array header
                            val pairLen = readLengthFromAdditional(pairHeader and 0x1F)
                            if (pairLen >= 2) {
                                val index = readUint() ?: return
                                val path = readString() ?: return
                                paths[path] = index
                                // skip remaining elements
                                repeat(pairLen - 2) { skipValue() }
                            }
                        } else {
                            skipValue()
                        }
                    }
                }
                else -> return
            }
        }

        /** Try to extract an integer value from a response (skip array wrapper if present). */
        public fun tryParseIntValue(): Int? {
            if (pos >= data.size) return null
            val header = data[pos].toInt() and 0xFF
            val majorType = header shr 5
            if (majorType == 4) {
                // Array — skip to last element (typically array(3) [instance, pathIdx, value])
                val count = readLength(header)
                if (count >= 3) {
                    readUint() // instanceId
                    readUint() // pathIndex
                    return readUint() // value
                }
                return null
            }
            return readUint()
        }

        private fun readUint(): Int? {
            if (pos >= data.size) return null
            val header = data[pos].toInt() and 0xFF
            val majorType = header shr 5
            if (majorType != 0) return null // not unsigned int
            return readLength(header)
        }

        private fun readString(): String? {
            if (pos >= data.size) return null
            val header = data[pos].toInt() and 0xFF
            val majorType = header shr 5
            if (majorType != 3) return null // not text string
            val length = readLength(header)
            if (pos + length > data.size) return null
            val str = String(data, pos, length, Charsets.UTF_8)
            pos += length
            return str
        }

        private fun readLength(header: Int): Int {
            val additional = header and 0x1F
            pos++ // consume the header byte
            return readLengthFromAdditional(additional)
        }

        private fun readLengthFromAdditional(additional: Int): Int {
            return when {
                additional < 24 -> additional
                additional == 24 -> {
                    val v = data[pos].toInt() and 0xFF
                    pos++
                    v
                }
                additional == 25 -> {
                    val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2
                    v
                }
                additional == 26 -> {
                    val v = ((data[pos].toInt() and 0xFF) shl 24) or
                        ((data[pos + 1].toInt() and 0xFF) shl 16) or
                        ((data[pos + 2].toInt() and 0xFF) shl 8) or
                        (data[pos + 3].toInt() and 0xFF)
                    pos += 4
                    v
                }
                else -> 0
            }
        }

        private fun peekByte(): Int? {
            if (pos >= data.size) return null
            return data[pos].toInt() and 0xFF
        }

        private fun skipValue() {
            if (pos >= data.size) return
            val header = data[pos].toInt() and 0xFF
            val majorType = header shr 5
            val length = readLength(header)
            when (majorType) {
                0, 1 -> {} // integer, length already consumed
                2, 3 -> pos += length // byte/text string
                4 -> repeat(length) { skipValue() } // array
                5 -> repeat(length * 2) { skipValue() } // map
                else -> {}
            }
        }
    }
}
