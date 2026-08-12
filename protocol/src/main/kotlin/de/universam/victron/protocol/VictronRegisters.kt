package de.universam.victron.protocol

import java.util.UUID

/**
 * VE.Direct register definitions and the GATT framing used to read/write them over BLE.
 *
 * Victron devices expose a connected GATT service alongside their connectionless Instant Readout
 * advertisements. The service uses a proprietary framing that tunnels VE.Direct-style register
 * access over a single transport characteristic.
 *
 * **This is reverse-engineered and not officially documented.** It works with current SmartSolar
 * firmware (as of mid-2026) but may break without notice.
 */
public object VictronRegisters {

    // ---- GATT UUIDs -------------------------------------------------------------------------

    /**
     * Victron BLE GATT service and characteristics.
     *
     * From VictronConnect APK disassembly: the actual service is `68c10001-...`, NOT
     * `6597xxxx-...` (which is only documented for SmartShunts in community projects).
     */
    public val SERVICE_UUID: UUID =
        UUID.fromString("68c10001-b17f-4d3a-a290-34ad6499937c")

    /** Write characteristic — TX to device. */
    public val WRITE_UUID: UUID =
        UUID.fromString("68c10002-b17f-4d3a-a290-34ad6499937c")

    /** Notify characteristic — RX from device. */
    public val NOTIFY_UUID: UUID =
        UUID.fromString("68c10003-b17f-4d3a-a290-34ad6499937c")

    // ---- Register IDs -----------------------------------------------------------------------

    /** Load output control mode (read/write, un8). */
    public const val LOAD_OUTPUT_CONTROL: Int = 0xEDAB

    /** Load output state (read-only, un8: 0=off, 1=on). */
    public const val LOAD_OUTPUT_STATE: Int = 0xEDA8

    /** Load switch high-level voltage threshold (read/write, un16, 0.01 V scale). */
    public const val LOAD_SWITCH_HIGH: Int = 0xED9D

    /** Load switch low-level voltage threshold (read/write, un16, 0.01 V scale). */
    public const val LOAD_SWITCH_LOW: Int = 0xED9C

    // ---- Load output control values ---------------------------------------------------------

    /** Load output always off. */
    public const val LOAD_OFF: Int = 0

    /** Automatic (BatteryLife algorithm). */
    public const val LOAD_AUTO: Int = 1

    /** Load output always on. */
    public const val LOAD_ALWAYS_ON: Int = 5

    /** Load output always off (explicit). */
    public const val LOAD_ALWAYS_OFF: Int = 4

    // ---- VREG/CBOR wire framing -------------------------------------------------------------

    /**
     * Encodes a register write command in the VREG framing used over the BLE transport
     * characteristic.
     *
     * Wire format: `08 00 19 <register-LE-u16> <CBOR-encoded-value>`
     *
     * The value is CBOR-encoded as a byte string (major type 2) containing the raw bytes.
     * For a single u8 value this is `42 XX 00` (2-byte bstr, value, padding to u16 width).
     */
    public fun encodeWrite(register: Int, value: ByteArray): ByteArray {
        val regLo = register and 0xFF
        val regHi = (register shr 8) and 0xFF
        // CBOR major type 2 (byte string), length = value.size
        val cborHeader = if (value.size < 24) {
            byteArrayOf((0x40 or value.size).toByte())
        } else {
            // length 24..255: one-byte length follows
            byteArrayOf(0x58, value.size.toByte())
        }
        return byteArrayOf(0x08, 0x00, 0x19, regLo.toByte(), regHi.toByte()) +
            cborHeader + value
    }

    /** Convenience: encode a single-byte register write (most control registers are un8). */
    public fun encodeWriteU8(register: Int, value: Int): ByteArray =
        encodeWrite(register, byteArrayOf(value.toByte()))

    /** Convenience: encode a two-byte little-endian register write. */
    public fun encodeWriteU16(register: Int, value: Int): ByteArray =
        encodeWrite(register, byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))

    /**
     * Encodes a register read request.
     *
     * Wire format: `08 00 17 <register-LE-u16>`
     */
    public fun encodeRead(register: Int): ByteArray {
        val regLo = register and 0xFF
        val regHi = (register shr 8) and 0xFF
        return byteArrayOf(0x08, 0x00, 0x17, regLo.toByte(), regHi.toByte())
    }

    /**
     * Attempts to decode a register response frame.
     *
     * Expected format: `08 00 19 <register-LE-u16> <CBOR-bstr-payload>`
     *
     * @return the register ID and raw value bytes, or `null` if the frame is not a valid response.
     */
    public fun decodeResponse(frame: ByteArray): RegisterValue? {
        if (frame.size < 6) return null
        if (frame[0] != 0x08.toByte() || frame[1] != 0x00.toByte() || frame[2] != 0x19.toByte()) return null
        val register = (frame[3].toInt() and 0xFF) or ((frame[4].toInt() and 0xFF) shl 8)
        // CBOR byte string decode
        val cborStart = 5
        if (cborStart >= frame.size) return null
        val majorByte = frame[cborStart].toInt() and 0xFF
        if ((majorByte and 0xE0) != 0x40) return null // not a byte string
        val length: Int
        val dataStart: Int
        val additional = majorByte and 0x1F
        if (additional < 24) {
            length = additional
            dataStart = cborStart + 1
        } else if (additional == 24 && cborStart + 1 < frame.size) {
            length = frame[cborStart + 1].toInt() and 0xFF
            dataStart = cborStart + 2
        } else {
            return null
        }
        if (dataStart + length > frame.size) return null
        return RegisterValue(register, frame.copyOfRange(dataStart, dataStart + length))
    }

    /** A decoded register ID + raw value from a GATT response frame. */
    public data class RegisterValue(val register: Int, val value: ByteArray) {
        /** Reads value as unsigned 8-bit. */
        public fun asU8(): Int = if (value.isNotEmpty()) value[0].toInt() and 0xFF else 0

        /** Reads value as unsigned 16-bit little-endian. */
        public fun asU16(): Int = if (value.size >= 2) {
            (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        } else {
            asU8()
        }

        override fun equals(other: Any?): Boolean =
            other is RegisterValue && register == other.register && value.contentEquals(other.value)

        override fun hashCode(): Int = 31 * register + value.contentHashCode()
    }
}
