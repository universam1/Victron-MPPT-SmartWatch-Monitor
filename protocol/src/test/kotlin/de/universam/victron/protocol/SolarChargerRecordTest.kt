package de.universam.victron.protocol

import de.universam.victron.protocol.records.ChargerError
import de.universam.victron.protocol.records.ChargerState
import de.universam.victron.protocol.records.SolarChargerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SolarChargerRecordTest {

    @Test
    fun `decodes a 12V MPPT in absorption`() {
        val record = SolarChargerRecord.decode(TestVectors.SOLAR_CHARGER_PLAINTEXT)

        assertEquals(ChargerState.ABSORPTION, record.chargerState)
        assertEquals(ChargerError.NO_ERROR, record.chargerError)
        assertEquals(13.88, record.batteryVoltage!!, 1e-9)
        assertEquals(1.4, record.batteryCurrent!!, 1e-9)
        assertEquals(30, record.yieldTodayWh)
        assertEquals(0.03, record.yieldTodayKWh!!, 1e-9)
        assertEquals(19, record.pvPowerW)
        assertEquals(0.0, record.loadCurrent!!, 1e-9)
        assertEquals(19.432, record.batteryPowerW!!, 1e-6)
    }

    @Test
    fun `decodes bulk charging`() {
        val record = SolarChargerRecord.decode(TestVectors.BULK_PLAINTEXT)

        assertEquals(ChargerState.BULK, record.chargerState)
        assertEquals(12.72, record.batteryVoltage!!, 1e-9)
        assertEquals(0.2, record.batteryCurrent!!, 1e-9)
        assertEquals(3, record.pvPowerW)
    }

    @Test
    fun `decodes a 24V MPPT without a load output`() {
        val record = SolarChargerRecord.decode(TestVectors.MPPT_24V_PLAINTEXT)

        assertEquals(ChargerState.BULK, record.chargerState)
        assertEquals(25.55, record.batteryVoltage!!, 1e-9)
        assertEquals(10.1, record.batteryCurrent!!, 1e-9)
        assertEquals(500, record.yieldTodayWh)
        assertEquals(265, record.pvPowerW)
        assertNull(record.loadCurrent, "0x1FF means the model has no load output")
    }

    @Test
    fun `maps not-available sentinels to null`() {
        // state 0xFF, error 0xFF, voltage 0x7FFF, current 0x7FFF, yield 0xFFFF, power 0xFFFF,
        // load 0x1FF
        val record = SolarChargerRecord.decode("ffffff7fff7fffffffffff01".hexToBytes())

        assertNull(record.chargerState)
        assertEquals(0xFF, record.chargerStateCode)
        assertNull(record.chargerError)
        assertNull(record.batteryVoltage)
        assertNull(record.batteryCurrent)
        assertNull(record.yieldTodayWh)
        assertNull(record.pvPowerW)
        assertNull(record.loadCurrent)
        assertNull(record.batteryPowerW)
    }

    @Test
    fun `keeps negative battery current signed`() {
        // -1.5 A -> 0xFFF1 little endian
        val record = SolarChargerRecord.decode("0400f004f1ff0300130000fe".hexToBytes())

        assertEquals(-1.5, record.batteryCurrent!!, 1e-9)
    }
}
