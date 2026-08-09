package de.universam.victron.protocol.records

import de.universam.victron.protocol.BitReader
import de.universam.victron.protocol.RecordType

/** A decoded Instant Readout payload. */
public sealed interface VictronRecord {
    public val recordType: RecordType
}

/**
 * Record type `0x01` — solar charger (SmartSolar / BlueSolar MPPT).
 *
 * All fields are `null` when the device reports them as "not available".
 */
public data class SolarChargerRecord(
    public val chargerState: ChargerState?,
    public val chargerStateCode: Int,
    public val chargerError: ChargerError?,
    public val chargerErrorCode: Int,
    /** Battery voltage in volts. */
    public val batteryVoltage: Double?,
    /** Battery charging current in amps, negative when discharging. */
    public val batteryCurrent: Double?,
    /** Yield of the current day in watt hours. */
    public val yieldTodayWh: Int?,
    /** Current PV power in watts. */
    public val pvPowerW: Int?,
    /** Current of the load output in amps, `null` on models without one. */
    public val loadCurrent: Double?,
) : VictronRecord {

    override val recordType: RecordType get() = RecordType.SOLAR_CHARGER

    /** Power going into the battery in watts, derived from voltage and current. */
    public val batteryPowerW: Double?
        get() {
            val voltage = batteryVoltage ?: return null
            val current = batteryCurrent ?: return null
            return voltage * current
        }

    /** Yield of the current day in kilowatt hours. */
    public val yieldTodayKWh: Double?
        get() = yieldTodayWh?.let { it / 1000.0 }

    public companion object {
        internal const val NA_U8 = 0xFF
        internal const val NA_I16 = 0x7FFF
        internal const val NA_U16 = 0xFFFF
        internal const val NA_U9 = 0x1FF

        /** Minimum number of payload bits needed to decode a full record. */
        public const val REQUIRED_BITS: Int = 8 + 8 + 16 + 16 + 16 + 16 + 9

        public fun decode(payload: ByteArray): SolarChargerRecord {
            val reader = BitReader(payload)
            val state = reader.readUnsignedInt(8)
            val error = reader.readUnsignedInt(8)
            // 0.01 V
            val voltage = reader.readSignedInt(16)
            // 0.1 A
            val current = reader.readSignedInt(16)
            // 10 Wh
            val yieldToday = reader.readUnsignedInt(16)
            // 1 W
            val pvPower = reader.readUnsignedInt(16)
            // 0.1 A
            val loadCurrent = reader.readUnsignedInt(9)

            return SolarChargerRecord(
                chargerState = ChargerState.fromCode(state),
                chargerStateCode = state,
                chargerError = ChargerError.fromCode(error),
                chargerErrorCode = error,
                batteryVoltage = if (voltage != NA_I16) voltage / 100.0 else null,
                batteryCurrent = if (current != NA_I16) current / 10.0 else null,
                yieldTodayWh = if (yieldToday != NA_U16) yieldToday * 10 else null,
                pvPowerW = if (pvPower != NA_U16) pvPower else null,
                loadCurrent = if (loadCurrent != NA_U9) loadCurrent / 10.0 else null,
            )
        }
    }
}

/**
 * A record whose type this library cannot decode yet. The decrypted [payload] is kept so the
 * debug screen can show it — that is exactly what is needed to add a new decoder (e.g. for a
 * SmartShunt or a Lynx Smart BMS).
 */
public class UnknownRecord(
    override val recordType: RecordType,
    public val recordTypeCode: Int,
    public val payload: ByteArray,
) : VictronRecord {

    public val payloadHex: String
        get() = payload.joinToString("") { "%02x".format(it) }

    override fun toString(): String =
        "UnknownRecord(type=0x${recordTypeCode.toString(16).uppercase()}, payload=$payloadHex)"
}
