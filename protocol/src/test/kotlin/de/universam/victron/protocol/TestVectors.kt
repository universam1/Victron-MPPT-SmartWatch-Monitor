package de.universam.victron.protocol

/**
 * Known-good vectors for the Instant Readout format.
 *
 * [SOLAR_CHARGER_ADVERTISEMENT] and its key are the publicly published end-to-end example of a
 * BlueSolar MPPT 75/15 (see docs/victron-ble-protocol.md); the expected values are what
 * VictronConnect showed for that advertisement.
 */
internal object TestVectors {

    /** Manufacturer data of company id 0x02E1, without the two company id bytes. */
    val SOLAR_CHARGER_ADVERTISEMENT: ByteArray = "100242a0016207adceb37b605d7e0ee21b24df5c".hexToBytes()

    val SOLAR_CHARGER_KEY: ByteArray = VictronCipher.parseKey("adeccb947395801a4dd45a2eaa44bf17")

    val SOLAR_CHARGER_PLAINTEXT: ByteArray = "04006c050e000300130000fe".hexToBytes()

    /** Same device in bulk charge. */
    val BULK_PLAINTEXT: ByteArray = "0300f80402000200030000fe".hexToBytes()

    /** A 24 V MPPT 100/xx at 265 W. */
    val MPPT_24V_PLAINTEXT: ByteArray = "0300fb09650032000901ffff".hexToBytes()
}
