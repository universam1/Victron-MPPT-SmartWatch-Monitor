package de.universam.victron.protocol

/**
 * The unencrypted part of a Victron product advertisement.
 *
 * Everything in here is readable without knowing the device's advertisement key, which is what
 * makes device discovery possible before the user has entered any key at all.
 */
public class VictronHeader internal constructor(
    /** Length of the extra manufacturer data record as announced by the device. */
    public val recordLength: Int,
    /** Victron model id, e.g. `0xA053` for a SmartSolar MPPT 75/15. */
    public val modelId: Int,
    public val recordType: RecordType,
    /** Raw record type byte, kept so unknown types stay reportable. */
    public val recordTypeCode: Int,
    /** 16 bit data counter, used as the initial value of the AES-CTR counter. */
    public val nonce: Int,
    /** First byte of the advertisement key, sent in the clear so receivers can check their key. */
    public val keyCheckByte: Int,
    public val ciphertext: ByteArray,
) {
    /** Human readable model name, or a `0x….` placeholder for model ids we don't know. */
    public val modelName: String get() = VictronModels.nameFor(modelId)

    override fun toString(): String =
        "VictronHeader(model=0x${modelId.toString(16).uppercase()} \"$modelName\", " +
            "record=${recordType.label}(0x${recordTypeCode.toString(16).uppercase()}), " +
            "nonce=$nonce, keyCheck=0x${keyCheckByte.toString(16).uppercase()}, " +
            "ciphertext=${ciphertext.size}B)"
}

/** Outcome of looking at the manufacturer data of a BLE advertisement. */
public sealed interface ParseResult {
    public data class Success(val header: VictronHeader) : ParseResult

    /** Valid Victron manufacturer data, but not a product advertisement (first byte != 0x10). */
    public data class NotProductAdvertisement(val prefix: Int) : ParseResult

    public data class TooShort(val length: Int) : ParseResult
}

/** Parser for the manufacturer-specific data of Victron's `0x02E1` manufacturer id. */
public object VictronAdvertisement {

    /**
     * @param manufacturerData the payload of manufacturer id [VictronBle.MANUFACTURER_ID],
     *   i.e. what `ScanRecord.getManufacturerSpecificData(0x02E1)` returns — without the two
     *   company id bytes.
     */
    public fun parse(manufacturerData: ByteArray): ParseResult {
        if (manufacturerData.size <= VictronBle.HEADER_SIZE) {
            return ParseResult.TooShort(manufacturerData.size)
        }
        val prefix = manufacturerData[0].toUByte().toInt()
        if (prefix != VictronBle.PRODUCT_ADVERTISEMENT) {
            return ParseResult.NotProductAdvertisement(prefix)
        }
        return ParseResult.Success(
            VictronHeader(
                recordLength = manufacturerData[1].toUByte().toInt(),
                modelId = manufacturerData.readUInt16Le(2),
                recordType = RecordType.fromCode(manufacturerData[4].toUByte().toInt()),
                recordTypeCode = manufacturerData[4].toUByte().toInt(),
                nonce = manufacturerData.readUInt16Le(5),
                keyCheckByte = manufacturerData[7].toUByte().toInt(),
                ciphertext = manufacturerData.copyOfRange(VictronBle.HEADER_SIZE, manufacturerData.size),
            ),
        )
    }

    private fun ByteArray.readUInt16Le(offset: Int): Int =
        (this[offset].toUByte().toInt()) or (this[offset + 1].toUByte().toInt() shl 8)
}
