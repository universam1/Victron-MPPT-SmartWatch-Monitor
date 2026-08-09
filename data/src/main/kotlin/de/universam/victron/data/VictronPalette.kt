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
