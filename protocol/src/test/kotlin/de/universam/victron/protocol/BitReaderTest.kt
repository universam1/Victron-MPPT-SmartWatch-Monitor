package de.universam.victron.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BitReaderTest {

    @Test
    fun `reads fields least significant bit first`() {
        // 0x34 0x12 read as one 16 bit field is 0x1234, i.e. little endian byte order.
        val reader = BitReader("3412".hexToBytes())
        assertEquals(0x1234, reader.readUnsignedInt(16))
    }

    @Test
    fun `reads unaligned fields across byte boundaries`() {
        // 0b1111_1110 0b0000_0001 -> 6 bits: 0b111110 = 62, then 9 bits: 0b0_0000_0111 = 7
        val reader = BitReader("FE01".hexToBytes())
        assertEquals(0b111110, reader.readUnsignedInt(6))
        assertEquals(0b000000111, reader.readUnsignedInt(9))
    }

    @Test
    fun `interprets signed fields as twos complement`() {
        val reader = BitReader("FFFF".hexToBytes())
        assertEquals(-1, reader.readSignedInt(16))
        assertEquals(-1, BitReader.toSignedInt(0x7FFFF, 19))
        assertEquals(0x7FFF, BitReader.toSignedInt(0x7FFF, 16))
    }

    @Test
    fun `tracks remaining bits and refuses to read past the end`() {
        val reader = BitReader("FF".hexToBytes())
        assertEquals(8, reader.remainingBits)
        reader.readUnsignedInt(8)
        assertEquals(0, reader.remainingBits)
        assertThrows<IllegalArgumentException> { reader.readBit() }
    }
}
