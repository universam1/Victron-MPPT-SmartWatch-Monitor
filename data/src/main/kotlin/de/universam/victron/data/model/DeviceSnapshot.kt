package de.universam.victron.data.model

import de.universam.victron.protocol.DecodeResult
import de.universam.victron.protocol.VictronHeader
import de.universam.victron.protocol.VictronModels
import de.universam.victron.protocol.records.SolarChargerRecord
import de.universam.victron.protocol.records.UnknownRecord
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

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
    /** Highest PV power ever seen from this device, the fallback scale for the power arc. */
    val observedPvPeakW: Int = 0,
    /** Highest battery current ever seen, the fallback scale for the current arc. */
    val observedCurrentPeakA: Double = 0.0,
) {
    /** What to call this device in the UI. */
    public val displayName: String get() =
        label?.takeIf { it.isNotBlank() }
            ?: bleName?.takeIf { it.isNotBlank() }
            ?: modelName

    public fun ageMillis(nowEpochMillis: Long): Long = (nowEpochMillis - receivedAtEpochMillis).coerceAtLeast(0)

    /** Keeps values that survive a single advertisement, like the observed peaks. */
    public fun carryOver(previous: DeviceSnapshot?): DeviceSnapshot {
        val peakW = maxOf(
            previous?.observedPvPeakW ?: 0,
            observedPvPeakW,
            solarCharger?.pvPowerW ?: 0,
        )
        val peakA = maxOf(
            previous?.observedCurrentPeakA ?: 0.0,
            observedCurrentPeakA,
            solarCharger?.batteryCurrent?.let { abs(it) } ?: 0.0,
        )
        return if (peakW == observedPvPeakW && peakA == observedCurrentPeakA) {
            this
        } else {
            copy(observedPvPeakW = peakW, observedCurrentPeakA = peakA)
        }
    }

    /** Maximum charge current in A the model name promises; `null` for an unknown model. */
    public val maxChargeCurrentA: Int? get() = VictronModels.maxChargeCurrentA(modelId)

    /**
     * Full scale of the current arc. The charger's rating wins — a 100/20 can never push more than
     * 20 A. Only when the model is unknown do we fall back to the highest current seen, rounded up,
     * with a floor so a trickle does not look like a full charge (and never a division by zero).
     */
    public fun batteryCurrentMaxA(): Double {
        maxChargeCurrentA?.let { return it.toDouble() }
        val observed = maxOf(observedCurrentPeakA, solarCharger?.batteryCurrent?.let { abs(it) } ?: 0.0)
        return kotlin.math.ceil(maxOf(observed, MIN_SCALE_A) / 5.0) * 5.0
    }

    /**
     * Full scale of the power arc. The charger's rating wins: max charge current × battery voltage
     * is the most power it can put into the battery. Only when the model is unknown or nothing has
     * been decoded yet do we fall back to the highest power seen so far, rounded up to something a
     * human would draw a scale to, with a 50 W floor so a dark morning does not make 3 W look like
     * a full array.
     */
    public fun pvScaleMaxW(): Int {
        val amps = maxChargeCurrentA
        val volts = solarCharger?.batteryVoltage
        if (amps != null && volts != null) return (amps * volts).roundToInt()
        val observed = maxOf(observedPvPeakW, solarCharger?.pvPowerW ?: 0, MIN_SCALE_W)
        val step = when {
            observed <= 100 -> 50
            observed <= 500 -> 100
            observed <= 2000 -> 250
            else -> 500
        }
        return ((observed + step - 1) / step) * step
    }

    /** 0..1 for the power arc. */
    public fun pvFraction(): Float {
        val power = solarCharger?.pvPowerW ?: return 0f
        return (power.toFloat() / pvScaleMaxW()).coerceIn(0f, 1f)
    }

    /** 0..1 for the battery current arc. */
    public fun batteryCurrentFraction(): Float {
        val current = solarCharger?.batteryCurrent ?: return 0f
        return (abs(current) / batteryCurrentMaxA()).coerceIn(0.0, 1.0).toFloat()
    }

    public companion object {
        private const val MIN_SCALE_W = 50
        private const val MIN_SCALE_A = 5.0

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
