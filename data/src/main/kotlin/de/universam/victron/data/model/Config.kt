package de.universam.victron.data.model

import kotlinx.serialization.Serializable

/** A Victron device the user has configured, identified by its BLE address. */
@Serializable
public data class DeviceConfig(
    /** BLE MAC address as reported by Android, e.g. `C0:3B:12:34:56:78`. */
    val address: String,
    /** 32 hex characters, from VictronConnect → Product info → Instant readout. */
    val advertisementKeyHex: String,
    /** Optional user label; the model name is used when this is null. */
    val label: String? = null,
    /** Show this device on the tile. */
    val showOnTile: Boolean = true,
)

/** Everything the apps persist. Small enough for a single JSON DataStore file. */
@Serializable
public data class AppConfig(
    val devices: List<DeviceConfig> = emptyList(),
    /**
     * Scan every ~15 minutes in the background so the tile is not stale when the user raises
     * their wrist. Off by default — it costs battery.
     */
    val backgroundScanEnabled: Boolean = false,
    /** Length of a one-shot scan window in seconds (tile enter, background worker). */
    val scanWindowSeconds: Int = 20,
) {
    public fun keyFor(address: String): String? =
        devices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.advertisementKeyHex

    public fun labelFor(address: String): String? =
        devices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.label

    public val tileAddresses: List<String>
        get() = devices.filter { it.showOnTile }.map { it.address }
}
