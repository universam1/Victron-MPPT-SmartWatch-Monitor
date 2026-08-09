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
    /**
     * Full scale of the power arc in watts. `0` means "derive it from the highest power seen so
     * far", which is what most people want without having to know their array size.
     */
    val pvPeakWatts: Int = 0,
    /** When this entry was last changed, used to resolve phone/watch sync conflicts. */
    val updatedAtEpochMillis: Long = 0,
)

/** Everything the apps persist. Small enough for a single JSON DataStore file. */
@Serializable
public data class AppConfig(
    val devices: List<DeviceConfig> = emptyList(),
    /**
     * Scan every ~15 minutes in the background so the tile is not stale when the user raises
     * their wrist. Off by default — it costs battery. Deliberately **not** synced: what a phone
     * can afford, a watch cannot.
     */
    val backgroundScanEnabled: Boolean = false,
    /** Length of a one-shot scan window in seconds (tile enter, background worker). */
    val scanWindowSeconds: Int = 20,
) {
    public fun deviceFor(address: String): DeviceConfig? =
        devices.firstOrNull { it.address.equals(address, ignoreCase = true) }

    public fun keyFor(address: String): String? = deviceFor(address)?.advertisementKeyHex

    public fun labelFor(address: String): String? = deviceFor(address)?.label

    public fun pvPeakWattsFor(address: String): Int = deviceFor(address)?.pvPeakWatts ?: 0

    public val tileAddresses: List<String>
        get() = devices.filter { it.showOnTile }.map { it.address }

    /**
     * Merges a device list coming from the other device.
     *
     * Per address the newer entry wins ([DeviceConfig.updatedAtEpochMillis]), and entries only one
     * side knows about are kept. Removals are therefore *not* propagated — a device you delete on
     * the phone stays on the watch until you delete it there too. That is the price of a union
     * merge, and it beats a rule where one device silently wipes the other's configuration.
     */
    public fun mergeDevices(remote: List<DeviceConfig>): AppConfig {
        if (remote.isEmpty()) return this
        val merged = LinkedHashMap<String, DeviceConfig>()
        devices.forEach { merged[it.address.uppercase()] = it }
        remote.forEach { incoming ->
            val key = incoming.address.uppercase()
            val existing = merged[key]
            if (existing == null || incoming.updatedAtEpochMillis > existing.updatedAtEpochMillis) {
                merged[key] = incoming
            }
        }
        return copy(devices = merged.values.toList())
    }

    /** True when this side knows something the [remote] list does not (or knows it better). */
    public fun hasNewerThan(remote: List<DeviceConfig>): Boolean = devices.any { local ->
        val counterpart = remote.firstOrNull { it.address.equals(local.address, ignoreCase = true) }
        counterpart == null || local.updatedAtEpochMillis > counterpart.updatedAtEpochMillis
    }
}
