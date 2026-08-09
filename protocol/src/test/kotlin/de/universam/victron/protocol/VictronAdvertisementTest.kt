package de.universam.victron.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class VictronAdvertisementTest {

    @Test
    fun `parses the plaintext header of a product advertisement`() {
        val result = VictronAdvertisement.parse(TestVectors.SOLAR_CHARGER_ADVERTISEMENT)

        val header = assertInstanceOf(ParseResult.Success::class.java, result).header
        assertEquals(0x02, header.recordLength)
        assertEquals(0xA042, header.modelId)
        assertEquals("BlueSolar MPPT 75/15", header.modelName)
        assertEquals(RecordType.SOLAR_CHARGER, header.recordType)
        assertEquals(0x01, header.recordTypeCode)
        assertEquals(0x0762, header.nonce)
        assertEquals(0xAD, header.keyCheckByte)
        assertEquals("ceb37b605d7e0ee21b24df5c", header.ciphertext.toHex())
    }

    @Test
    fun `ignores manufacturer data that is not a product advertisement`() {
        val result = VictronAdvertisement.parse("0102030405060708090a".hexToBytes())

        val notProduct = assertInstanceOf(ParseResult.NotProductAdvertisement::class.java, result)
        assertEquals(0x01, notProduct.prefix)
    }

    @Test
    fun `reports manufacturer data without a payload as too short`() {
        val result = VictronAdvertisement.parse("100242a0016207ad".hexToBytes())

        assertEquals(8, assertInstanceOf(ParseResult.TooShort::class.java, result).length)
    }

    @Test
    fun `maps unknown model ids to a readable placeholder`() {
        assertEquals("Victron 0xFFEE", VictronModels.nameFor(0xFFEE))
        assertEquals("SmartSolar MPPT 100/20", VictronModels.nameFor(0xA05F))
    }

    @Test
    fun `maps record type codes including the undecoded ones`() {
        assertEquals(RecordType.BATTERY_MONITOR, RecordType.fromCode(0x02))
        assertEquals(RecordType.LYNX_SMART_BMS, RecordType.fromCode(0x0A))
        assertEquals(RecordType.UNKNOWN, RecordType.fromCode(0x7E))
    }
}
