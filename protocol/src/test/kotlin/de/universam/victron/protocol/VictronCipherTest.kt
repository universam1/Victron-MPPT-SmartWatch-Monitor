package de.universam.victron.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VictronCipherTest {

    @Test
    fun `decrypts a real solar charger advertisement`() {
        val header = (VictronAdvertisement.parse(TestVectors.SOLAR_CHARGER_ADVERTISEMENT) as ParseResult.Success).header
        val plaintext = VictronCipher.decrypt(TestVectors.SOLAR_CHARGER_KEY, header.nonce, header.ciphertext)

        assertEquals(TestVectors.SOLAR_CHARGER_PLAINTEXT.toHex(), plaintext.toHex())
    }

    @Test
    fun `keystream is generated with a little endian counter block`() {
        // AES-ECB of the nonce written as a little endian 128 bit integer, XORed with the
        // ciphertext. Verified against Victron's documented AES-CTR usage and the reference
        // implementations; a big endian counter (what AES/CTR/NoPadding of the JCE would use)
        // produces a completely different plaintext.
        val nonce = 0x0762
        val plaintext = VictronCipher.decrypt(
            key = TestVectors.SOLAR_CHARGER_KEY,
            nonce = nonce,
            ciphertext = "ceb37b605d7e0ee21b24df5c".hexToBytes(),
        )
        assertEquals("04006c050e000300130000fe", plaintext.toHex())
    }

    @Test
    fun `decryption is symmetric for payloads spanning several blocks`() {
        val key = VictronCipher.parseKey("000102030405060708090a0b0c0d0e0f")
        val plaintext = ByteArray(40) { it.toByte() }
        val ciphertext = VictronCipher.decrypt(key, nonce = 0xBEEF, ciphertext = plaintext)
        assertEquals(plaintext.toHex(), VictronCipher.decrypt(key, 0xBEEF, ciphertext).toHex())
    }

    @Test
    fun `parses keys in the format VictronConnect shows`() {
        val expected = "adeccb947395801a4dd45a2eaa44bf17"
        assertEquals(expected, VictronCipher.parseKey(expected).toHex())
        assertEquals(expected, VictronCipher.parseKey("ADECCB947395801A4DD45A2EAA44BF17").toHex())
        assertEquals(expected, VictronCipher.parseKey("adec cb94 7395 801a 4dd4 5a2e aa44 bf17").toHex())
    }

    @Test
    fun `rejects malformed keys`() {
        assertThrows<IllegalArgumentException> { VictronCipher.parseKey("deadbeef") }
        assertThrows<IllegalArgumentException> { VictronCipher.parseKey("z".repeat(32)) }
    }

    @Test
    fun `uses the key check byte to tell keys apart`() {
        val header = (VictronAdvertisement.parse(TestVectors.SOLAR_CHARGER_ADVERTISEMENT) as ParseResult.Success).header
        assertTrue(VictronCipher.matches(TestVectors.SOLAR_CHARGER_KEY, header))
        assertFalse(VictronCipher.matches(VictronCipher.parseKey("00".repeat(16)), header))
    }
}
