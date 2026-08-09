package de.universam.victron.protocol

/**
 * Record type of the "extra manufacturer data" carried by a product advertisement. It decides
 * how the decrypted payload has to be bit-unpacked.
 *
 * Only [SOLAR_CHARGER] is decoded by this library so far; every other type is surfaced as
 * [de.universam.victron.protocol.records.UnknownRecord] together with its decrypted bytes, so
 * that adding a decoder later is a local change (see docs/victron-ble-protocol.md for the
 * documented payload layouts).
 */
public enum class RecordType(public val code: Int, public val label: String) {
    SOLAR_CHARGER(0x01, "Solar charger"),
    BATTERY_MONITOR(0x02, "Battery monitor"),
    INVERTER(0x03, "Inverter"),
    DCDC_CONVERTER(0x04, "DC/DC converter"),
    SMART_LITHIUM(0x05, "SmartLithium"),
    INVERTER_RS(0x06, "Inverter RS"),
    AC_CHARGER(0x08, "AC charger"),
    SMART_BATTERY_PROTECT(0x09, "Smart BatteryProtect"),
    LYNX_SMART_BMS(0x0A, "Lynx Smart BMS"),
    MULTI_RS(0x0B, "Multi RS"),
    VE_BUS(0x0C, "VE.Bus"),
    DC_ENERGY_METER(0x0D, "DC energy meter"),
    ORION_XS(0x0F, "Orion XS"),

    /** A record type that is not (yet) known to this library. */
    UNKNOWN(-1, "Unknown record"),
    ;

    public companion object {
        public fun fromCode(code: Int): RecordType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
