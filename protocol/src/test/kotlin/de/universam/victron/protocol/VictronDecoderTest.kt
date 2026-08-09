package de.universam.victron.protocol

import de.universam.victron.protocol.records.ChargerState
import de.universam.victron.protocol.records.SolarChargerRecord
import de.universam.victron.protocol.records.UnknownRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class VictronDecoderTest {

    @Test
    fun `decodes a real advertisement end to end`() {
        val result = VictronDecoder.decode(TestVectors.SOLAR_CHARGER_ADVERTISEMENT, TestVectors.SOLAR_CHARGER_KEY)

        val decoded = assertInstanceOf(DecodeResult.Decoded::class.java, result)
        assertEquals("BlueSolar MPPT 75/15", decoded.header.modelName)
        val record = assertInstanceOf(SolarChargerRecord::class.java, decoded.record)
        assertEquals(ChargerState.ABSORPTION, record.chargerState)
        assertEquals(13.88, record.batteryVoltage!!, 1e-9)
        assertEquals(19, record.pvPowerW)
    }

    @Test
    fun `reports a missing key without losing the header`() {
        val result = VictronDecoder.decode(TestVectors.SOLAR_CHARGER_ADVERTISEMENT, key = null)

        val missing = assertInstanceOf(DecodeResult.MissingKey::class.java, result)
        assertEquals(0xA042, missing.header.modelId)
    }

    @Test
    fun `reports a key that belongs to another device`() {
        val otherKey = VictronCipher.parseKey("00112233445566778899aabbccddeeff")

        val result = VictronDecoder.decode(TestVectors.SOLAR_CHARGER_ADVERTISEMENT, otherKey)

        assertInstanceOf(DecodeResult.KeyMismatch::class.java, result)
    }

    @Test
    fun `keeps the decrypted payload of record types it cannot decode`() {
        // Same advertisement, but claiming to be a battery monitor (record type 0x02).
        val patched = TestVectors.SOLAR_CHARGER_ADVERTISEMENT.copyOf().also { it[4] = 0x02 }

        val result = VictronDecoder.decode(patched, TestVectors.SOLAR_CHARGER_KEY)

        val decoded = assertInstanceOf(DecodeResult.Decoded::class.java, result)
        val record = assertInstanceOf(UnknownRecord::class.java, decoded.record)
        assertEquals(RecordType.BATTERY_MONITOR, record.recordType)
        assertEquals(TestVectors.SOLAR_CHARGER_PLAINTEXT.toHex(), record.payloadHex)
    }

    @Test
    fun `rejects foreign manufacturer data`() {
        val result = VictronDecoder.decode("0201beefcafebabe0102".hexToBytes(), TestVectors.SOLAR_CHARGER_KEY)

        assertInstanceOf(DecodeResult.Unusable::class.java, result)
    }

    @Test
    fun `reports a truncated solar charger payload`() {
        val truncated = TestVectors.SOLAR_CHARGER_ADVERTISEMENT.copyOf(VictronBle.HEADER_SIZE + 4)

        val result = VictronDecoder.decode(truncated, TestVectors.SOLAR_CHARGER_KEY)

        assertInstanceOf(DecodeResult.PayloadTooShort::class.java, result)
    }
}
