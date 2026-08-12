package de.universam.victron.protocol

import java.util.UUID

/**
 * VE.Direct register definitions and BLE GATT UUIDs for the Victron SmartSolar MPPT.
 *
 * From VictronConnect APK disassembly (authoritative source, mid-2026):
 * - Single GATT service `68c10001` hosts both VeService (PIN, device info) and
 *   VeSmartService (CBOR path-based protocol for settings).
 * - Control characteristic `68c10002` carries opcodes and short messages.
 * - Data characteristic `68c10003` carries CBOR-chunked path values.
 *
 * The VeSmartService protocol is path-based: the device reports its available paths
 * (e.g. `/Settings/Load/OperationMode`) with integer indices; get/set use those indices,
 * never raw vreg IDs. The vreg constants below are kept for documentation and for the
 * VE.Direct serial fallback path.
 */
public object VictronRegisters {

    // ---- GATT UUIDs (from VictronConnect APK disassembly) ------------------------------------

    /**
     * The single Victron BLE GATT service. Both VeService (PIN auth, device info, DFU) and
     * VeSmartService (CBOR path-based settings) operate on this service.
     */
    public val SERVICE_UUID: UUID =
        UUID.fromString("68c10001-b17f-4d3a-a290-34ad6499937c")

    /**
     * Control characteristic (Write + Notify).
     * Carries: PIN auth, opcodes (keepalive, readyToReceive, error), short responses.
     */
    public val CONTROL_UUID: UUID =
        UUID.fromString("68c10002-b17f-4d3a-a290-34ad6499937c")

    /**
     * Data characteristic (Notify).
     * Carries: CBOR-chunked path values (path lists, get/set responses).
     */
    public val DATA_UUID: UUID =
        UUID.fromString("68c10003-b17f-4d3a-a290-34ad6499937c")

    // ---- VE.Direct Register IDs (for documentation / serial fallback) ------------------------

    /** Load output control mode (read/write, un8). */
    public const val LOAD_OUTPUT_CONTROL: Int = 0xEDAB

    /** Load output state (read-only, un8: 0=off, 1=on). */
    public const val LOAD_OUTPUT_STATE: Int = 0xEDA8

    /** Load switch high-level voltage threshold (read/write, un16, 0.01 V scale). */
    public const val LOAD_SWITCH_HIGH: Int = 0xED9D

    /** Load switch low-level voltage threshold (read/write, un16, 0.01 V scale). */
    public const val LOAD_SWITCH_LOW: Int = 0xED9C

    /** Device capabilities register (read, firmware >= 1.16). */
    public const val CAPABILITIES: Int = 0x0140

    // ---- Load output control values (path `/Settings/Load/OperationMode`) -------------------

    /** Automatic — BatteryLife algorithm, load follows voltage thresholds. */
    public const val LOAD_AUTO: Int = 1

    /** Always on — load output permanently enabled. */
    public const val LOAD_ALWAYS_ON: Int = 4

    // ---- Capability bits (from register 0x0140) ---------------------------------------------

    /** Device has a physical load output. */
    public const val CAP_HAS_LOAD: Int = 0x0001

    /** Device has user-configurable load switch voltage levels. */
    public const val CAP_HAS_USER_LOAD_SWITCH: Int = 0x0800

    // ---- VeSmartService path constants ------------------------------------------------------

    /** Path for load output operation mode (values: 1=auto, 4=always on). */
    public const val PATH_LOAD_OPERATION_MODE: String = "/Settings/Load/OperationMode"

    /** Path for charger mode (values: 1=on, 4=off, 0xFD=hibernate). */
    public const val PATH_MODE: String = "/Mode"

    /** Path for load output state (read-only, 0=off, 1=on). */
    public const val PATH_LOAD_STATE: String = "/Load/State"

    // ---- Product IDs with load output -------------------------------------------------------

    /** SmartSolar MPPT 100/20-48V. */
    public const val PRODUCT_SMARTSOLAR_100_20_48V: Int = 0xA05F
}
