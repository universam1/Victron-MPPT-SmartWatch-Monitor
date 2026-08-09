package de.universam.victron.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR as Victron uses it for Instant Readout advertisements.
 *
 * The counter block is the 16 bit nonce from the advertisement header, written as a **little
 * endian 128 bit integer**, and it is incremented little-endian for every following block. That
 * differs from `AES/CTR/NoPadding` of the JCE, which counts big-endian, so the keystream is
 * generated block by block with plain AES-ECB instead. For a single-block payload the two only
 * differ in byte order; for longer payloads they diverge completely.
 */
public object VictronCipher {

    private const val BLOCK_SIZE = 16

    /**
     * Parses a 32 character hex advertisement key as shown by VictronConnect
     * (Product info → Instant readout via Bluetooth → Encryption data).
     *
     * Spaces, colons and dashes are ignored.
     *
     * @throws IllegalArgumentException if the key is not 16 bytes of valid hex.
     */
    public fun parseKey(hexKey: String): ByteArray {
        val cleaned = hexKey.filterNot { it == ' ' || it == ':' || it == '-' }
        require(cleaned.length == VictronBle.KEY_SIZE * 2) {
            "Advertisement key must be ${VictronBle.KEY_SIZE * 2} hex characters, was ${cleaned.length}"
        }
        return ByteArray(VictronBle.KEY_SIZE) { index ->
            val hi = Character.digit(cleaned[index * 2], 16)
            val lo = Character.digit(cleaned[index * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Advertisement key contains non-hex characters" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /** True when [key] can possibly belong to [header], judged by the plaintext key-check byte. */
    public fun matches(key: ByteArray, header: VictronHeader): Boolean =
        key.isNotEmpty() && key[0].toUByte().toInt() == header.keyCheckByte

    /**
     * Decrypts [ciphertext] with [key], using [nonce] as the initial counter value.
     *
     * @return the decrypted payload, exactly as long as [ciphertext].
     */
    public fun decrypt(key: ByteArray, nonce: Int, ciphertext: ByteArray): ByteArray {
        require(key.size == VictronBle.KEY_SIZE) { "Key must be ${VictronBle.KEY_SIZE} bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))

        val counter = ByteArray(BLOCK_SIZE)
        counter[0] = (nonce and 0xFF).toByte()
        counter[1] = ((nonce shr 8) and 0xFF).toByte()

        val plaintext = ByteArray(ciphertext.size)
        var offset = 0
        while (offset < ciphertext.size) {
            val keystream = cipher.doFinal(counter)
            val chunk = minOf(BLOCK_SIZE, ciphertext.size - offset)
            for (i in 0 until chunk) {
                plaintext[offset + i] = (ciphertext[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += chunk
            incrementLittleEndian(counter)
        }
        return plaintext
    }

    private fun incrementLittleEndian(counter: ByteArray) {
        for (i in counter.indices) {
            val incremented = (counter[i].toInt() and 0xFF) + 1
            counter[i] = incremented.toByte()
            if (incremented <= 0xFF) return
        }
    }
}
