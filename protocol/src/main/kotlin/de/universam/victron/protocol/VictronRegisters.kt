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

    // ---- VeSmartService GATT UUIDs (306b — requires encrypted link) -------------------------

    /**
     * The VeSmartService for settings read/write (path-based CBOR protocol).
     * Requires BLE bonding (encrypted link). Not visible until pairing is complete.
     */
    public val SMART_SERVICE_UUID: UUID =
        UUID.fromString("306b0001-b081-4037-83dc-e59fcc3cdfd0")

    /**
     * VeSmartService RX characteristic (Read + WriteWithoutResponse + Notify).
     * Receives keepalive/ack notifications (f901).
     */
    public val SMART_RX_UUID: UUID =
        UUID.fromString("306b0002-b081-4037-83dc-e59fcc3cdfd0")

    /**
     * VeSmartService TX characteristic (WriteWithoutResponse + Notify).
     * Carries protocol commands (init, GetDevices, GetPathList, GetPathValue, SetPathValue)
     * and their responses as notifications.
     */
    public val SMART_TX_UUID: UUID =
        UUID.fromString("306b0003-b081-4037-83dc-e59fcc3cdfd0")

    /**
     * VeSmartService bulk characteristic (WriteWithoutResponse + Notify).
     * Carries bulk path queries (batched GetPathValue requests).
     */
    public val SMART_BULK_UUID: UUID =
        UUID.fromString("306b0004-b081-4037-83dc-e59fcc3cdfd0")

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

    // ---- Load output control values (register 0xEDAB) ---------------------------------------
    // VictronConnect writes this register directly; its QML calls the same value
    // `loadOperationMode`. Only 1 and 4 are needed for an on/off toggle.

    /** Automatic — load follows the battery voltage thresholds. */
    public const val LOAD_AUTO: Int = 1

    /** Always on — load output permanently enabled. */
    public const val LOAD_ALWAYS_ON: Int = 4

    /**
     * Streetlight flag, bit 7 of [LOAD_OUTPUT_CONTROL]. It shares the register with the mode,
     * so a write must carry the current flag through or it silently turns streetlight off.
     */
    public const val LOAD_STREETLIGHT_BIT: Int = 0x80

    /** Mode part of a [LOAD_OUTPUT_CONTROL] value, with the streetlight flag masked off. */
    public fun loadMode(registerValue: Int): Int = registerValue and LOAD_STREETLIGHT_BIT.inv() and 0xFF

    /** Combines a [LOAD_AUTO]/[LOAD_ALWAYS_ON] mode with the streetlight flag of [previous]. */
    public fun loadValuePreservingFlags(mode: Int, previous: Int): Int =
        (mode and 0x7F) or (previous and LOAD_STREETLIGHT_BIT)

    // ---- Capability bits (from register 0x0140, firmware >= 1.16) ---------------------------

    /** Device has a physical load output. */
    public const val CAP_HAS_LOAD: Int = 0x0001

    /** Device has user-configurable load switch voltage levels. */
    public const val CAP_HAS_USER_LOAD_SWITCH: Int = 0x0800

    /** Device has a virtual load output. */
    public const val CAP_HAS_VIRTUAL_LOAD: Int = 0x80000

    // ---- Product IDs with load output -------------------------------------------------------

    /** SmartSolar MPPT 100/20-48V. */
    public const val PRODUCT_SMARTSOLAR_100_20_48V: Int = 0xA05F

    /**
     * Product ids whose load output is configurable, verbatim from VictronConnect's
     * `hasLoadOutputConfig` QML property.
     */
    public val PRODUCTS_WITH_LOAD_OUTPUT: Set<Int> = setOf(
        0xA04C, 0xA054, 0xA042, 0xA053, 0xA043, 0xA055,
        0xA066, 0xA05F, 0xA067, 0xA060, 0xA07B, 0xA079,
        0xA07C, 0xA074, 0xA07A, 0xA07D, 0xA07F, 0xA075,
    )
}
