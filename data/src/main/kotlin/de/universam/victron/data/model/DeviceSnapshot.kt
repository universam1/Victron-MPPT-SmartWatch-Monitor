package de.universam.victron.data.model

import de.universam.victron.protocol.DecodeResult
import de.universam.victron.protocol.VictronHeader
import de.universam.victron.protocol.records.SolarChargerRecord
import de.universam.victron.protocol.records.UnknownRecord
import kotlinx.serialization.Serializable

/** How much of an advertisement we were able to make sense of. */
public enum class SnapshotStatus {
    /** Fully decoded, [DeviceSnapshot.solarCharger] is populated. */
    DECODED,

    /** Seen and identified, but no advertisement key configured yet. */
    MISSING_KEY,

    /** A key is configured for this address but it does not match the advertisement. */
    KEY_MISMATCH,

    /** Decrypted, but this record type has no decoder yet — see [DeviceSnapshot.payloadHex]. */
    UNDECODED_RECORD,
}

/** Decoded values of a solar charger record, flattened for persistence. */
@Serializable
public data class SolarChargerValues(
    val chargerStateLabel: String?,
    val chargerStateCode: Int,
    val chargerErrorLabel: String?,
    val chargerErrorCode: Int,
    val batteryVoltage: Double?,
    val batteryCurrent: Double?,
    val pvPowerW: Int?,
    val yieldTodayWh: Int?,
    val loadCurrent: Double?,
) {
    public val batteryPowerW: Double?
        get() {
            val voltage = batteryVoltage ?: return null
            val current = batteryCurrent ?: return null
            return voltage * current
        }

    public val hasError: Boolean get() = chargerErrorCode != 0 && chargerErrorCode != 0xFF
}

/**
 * The latest thing we know about one Victron device. This is what the UI renders and what gets
 * cached on disk so the tile has something to show before the first scan of a session finishes.
 */
@Serializable
public data class DeviceSnapshot(
    val address: String,
    val bleName: String?,
    val label: String?,
    val modelId: Int,
    val modelName: String,
    val recordTypeCode: Int,
    val recordLabel: String,
    val rssi: Int,
    /** Wall clock time of the advertisement, used to show how old the values are. */
    val receivedAtEpochMillis: Long,
    val status: SnapshotStatus,
    val solarCharger: SolarChargerValues? = null,
    /** Decrypted payload of a record type we cannot decode yet, as hex. */
    val payloadHex: String? = null,
) {
    /** What to call this device in the UI. */
    public val displayName: String get() = label?.takeIf { it.isNotBlank() } ?: modelName

    public fun ageMillis(nowEpochMillis: Long): Long = (nowEpochMillis - receivedAtEpochMillis).coerceAtLeast(0)

    public companion object {
        public fun from(
            address: String,
            bleName: String?,
            label: String?,
            rssi: Int,
            receivedAtEpochMillis: Long,
            result: DecodeResult,
        ): DeviceSnapshot? = when (result) {
            is DecodeResult.Decoded -> base(address, bleName, label, rssi, receivedAtEpochMillis, result.header).let {
                when (val record = result.record) {
                    is SolarChargerRecord -> it.copy(
                        status = SnapshotStatus.DECODED,
                        solarCharger = record.toValues(),
                    )

                    is UnknownRecord -> it.copy(
                        status = SnapshotStatus.UNDECODED_RECORD,
                        payloadHex = record.payloadHex,
                    )
                }
            }

            is DecodeResult.MissingKey ->
                base(address, bleName, label, rssi, receivedAtEpochMillis, result.header)
                    .copy(status = SnapshotStatus.MISSING_KEY)

            is DecodeResult.KeyMismatch ->
                base(address, bleName, label, rssi, receivedAtEpochMillis, result.header)
                    .copy(status = SnapshotStatus.KEY_MISMATCH)

            is DecodeResult.PayloadTooShort ->
                base(address, bleName, label, rssi, receivedAtEpochMillis, result.header)
                    .copy(status = SnapshotStatus.UNDECODED_RECORD)

            is DecodeResult.Unusable -> null
        }

        private fun base(
            address: String,
            bleName: String?,
            label: String?,
            rssi: Int,
            receivedAtEpochMillis: Long,
            header: VictronHeader,
        ) = DeviceSnapshot(
            address = address,
            bleName = bleName,
            label = label,
            modelId = header.modelId,
            modelName = header.modelName,
            recordTypeCode = header.recordTypeCode,
            recordLabel = header.recordType.label,
            rssi = rssi,
            receivedAtEpochMillis = receivedAtEpochMillis,
            status = SnapshotStatus.MISSING_KEY,
        )

        private fun SolarChargerRecord.toValues() = SolarChargerValues(
            chargerStateLabel = chargerState?.label,
            chargerStateCode = chargerStateCode,
            chargerErrorLabel = chargerError?.label,
            chargerErrorCode = chargerErrorCode,
            batteryVoltage = batteryVoltage,
            batteryCurrent = batteryCurrent,
            pvPowerW = pvPowerW,
            yieldTodayWh = yieldTodayWh,
            loadCurrent = loadCurrent,
        )
    }
}

/** Persisted cache of the most recent snapshot per device. */
@Serializable
public data class SnapshotCache(
    val snapshots: List<DeviceSnapshot> = emptyList(),
)
