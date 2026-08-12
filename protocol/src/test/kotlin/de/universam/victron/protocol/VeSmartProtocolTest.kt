package de.universam.victron.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VeSmartProtocolTest {

    // ---- CBOR encoding tests ----------------------------------------------------------------

    @Test
    fun `cborEncodeUint encodes small values inline`() {
        // CBOR: 0-23 encoded as single byte (major type 0)
        assertEquals("00", VeSmartProtocol.cborEncodeUint(0).toHex())
        assertEquals("01", VeSmartProtocol.cborEncodeUint(1).toHex())
        assertEquals("04", VeSmartProtocol.cborEncodeUint(4).toHex())
        assertEquals("17", VeSmartProtocol.cborEncodeUint(23).toHex())
    }

    @Test
    fun `cborEncodeUint encodes 24-255 with one-byte length`() {
        // CBOR: 24 = 0x18 0x18
        assertEquals("1818", VeSmartProtocol.cborEncodeUint(24).toHex())
        assertEquals("18ff", VeSmartProtocol.cborEncodeUint(255).toHex())
    }

    @Test
    fun `cborEncodeUint encodes 256-65535 with two-byte length`() {
        // CBOR: 256 = 0x19 0x01 0x00
        assertEquals("190100", VeSmartProtocol.cborEncodeUint(256).toHex())
        assertEquals("19ffff", VeSmartProtocol.cborEncodeUint(65535).toHex())
    }

    @Test
    fun `cborEncodeArray encodes short arrays`() {
        // CBOR array(3) = 0x83
        assertEquals("83", VeSmartProtocol.cborEncodeArray(3).toHex())
        assertEquals("80", VeSmartProtocol.cborEncodeArray(0).toHex())
        assertEquals("97", VeSmartProtocol.cborEncodeArray(23).toHex())
    }

    @Test
    fun `cborEncodeString encodes UTF-8 text`() {
        // CBOR text string "abc" = 0x63 0x61 0x62 0x63
        assertEquals("63616263", VeSmartProtocol.cborEncodeString("abc").toHex())
        // Empty string
        assertEquals("60", VeSmartProtocol.cborEncodeString("").toHex())
    }

    // ---- Protocol message encoding ----------------------------------------------------------

    @Test
    fun `encodeKeepAlive produces single opcode byte`() {
        val msg = VeSmartProtocol.encodeKeepAlive()
        assertEquals(1, msg.size)
        assertEquals(VeSmartProtocol.OP_KEEPALIVE, msg[0].toInt() and 0xFF)
    }

    @Test
    fun `encodeReadyToReceive includes slot count`() {
        val msg = VeSmartProtocol.encodeReadyToReceive(16)
        assertEquals(2, msg.size)
        assertEquals(VeSmartProtocol.OP_READY_TO_RECEIVE, msg[0].toInt() and 0xFF)
        assertEquals(16, msg[1].toInt() and 0xFF)
    }

    @Test
    fun `encodeGetDevices is single opcode byte`() {
        val msg = VeSmartProtocol.encodeGetDevices()
        assertEquals(1, msg.size)
        assertEquals(VeSmartProtocol.OP_GET_DEVICES, msg[0].toInt() and 0xFF)
    }

    @Test
    fun `encodeSetPathValue produces opcode plus CBOR array of 3 elements`() {
        // SetPathValue(instanceId=0, pathIndex=5, value=4)
        // Expected: 09 83 00 05 04
        //           ^opcode ^array(3) ^0 ^5 ^4
        val msg = VeSmartProtocol.encodeSetPathValue(0, 5, 4)
        assertEquals("0983000504", msg.toHex())
    }

    @Test
    fun `encodeSetPathValue with larger pathIndex uses CBOR two-byte encoding`() {
        // SetPathValue(instanceId=1, pathIndex=30, value=1)
        // 30 >= 24, so CBOR: 0x18 0x1E
        val msg = VeSmartProtocol.encodeSetPathValue(1, 30, 1)
        assertEquals("098301181e01", msg.toHex())
    }

    @Test
    fun `encodeGetPathValue produces opcode plus CBOR array of 2`() {
        val msg = VeSmartProtocol.encodeGetPathValue(0, 7)
        assertEquals("07820007", msg.toHex())
    }

    // ---- Path list parsing ------------------------------------------------------------------

    @Test
    fun `parsePathList decodes CBOR map response`() {
        // Simulate: opcode(0x06) + map(2) { 3: "/Mode", 5: "/Settings/Load/OperationMode" }
        val pathMode = "/Mode"
        val pathLoad = "/Settings/Load/OperationMode"
        val cbor = byteArrayOf(VeSmartProtocol.OP_PATH_LIST.toByte()) +
            VeSmartProtocol.cborEncodeMap(2) +
            VeSmartProtocol.cborEncodeUint(3) + VeSmartProtocol.cborEncodeString(pathMode) +
            VeSmartProtocol.cborEncodeUint(5) + VeSmartProtocol.cborEncodeString(pathLoad)

        val paths = VeSmartProtocol.parsePathList(cbor)
        assertEquals(2, paths.size)
        assertEquals(3, paths[pathMode])
        assertEquals(5, paths[pathLoad])
    }

    @Test
    fun `parsePathList returns empty for empty input`() {
        assertTrue(VeSmartProtocol.parsePathList(byteArrayOf()).isEmpty())
    }

    // ---- Path value parsing -----------------------------------------------------------------

    @Test
    fun `parsePathValue extracts value from array response`() {
        // opcode(0x08) + array(3) [instanceId=0, pathIndex=5, value=4]
        val response = byteArrayOf(
            VeSmartProtocol.OP_PATH_VALUE.toByte(),
            0x83.toByte(), 0x00, 0x05, 0x04,
        )
        assertEquals(4, VeSmartProtocol.parsePathValue(response))
    }

    @Test
    fun `parsePathValue returns null for truncated data`() {
        assertNull(VeSmartProtocol.parsePathValue(byteArrayOf(0x08)))
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
