package de.universam.victron.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VictronRegistersTest {

    @Test
    fun `encodeWriteU8 produces correct VREG frame for load output ON`() {
        val frame = VictronRegisters.encodeWriteU8(VictronRegisters.LOAD_OUTPUT_CONTROL, VictronRegisters.LOAD_ALWAYS_ON)
        // 08 00 19 AB ED 41 05
        //                ^^ CBOR bstr len=1, value=0x05
        assertEquals("080019abed4105", frame.toHex())
    }

    @Test
    fun `encodeWriteU8 produces correct VREG frame for load output OFF`() {
        val frame = VictronRegisters.encodeWriteU8(VictronRegisters.LOAD_OUTPUT_CONTROL, VictronRegisters.LOAD_ALWAYS_OFF)
        assertEquals("080019abed4104", frame.toHex())
    }

    @Test
    fun `encodeWriteU16 uses little endian`() {
        // Write 1350 (0x0546) to load switch high (0xED9D)
        val frame = VictronRegisters.encodeWriteU16(VictronRegisters.LOAD_SWITCH_HIGH, 1350)
        // register 0xED9D LE = 9D ED, value 1350 = 0x0546 LE = 46 05
        assertEquals("0800199ded424605", frame.toHex())
    }

    @Test
    fun `encodeRead produces correct request frame`() {
        val frame = VictronRegisters.encodeRead(VictronRegisters.LOAD_OUTPUT_STATE)
        // 08 00 17 A8 ED
        assertEquals("080017a8ed", frame.toHex())
    }

    @Test
    fun `decodeResponse round-trips a write frame`() {
        val frame = VictronRegisters.encodeWriteU8(VictronRegisters.LOAD_OUTPUT_CONTROL, VictronRegisters.LOAD_ALWAYS_ON)
        val result = VictronRegisters.decodeResponse(frame)
        assertNotNull(result)
        assertEquals(VictronRegisters.LOAD_OUTPUT_CONTROL, result!!.register)
        assertEquals(VictronRegisters.LOAD_ALWAYS_ON, result.asU8())
    }

    @Test
    fun `decodeResponse handles u16 value`() {
        val frame = VictronRegisters.encodeWriteU16(VictronRegisters.LOAD_SWITCH_HIGH, 1350)
        val result = VictronRegisters.decodeResponse(frame)
        assertNotNull(result)
        assertEquals(VictronRegisters.LOAD_SWITCH_HIGH, result!!.register)
        assertEquals(1350, result.asU16())
    }

    @Test
    fun `decodeResponse returns null for truncated frame`() {
        assertNull(VictronRegisters.decodeResponse(byteArrayOf(0x08, 0x00)))
    }

    @Test
    fun `decodeResponse returns null for wrong header`() {
        assertNull(VictronRegisters.decodeResponse(byteArrayOf(0x09, 0x00, 0x19, 0xAB.toByte(), 0xED.toByte(), 0x41, 0x05)))
    }

    @Test
    fun `registerUuid builds expected UUID string`() {
        val uuid = VictronRegisters.registerUuid(0xEDAB)
        assertEquals("6597edab-4bda-4c1e-af4b-551c4cf74769", uuid.toString())
    }

    @Test
    fun `service UUID is register 0x0000`() {
        assertEquals("65970000-4bda-4c1e-af4b-551c4cf74769", VictronRegisters.SERVICE_UUID.toString())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
