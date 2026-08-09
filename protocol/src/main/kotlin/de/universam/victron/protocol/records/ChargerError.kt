package de.universam.victron.protocol.records

/**
 * MPPT / charger error codes (VE.Direct protocol, see
 * https://www.victronenergy.com/live/mppt-error-codes). [code] is the number VictronConnect
 * shows as "Err N".
 */
public enum class ChargerError(public val code: Int, public val label: String) {
    NO_ERROR(0, "No error"),
    BATTERY_TEMPERATURE_HIGH(1, "Battery temperature too high"),
    BATTERY_VOLTAGE_HIGH(2, "Battery voltage too high"),
    REMOTE_TEMPERATURE_SENSOR_A(3, "Remote temperature sensor failure"),
    REMOTE_TEMPERATURE_SENSOR_B(4, "Remote temperature sensor failure"),
    REMOTE_TEMPERATURE_SENSOR_C(5, "Remote temperature sensor failure"),
    REMOTE_BATTERY_VOLTAGE_A(6, "Remote battery voltage sense failure"),
    REMOTE_BATTERY_VOLTAGE_B(7, "Remote battery voltage sense failure"),
    REMOTE_BATTERY_VOLTAGE_C(8, "Remote battery voltage sense failure"),
    HIGH_RIPPLE(11, "Battery high ripple voltage"),
    BATTERY_TEMPERATURE_LOW(14, "Battery temperature too low"),
    CHARGER_TEMPERATURE_HIGH(17, "Charger temperature too high"),
    CHARGER_OVER_CURRENT(18, "Charger over current"),
    BULK_TIME_LIMIT(20, "Bulk time limit exceeded"),
    CURRENT_SENSOR(21, "Current sensor issue"),
    INTERNAL_TEMPERATURE_A(22, "Internal temperature sensor failure"),
    INTERNAL_TEMPERATURE_B(23, "Internal temperature sensor failure"),
    FAN_FAILURE(24, "Fan failure"),
    TERMINALS_OVERHEATED(26, "Terminals overheated"),
    SHORT_CIRCUIT(27, "Charger short circuit"),
    CONVERTER_ISSUE(28, "Power stage / converter issue"),
    OVER_CHARGE(29, "Over-charge protection"),
    PV_VOLTAGE_HIGH(33, "PV input voltage too high"),
    PV_CURRENT_HIGH(34, "PV input current too high"),
    PV_OVER_POWER(35, "PV over-power"),
    INPUT_SHUTDOWN_VOLTAGE(38, "Input shutdown (battery over-voltage)"),
    INPUT_SHUTDOWN_CURRENT(39, "Input shutdown (current in off mode)"),
    INPUT_SHUTDOWN_FAILURE(40, "PV input failed to shut down"),
    COMMUNICATION_WARNING(65, "Communication warning"),
    INCOMPATIBLE_DEVICE(66, "Incompatible device"),
    BMS_CONNECTION_LOST(67, "BMS connection lost"),
    NETWORK_MISCONFIGURED_A(68, "Network misconfigured"),
    NETWORK_MISCONFIGURED_B(69, "Network misconfigured"),
    NETWORK_MISCONFIGURED_C(70, "Network misconfigured"),
    NETWORK_MISCONFIGURED_D(71, "Network misconfigured"),
    CPU_TEMPERATURE_HIGH(114, "CPU temperature too high"),
    CALIBRATION_LOST(116, "Factory calibration data lost"),
    INVALID_FIRMWARE(117, "Invalid / incompatible firmware"),
    SETTINGS_LOST(119, "Settings data lost"),
    ;

    public val isError: Boolean get() = this != NO_ERROR

    public companion object {
        /** @return null for the "not available" code 0xFF or a code we don't have a label for. */
        public fun fromCode(code: Int): ChargerError? = entries.firstOrNull { it.code == code }
    }
}
