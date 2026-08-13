package de.universam.victron.protocol

/**
 * VeSmartService — the connected (GATT) protocol VictronConnect uses to read and write
 * device settings, as opposed to the connectionless Instant Readout advertisements.
 *
 * Reverse-engineered from the VictronConnect ARM64 library. See [docs/vesmart-ble-gatt.md].
 *
 * ## Channels
 *
 * Everything runs on service `306b0001`:
 * - **control** (`306b0002`) — session setup and flow control: `0xfa` chunk size,
 *   `0xf9` receive credit, `0xf7` error.
 * - **data / last chunk** (`306b0003`) — commands and replies. A payload that fits one
 *   chunk goes here whole.
 * - **continuation** (`306b0004`) — every chunk *except* the last of a multi-chunk payload.
 *   Chunks are plain slices with no per-chunk header; the negotiated size is 128 bytes, so
 *   the short commands here are always single-chunk.
 *
 * ## Addressing
 *
 * Settings are addressed by **16-bit VE.Direct register id**, not by name — e.g. `19 ed ab`
 * for [VictronRegisters.LOAD_OUTPUT_CONTROL]. The device's own log format string
 * (`RegId=%04X Flags=%02X Length=%d`) confirms register addressing; the `/Settings/...`
 * string paths in the app belong to its VenusOS/MQTT transport instead.
 *
 * A device may expose several *instances* (VE.Smart networking). The instance follows the
 * opcode, and the valid instances come from [parseDeviceList] — it is not always `0`.
 *
 * ## Framing: a flat CBOR sequence
 *
 * A request is a bare concatenation of CBOR items with **no array or map wrapper**:
 * `<opcode> <instance> <regId> [<value>] …`. Opcodes are all below 24, so each is a single
 * literal byte. VictronConnect builds this by writing successive `Cbor` values into one
 * `QDataStream` and handing the buffer to `VeSmartService::writeCbor`.
 *
 * Older community documentation describes opcodes `03`/`05`/`06` with an `81`/`82` CBOR
 * array wrapper. That is a **previous protocol generation**; the shipping app uses the
 * opcodes below and emits no wrapper. Opcodes 3–6 are unused by current firmware.
 */
public object VeSmartProtocol {

    // ---- control channel (306b0002) ---------------------------------------------------------

    /** Chunk-size negotiation, `fa <size> <flags>`. VictronConnect sends `fa 80 ff`. */
    public const val CTRL_CHUNK_SIZE: Int = 0xFA

    /** Receive credit, `f9 <credit>`. VictronConnect sends `f9 80`; the device acks `f9 01`. */
    public const val CTRL_READY_TO_RECEIVE: Int = 0xF9

    /** Error, `f7 <code> 00`. Code 3 = session not initialised, 2 = invalid command / not ready. */
    public const val CTRL_ERROR: Int = 0xF7

    /** Negotiated chunk size in bytes. The device starts at 20 and is raised to this. */
    public const val CHUNK_SIZE: Int = 0x80

    /** `fa 80 ff` — negotiate the chunk size. First frame of session setup. */
    public fun encodeChunkSize(size: Int = CHUNK_SIZE, flags: Int = 0xFF): ByteArray =
        byteArrayOf(CTRL_CHUNK_SIZE.toByte(), size.toByte(), flags.toByte())

    /** `f9 <credit>` — grant the device credit to send. Second frame of session setup. */
    public fun encodeReadyToReceive(credit: Int = 0x80): ByteArray =
        byteArrayOf(CTRL_READY_TO_RECEIVE.toByte(), credit.toByte())

    /** True when [frame] is the `f9 01` session-ready ack. */
    public fun isSessionReady(frame: ByteArray): Boolean =
        frame.size >= 2 && (frame[0].toInt() and 0xFF) == CTRL_READY_TO_RECEIVE && frame[1].toInt() == 0x01

    /** Error code of an `f7 <code> ..` frame, or null when [frame] is not an error. */
    public fun errorCode(frame: ByteArray): Int? =
        if (frame.size >= 2 && (frame[0].toInt() and 0xFF) == CTRL_ERROR) frame[1].toInt() and 0xFF else null

    // ---- data channel opcodes ---------------------------------------------------------------
    // Requests

    /** Enumerate instances. No payload. */
    public const val OP_GET_DEVICES: Int = 0x01

    /** List the registers an instance exposes. Payload: instance. */
    public const val OP_GET_PATH_LIST: Int = 0x0A

    /** Read registers. Payload: instance, then one or more register ids. */
    public const val OP_GET_VALUES: Int = 0x0B

    /** Write registers. Payload: instance, then (register id, value) pairs. */
    public const val OP_SET_VALUES: Int = 0x0C

    // Responses

    /** Reply to [OP_GET_DEVICES]. */
    public const val OP_DEVICE_LIST: Int = 0x02

    /** Generic ack / status. */
    public const val OP_RESPONSE: Int = 0x07

    /** A value. */
    public const val OP_VALUE: Int = 0x08

    /** Ack for a value write. */
    public const val OP_VALUE_RESPONSE: Int = 0x09

    /** Reply to [OP_GET_PATH_LIST]. */
    public const val OP_PATH_LIST: Int = 0x0D

    /** The device announcing a newly available register. */
    public const val OP_NEW_PATH: Int = 0x0E

    /** A register's value. */
    public const val OP_PATH_VALUE: Int = 0x0F

    /**
     * Register the session keepalive is written to. VictronConnect writes 10000 (ms) to it
     * every 10 s; letting it lapse ends the session.
     */
    public const val KEEPALIVE_REGISTER: Int = 0x0093

    /** How often the keepalive must be rewritten, matching VictronConnect's timer. */
    public const val KEEPALIVE_INTERVAL_MS: Long = 10_000L

    // ---- requests ---------------------------------------------------------------------------

    /** `01` — enumerate instances. */
    public fun encodeGetDevices(): ByteArray = byteArrayOf(OP_GET_DEVICES.toByte())

    /** `0a <instance>` — list the registers an instance exposes. */
    public fun encodeGetPathList(instance: Int): ByteArray =
        byteArrayOf(OP_GET_PATH_LIST.toByte()) + cborEncodeUint(instance)

    /** `0b <instance> <regId>` — read one register. */
    public fun encodeGetRegister(instance: Int, register: Int): ByteArray =
        byteArrayOf(OP_GET_VALUES.toByte()) + cborEncodeUint(instance) + cborRegisterId(register)

    /**
     * `0c <instance> <regId> <value>` — write one register.
     *
     * [asByteString] selects how the value is encoded. VictronConnect has two write paths:
     * `setPathValues` passes a QVariant, which serialises an integer as a CBOR unsigned int,
     * while `setValue` passes a QByteArray, which serialises as a CBOR byte string holding
     * the register's little-endian bytes (that is how the keepalive writes 10000 as
     * `42 10 27`). Settings edited from the UI take the QVariant path, so the unsigned-int
     * form is the default.
     */
    public fun encodeSetRegister(
        instance: Int,
        register: Int,
        value: Int,
        asByteString: Boolean = false,
        valueBytes: Int = 1,
    ): ByteArray {
        val encodedValue = if (asByteString) {
            cborEncodeBytes(littleEndian(value, valueBytes))
        } else {
            cborEncodeUint(value)
        }
        return byteArrayOf(OP_SET_VALUES.toByte()) + cborEncodeUint(instance) +
            cborRegisterId(register) + encodedValue
    }

    /** The session keepalive: write 10000 ms to [KEEPALIVE_REGISTER] as a little-endian u16. */
    public fun encodeKeepAlive(instance: Int = 0): ByteArray =
        encodeSetRegister(instance, KEEPALIVE_REGISTER, 10_000, asByteString = true, valueBytes = 2)

    // ---- CBOR helpers -----------------------------------------------------------------------

    /**
     * A register id as CBOR. Always the 2-byte `19 <hi> <lo>` unsigned form, which is what
     * VictronConnect emits even for ids that would fit a shorter encoding.
     */
    public fun cborRegisterId(register: Int): ByteArray =
        byteArrayOf(0x19, ((register shr 8) and 0xFF).toByte(), (register and 0xFF).toByte())

    /** Encode an unsigned integer, CBOR major type 0. */
    public fun cborEncodeUint(value: Int): ByteArray {
        require(value >= 0) { "only unsigned values supported" }
        return when {
            value < 24 -> byteArrayOf(value.toByte())
            value < 256 -> byteArrayOf(0x18, value.toByte())
            value < 65536 -> byteArrayOf(0x19, (value shr 8).toByte(), (value and 0xFF).toByte())
            else -> byteArrayOf(
                0x1A,
                (value shr 24).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte(),
            )
        }
    }

    /** CBOR byte string, major type 2. */
    public fun cborEncodeBytes(value: ByteArray): ByteArray {
        val header = when {
            value.size < 24 -> byteArrayOf((0x40 or value.size).toByte())
            value.size < 256 -> byteArrayOf(0x58, value.size.toByte())
            else -> byteArrayOf(0x59, (value.size shr 8).toByte(), (value.size and 0xFF).toByte())
        }
        return header + value
    }

    /** [value] as [count] little-endian bytes, the layout VE.Direct registers use. */
    public fun littleEndian(value: Int, count: Int): ByteArray =
        ByteArray(count) { ((value shr (8 * it)) and 0xFF).toByte() }

    // ---- replies ----------------------------------------------------------------------------

    /**
     * Instance ids from an [OP_DEVICE_LIST] reply.
     *
     * The payload is a sequence of small unsigned ints, seen as `<instance> <flags>` pairs and
     * optionally wrapped in a CBOR indefinite array (`9f … ff`).
     */
    public fun parseDeviceList(frame: ByteArray): List<Int> {
        if (frame.size < 2 || (frame[0].toInt() and 0xFF) != OP_DEVICE_LIST) return emptyList()
        var i = 1
        if ((frame[i].toInt() and 0xFF) == 0x9F) i++ // indefinite array start
        val ints = mutableListOf<Int>()
        while (i < frame.size) {
            val b = frame[i].toInt() and 0xFF
            if (b == 0xFF) break // break marker
            when {
                b < 24 -> { ints += b; i++ }
                b == 0x18 && i + 1 < frame.size -> { ints += frame[i + 1].toInt() and 0xFF; i += 2 }
                b == 0x19 && i + 2 < frame.size -> {
                    ints += ((frame[i + 1].toInt() and 0xFF) shl 8) or (frame[i + 2].toInt() and 0xFF)
                    i += 3
                }
                else -> i++ // skip the unexpected rather than give up on the frame
            }
        }
        // Even positions are instance ids, odd ones flags.
        return ints.filterIndexed { idx, _ -> idx % 2 == 0 }.distinct()
    }

    /** True when [frame] is a reply that carries register values. */
    public fun carriesValue(frame: ByteArray): Boolean =
        frame.isNotEmpty() && (frame[0].toInt() and 0xFF) in
            setOf(OP_VALUE, OP_VALUE_RESPONSE, OP_PATH_VALUE, OP_RESPONSE)

    /**
     * Value of [register] in a reply frame, or null when the frame does not carry it.
     *
     * Scans for the register id as a big-endian uint16 and decodes the CBOR item after it,
     * which tolerates the differing wrappers used for single versus batched replies.
     */
    public fun parseRegisterValue(frame: ByteArray, register: Int): Int? {
        val hi = ((register shr 8) and 0xFF).toByte()
        val lo = (register and 0xFF).toByte()
        for (i in 0 until frame.size - 2) {
            if (frame[i] == hi && frame[i + 1] == lo) {
                decodeUintAt(frame, i + 2)?.let { return it }
            }
        }
        return null
    }

    /** Decodes the CBOR unsigned int or byte string at [pos] to an integer. */
    private fun decodeUintAt(frame: ByteArray, pos: Int): Int? {
        if (pos >= frame.size) return null
        val b = frame[pos].toInt() and 0xFF
        return when {
            b < 24 -> b
            b == 0x18 && pos + 1 < frame.size -> frame[pos + 1].toInt() and 0xFF
            b == 0x19 && pos + 2 < frame.size ->
                ((frame[pos + 1].toInt() and 0xFF) shl 8) or (frame[pos + 2].toInt() and 0xFF)
            // byte string: the register payload, little endian as VE.Direct stores it
            b in 0x41..0x44 -> {
                val n = b - 0x40
                if (pos + n >= frame.size) return null
                var v = 0
                for (k in 0 until n) v = v or ((frame[pos + 1 + k].toInt() and 0xFF) shl (8 * k))
                v
            }
            else -> null
        }
    }

    /** Register ids advertised in an [OP_PATH_LIST] reply. */
    public fun parseRegisterList(frame: ByteArray): List<Int> {
        if (frame.isEmpty() || (frame[0].toInt() and 0xFF) != OP_PATH_LIST) return emptyList()
        val regs = mutableListOf<Int>()
        var i = 1
        while (i < frame.size - 2) {
            if ((frame[i].toInt() and 0xFF) == 0x19) {
                regs += ((frame[i + 1].toInt() and 0xFF) shl 8) or (frame[i + 2].toInt() and 0xFF)
                i += 3
            } else {
                i++
            }
        }
        return regs
    }
}
