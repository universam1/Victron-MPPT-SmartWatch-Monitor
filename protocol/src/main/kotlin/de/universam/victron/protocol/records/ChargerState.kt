package de.universam.victron.protocol.records

/**
 * Device / charger state as used by VE.Direct and reported in the first byte of a solar charger
 * record. Not every value can occur on an MPPT, the enum simply mirrors the protocol.
 */
public enum class ChargerState(public val code: Int, public val label: String) {
    OFF(0, "Off"),
    LOW_POWER(1, "Low power"),
    FAULT(2, "Fault"),
    BULK(3, "Bulk"),
    ABSORPTION(4, "Absorption"),
    FLOAT(5, "Float"),
    STORAGE(6, "Storage"),
    EQUALIZE_MANUAL(7, "Equalize (manual)"),
    INVERTING(9, "Inverting"),
    POWER_SUPPLY(11, "Power supply"),
    STARTING_UP(245, "Starting up"),
    REPEATED_ABSORPTION(246, "Repeated absorption"),
    RECONDITION(247, "Recondition"),
    BATTERY_SAFE(248, "Battery safe"),
    ACTIVE(249, "Active"),
    EXTERNAL_CONTROL(252, "External control"),
    ;

    public companion object {
        /** @return null for the "not available" code 0xFF or an unknown code. */
        public fun fromCode(code: Int): ChargerState? = entries.firstOrNull { it.code == code }
    }
}
