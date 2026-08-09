package de.universam.victron.protocol

import de.universam.victron.protocol.records.SolarChargerRecord
import de.universam.victron.protocol.records.UnknownRecord
import de.universam.victron.protocol.records.VictronRecord

/** Outcome of decoding one advertisement. */
public sealed interface DecodeResult {

    public data class Decoded(val header: VictronHeader, val record: VictronRecord) : DecodeResult

    /** Header understood, but no advertisement key was supplied. */
    public data class MissingKey(val header: VictronHeader) : DecodeResult

    /** The supplied key does not match the key-check byte of this advertisement. */
    public data class KeyMismatch(val header: VictronHeader) : DecodeResult

    /** Not a Victron product advertisement, or truncated beyond use. */
    public data class Unusable(val reason: String) : DecodeResult

    /** Decryption worked but the payload was too short for the record type. */
    public data class PayloadTooShort(val header: VictronHeader, val payload: ByteArray) : DecodeResult
}

/**
 * Turns raw manufacturer data into a typed record: parse header → check key → AES-CTR →
 * bit-unpack.
 */
public object VictronDecoder {

    /**
     * @param manufacturerData payload of manufacturer id [VictronBle.MANUFACTURER_ID].
     * @param key 16 byte advertisement key, or `null` when it is not known yet.
     */
    public fun decode(manufacturerData: ByteArray, key: ByteArray?): DecodeResult =
        when (val parsed = VictronAdvertisement.parse(manufacturerData)) {
            is ParseResult.TooShort ->
                DecodeResult.Unusable("Manufacturer data too short (${parsed.length} bytes)")

            is ParseResult.NotProductAdvertisement ->
                DecodeResult.Unusable(
                    "Not a product advertisement (prefix 0x${parsed.prefix.toString(16).uppercase()})",
                )

            is ParseResult.Success -> decode(parsed.header, key)
        }

    public fun decode(header: VictronHeader, key: ByteArray?): DecodeResult {
        if (key == null) return DecodeResult.MissingKey(header)
        if (!VictronCipher.matches(key, header)) return DecodeResult.KeyMismatch(header)

        val payload = VictronCipher.decrypt(key, header.nonce, header.ciphertext)
        return when (header.recordType) {
            RecordType.SOLAR_CHARGER ->
                if (payload.size * 8 < SolarChargerRecord.REQUIRED_BITS) {
                    DecodeResult.PayloadTooShort(header, payload)
                } else {
                    DecodeResult.Decoded(header, SolarChargerRecord.decode(payload))
                }

            else -> DecodeResult.Decoded(
                header,
                UnknownRecord(header.recordType, header.recordTypeCode, payload),
            )
        }
    }
}
