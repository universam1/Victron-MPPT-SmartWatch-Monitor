package de.universam.victron.protocol

/**
 * Reads the bit-packed fields of a decrypted Victron payload.
 *
 * Victron packs the fields **LSB first** and does not align them to byte boundaries (a solar
 * charger record for instance ends with a 9 bit load current). Reading a value therefore means
 * collecting [numBits] bits starting at the current position, least significant bit first.
 */
public class BitReader(private val data: ByteArray) {

    private var bitIndex: Int = 0

    /** Number of bits that have not been read yet. */
    public val remainingBits: Int
        get() = data.size * 8 - bitIndex

    public fun readBit(): Int {
        require(bitIndex < data.size * 8) { "Payload exhausted after $bitIndex bits" }
        val bit = (data[bitIndex ushr 3].toInt() shr (bitIndex and 7)) and 1
        bitIndex++
        return bit
    }

    public fun readUnsignedInt(numBits: Int): Int {
        require(numBits in 1..31) { "numBits must be 1..31, was $numBits" }
        var value = 0
        for (position in 0 until numBits) {
            value = value or (readBit() shl position)
        }
        return value
    }

    public fun readSignedInt(numBits: Int): Int = toSignedInt(readUnsignedInt(numBits), numBits)

    public companion object {
        public fun toSignedInt(value: Int, numBits: Int): Int =
            if (value and (1 shl (numBits - 1)) != 0) value - (1 shl numBits) else value
    }
}
