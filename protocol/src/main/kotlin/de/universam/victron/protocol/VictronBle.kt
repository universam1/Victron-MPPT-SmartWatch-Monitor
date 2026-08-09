package de.universam.victron.protocol

/**
 * Constants of Victron's "Instant Readout" BLE advertising format.
 *
 * Victron devices with Instant Readout enabled broadcast their live measurements inside the
 * manufacturer-specific data of their BLE advertisements. Reading them is completely
 * connectionless: no pairing, no GATT connection, and VictronConnect stays usable in parallel.
 *
 * See [docs/victron-ble-protocol.md](../../../../../../../docs/victron-ble-protocol.md).
 */
public object VictronBle {

    /** Bluetooth SIG company identifier of Victron Energy BV. */
    public const val MANUFACTURER_ID: Int = 0x02E1

    /**
     * First byte of the manufacturer data for the only record we care about,
     * "Product Advertisement". Anything else must be ignored.
     */
    public const val PRODUCT_ADVERTISEMENT: Int = 0x10

    /**
     * Bytes before the ciphertext: prefix, record length, model id (2), record type,
     * nonce (2) and the key-check byte.
     */
    public const val HEADER_SIZE: Int = 8

    /** Length of an Instant Readout advertisement key (AES-128). */
    public const val KEY_SIZE: Int = 16
}
