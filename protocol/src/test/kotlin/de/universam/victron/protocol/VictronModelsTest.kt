package de.universam.victron.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The max charge current the UI scales its gauges with is read out of the model name, so the
 * parser has to survive every naming variant in the table — and refuse everything that is not a
 * solar charger.
 */
class VictronModelsTest {

    @Test
    fun `the amps after the slash are the charge current rating`() {
        assertEquals(20, VictronModels.maxChargeCurrentA(0xA060)) // SmartSolar MPPT 100/20 48V
        assertEquals(15, VictronModels.maxChargeCurrentA(0xA042)) // BlueSolar MPPT 75/15
        assertEquals(100, VictronModels.maxChargeCurrentA(0xA051)) // SmartSolar MPPT 150/100
        assertEquals(15, VictronModels.maxChargeCurrentA(0xA075)) // SmartSolar MPPT 75/15 rev2
    }

    @Test
    fun `the VE Can and RS infixes do not hide the rating`() {
        assertEquals(70, VictronModels.maxChargeCurrentA(0xA109)) // SmartSolar MPPT VE.Can 250/70
        assertEquals(100, VictronModels.maxChargeCurrentA(0xA10F)) // BlueSolar MPPT VE.Can 150/100
        assertEquals(200, VictronModels.maxChargeCurrentA(0xA111)) // SmartSolar MPPT RS 450/200
    }

    @Test
    fun `every MPPT in the table has a rating`() {
        // A gauge that silently falls back to the observed peak for a known charger is the bug
        // this guards: if a new MPPT is added with a name this parser cannot read, fail here.
        assertEquals(
            emptyList<Int>(),
            (0..0xFFFF).filter {
                VictronModels.nameFor(it).contains("MPPT") && VictronModels.maxChargeCurrentA(it) == null
            },
        )
    }

    @Test
    fun `devices that are not chargers have no rating`() {
        assertNull(VictronModels.maxChargeCurrentA(0xA389)) // SmartShunt 500A/50mV
        assertNull(VictronModels.maxChargeCurrentA(0xA0E0)) // Smart Lithium 12.8V/90Ah
        assertNull(VictronModels.maxChargeCurrentA(0xA3B1)) // Smart BatteryProtect 12/24V-100A
        assertNull(VictronModels.maxChargeCurrentA(0xA3C8)) // Orion Smart 12/12-30A
        assertNull(VictronModels.maxChargeCurrentA(0xA441)) // Multi RS Solar 48V/6000VA/100A
        assertNull(VictronModels.maxChargeCurrentA(0xFFFF)) // not in the table at all
    }
}
