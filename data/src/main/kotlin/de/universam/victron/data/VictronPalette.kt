package de.universam.victron.data

/**
 * One palette for the app screens, the tile and the phone — a value must not change colour
 * depending on where you read it.
 *
 * The hues follow VictronConnect: yellow is solar, blue is the battery, green means energy is going
 * in, orange means it is going out. Colours are ARGB ints so the ProtoLayout tile (which knows
 * nothing about Compose) can use exactly the same numbers.
 */
public object VictronPalette {

    public const val SOLAR: Int = 0xFFFFC531.toInt()
    public const val BATTERY: Int = 0xFF4FC3F7.toInt()
    public const val CHARGING: Int = 0xFF7ED957.toInt()
    public const val DISCHARGING: Int = 0xFFFF8A5C.toInt()
    public const val YIELD: Int = 0xFF9CCC65.toInt()
    public const val ERROR: Int = 0xFFFF6B6B.toInt()

    // Heat-gradient stops for the PV arc (low → mid → high power)
    public const val HEAT_LOW: Int = 0xFFFFC531.toInt()    // SOLAR yellow
    public const val HEAT_MID: Int = 0xFFFF8C00.toInt()    // dark orange
    public const val HEAT_HIGH: Int = 0xFFFF3D00.toInt()   // fire-red

    // Battery current gradient stops (charging direction)
    public const val CURRENT_LOW: Int = 0xFF7ED957.toInt()   // CHARGING green
    public const val CURRENT_MID: Int = 0xFFCCDB39.toInt()   // yellow-green
    public const val CURRENT_HIGH: Int = 0xFFFF8A5C.toInt()  // orange

    public const val TRACK: Int = 0xFF2A2D33.toInt()
    public const val TEXT: Int = 0xFFFFFFFF.toInt()
    public const val TEXT_DIM: Int = 0xFF9AA0A6.toInt()
    public const val BACKGROUND: Int = 0xFF000000.toInt()
    public const val SURFACE: Int = 0xFF1B1D22.toInt()

    /** Green while charging, orange while the battery is being drained, dim when there is no value. */
    public fun currentColor(amps: Double?): Int = when {
        amps == null -> TEXT_DIM
        amps > 0.05 -> CHARGING
        amps < -0.05 -> DISCHARGING
        else -> TEXT_DIM
    }
}
