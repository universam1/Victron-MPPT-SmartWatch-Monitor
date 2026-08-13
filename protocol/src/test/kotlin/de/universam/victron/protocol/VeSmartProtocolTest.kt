package de.universam.victron.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Byte-level fixtures come from the VictronConnect ARM64 library: each opcode is the literal
 * immediate the corresponding function loads before serialising it as CBOR. These tests pin
 * our encoder to what the shipping app emits rather than to our own assumptions.
 */
class VeSmartProtocolTest {

    // ---- session setup on the control characteristic -----------------------------------------

    @Test
    fun `chunk size negotiation matches the captured fa80ff`() {
        assertEquals("fa80ff", VeSmartProtocol.encodeChunkSize().toHex())
    }

    @Test
    fun `ready to receive matches the captured f980`() {
        assertEquals("f980", VeSmartProtocol.encodeReadyToReceive().toHex())
    }

    @Test
    fun `f901 is recognised as the session ready ack`() {
        assertTrue(VeSmartProtocol.isSessionReady("f901".hex()))
        assertFalse(VeSmartProtocol.isSessionReady("f980".hex()))
        assertFalse(VeSmartProtocol.isSessionReady("f70300".hex()))
    }

    @Test
    fun `error frames expose their code`() {
        // f7 03 00 was the "session not initialised" error hit during live debugging.
        assertEquals(3, VeSmartProtocol.errorCode("f70300".hex()))
        assertEquals(2, VeSmartProtocol.errorCode("f70200".hex()))
        assertNull(VeSmartProtocol.errorCode("f901".hex()))
    }

    // ---- requests ---------------------------------------------------------------------------

    @Test
    fun `GetDevices is a bare 01`() {
        assertEquals("01", VeSmartProtocol.encodeGetDevices().toHex())
    }

    @Test
    fun `GetPathList carries the instance`() {
        assertEquals("0a00", VeSmartProtocol.encodeGetPathList(0).toHex())
        assertEquals("0a03", VeSmartProtocol.encodeGetPathList(3).toHex())
    }

    @Test
    fun `register read is opcode 0b then instance then register id`() {
        assertEquals(
            "0b0319edab",
            VeSmartProtocol.encodeGetRegister(3, VictronRegisters.LOAD_OUTPUT_CONTROL).toHex(),
        )
        assertEquals("0b0019ec0f", VeSmartProtocol.encodeGetRegister(0, 0xEC0F).toHex())
    }

    @Test
    fun `register write is opcode 0c then instance, register id and value`() {
        // The decisive one: switching the load output to "always on" (4) on instance 3.
        assertEquals(
            "0c0319edab04",
            VeSmartProtocol.encodeSetRegister(
                instance = 3,
                register = VictronRegisters.LOAD_OUTPUT_CONTROL,
                value = VictronRegisters.LOAD_ALWAYS_ON,
            ).toHex(),
        )
    }

    @Test
    fun `register write can carry a little endian byte string value`() {
        // The QByteArray write path, as the keepalive uses: 42 = bstr(2), 10 27 = 10000 LE.
        assertEquals(
            "0c00190093421027",
            VeSmartProtocol.encodeSetRegister(
                instance = 0,
                register = VeSmartProtocol.KEEPALIVE_REGISTER,
                value = 10_000,
                asByteString = true,
                valueBytes = 2,
            ).toHex(),
        )
    }

    @Test
    fun `keepalive writes ten seconds to register 0x0093`() {
        assertEquals("0c00190093421027", VeSmartProtocol.encodeKeepAlive().toHex())
        assertEquals(10_000L, VeSmartProtocol.KEEPALIVE_INTERVAL_MS)
    }

    @Test
    fun `register id is always the two byte CBOR uint form`() {
        // 0x0140 would fit a shorter CBOR encoding, but the device expects 19 <hi> <lo>.
        assertEquals("190140", VeSmartProtocol.cborRegisterId(VictronRegisters.CAPABILITIES).toHex())
    }

    @Test
    fun `little endian conversion matches VE Direct register layout`() {
        assertEquals("1027", VeSmartProtocol.littleEndian(10_000, 2).toHex())
        assertEquals("04", VeSmartProtocol.littleEndian(4, 1).toHex())
    }

    // ---- replies ----------------------------------------------------------------------------

    @Test
    fun `device list decodes the captured instances`() {
        // 02 9f 00 00 01 00 03 01 ff — indefinite array of (instance, flags) pairs.
        val instances = VeSmartProtocol.parseDeviceList("029f000001000301ff".hex())
        assertEquals(listOf(0, 1, 3), instances)
    }

    @Test
    fun `device list of a frame that is not a device list is empty`() {
        assertTrue(VeSmartProtocol.parseDeviceList("0a00".hex()).isEmpty())
    }

    @Test
    fun `register value is found after its register id`() {
        // 0f = PathValue, instance 3, register 0xEDAB, one-byte value 4 (always on)
        assertEquals(4, VeSmartProtocol.parseRegisterValue("0f0319edab04".hex(), 0xEDAB))
    }

    @Test
    fun `register value decodes a little endian byte string payload`() {
        // 41 = CBOR bstr(1); VE.Direct stores register payloads little endian
        assertEquals(0x04, VeSmartProtocol.parseRegisterValue("0f0319edab4104".hex(), 0xEDAB))
        // 42 = bstr(2), 0x2710 little endian
        assertEquals(0x2710, VeSmartProtocol.parseRegisterValue("0f0319ed9d421027".hex(), 0xED9D))
    }

    @Test
    fun `register value is null when the register is absent`() {
        assertNull(VeSmartProtocol.parseRegisterValue("0f0319ec0f04".hex(), 0xEDAB))
    }

    @Test
    fun `value carrying replies are recognised`() {
        assertTrue(VeSmartProtocol.carriesValue("0f0319edab04".hex()))
        assertTrue(VeSmartProtocol.carriesValue("090319edab04".hex()))
        assertFalse(VeSmartProtocol.carriesValue("029f00ff".hex()))
    }

    @Test
    fun `register list picks out the advertised register ids`() {
        // 0d = PathList, instance 0, followed by CBOR uint16 register ids
        assertEquals(
            listOf(0xEDAB, 0xED9D),
            VeSmartProtocol.parseRegisterList("0d0019edab19ed9d".hex()),
        )
    }

    // ---- load output value helpers ----------------------------------------------------------

    @Test
    fun `load mode masks off the streetlight flag`() {
        assertEquals(VictronRegisters.LOAD_ALWAYS_ON, VictronRegisters.loadMode(0x84))
        assertEquals(VictronRegisters.LOAD_AUTO, VictronRegisters.loadMode(0x81))
        assertEquals(VictronRegisters.LOAD_ALWAYS_ON, VictronRegisters.loadMode(0x04))
    }

    @Test
    fun `writing a mode preserves the streetlight flag`() {
        // streetlight on, switching to always-on must keep bit 7
        assertEquals(0x84, VictronRegisters.loadValuePreservingFlags(VictronRegisters.LOAD_ALWAYS_ON, 0x81))
        // streetlight off stays off
        assertEquals(0x04, VictronRegisters.loadValuePreservingFlags(VictronRegisters.LOAD_ALWAYS_ON, 0x01))
    }

    @Test
    fun `the target MPPT is known to have a configurable load output`() {
        assertTrue(
            VictronRegisters.PRODUCT_SMARTSOLAR_100_20_48V in VictronRegisters.PRODUCTS_WITH_LOAD_OUTPUT,
        )
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
