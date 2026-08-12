package de.universam.victron.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VictronRegistersTest {

    @Test
    fun `service UUID is 68c10001`() {
        assertEquals("68c10001-b17f-4d3a-a290-34ad6499937c", VictronRegisters.SERVICE_UUID.toString())
    }

    @Test
    fun `control UUID is 68c10002`() {
        assertEquals("68c10002-b17f-4d3a-a290-34ad6499937c", VictronRegisters.CONTROL_UUID.toString())
    }

    @Test
    fun `data UUID is 68c10003`() {
        assertEquals("68c10003-b17f-4d3a-a290-34ad6499937c", VictronRegisters.DATA_UUID.toString())
    }

    @Test
    fun `load output constants have correct values`() {
        assertEquals(0xEDAB, VictronRegisters.LOAD_OUTPUT_CONTROL)
        assertEquals(0xEDA8, VictronRegisters.LOAD_OUTPUT_STATE)
        assertEquals(1, VictronRegisters.LOAD_AUTO)
        assertEquals(4, VictronRegisters.LOAD_ALWAYS_ON)
    }

    @Test
    fun `product ID for SmartSolar 100-20-48V`() {
        assertEquals(0xA05F, VictronRegisters.PRODUCT_SMARTSOLAR_100_20_48V)
    }

    @Test
    fun `path constants match VictronConnect QML`() {
        assertEquals("/Settings/Load/OperationMode", VictronRegisters.PATH_LOAD_OPERATION_MODE)
        assertEquals("/Mode", VictronRegisters.PATH_MODE)
    }
}
