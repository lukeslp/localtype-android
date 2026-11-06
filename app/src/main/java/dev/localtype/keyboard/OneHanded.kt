package dev.localtype.keyboard

/** One-handed keys: off, or narrowed toward the left or the right edge. Read once per field. */
enum class OneHanded {
    OFF, LEFT, RIGHT;

    companion object {
        fun of(name: String?): OneHanded = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * How wide the keys are with one-handed on, and where the rail beside them goes. Pure, so the sizes are tested.
 * The rail holds two 48 dp buttons: move the keys to the other side, and full width.
 */
object OneHandedRules {
    /** The rail beside the keys: one 48 dp button wide, with a little room. */
    const val RAIL_DP = 56

    /** The keys never get wider than this, so on a tablet or in landscape they still reach one thumb. */
    const val MAX_KEYS_DP = 400

    /** Below this width the keys would be too small to aim at, so one-handed stays off. */
    const val MIN_WIDTH_DP = 320

    /** Whether one-handed narrows the keys in a window [widthDp] wide. */
    fun applies(mode: OneHanded, widthDp: Int): Boolean = mode != OneHanded.OFF && widthDp >= MIN_WIDTH_DP

    /** The keys' width in dp; the rail takes the rest. [widthDp] when one-handed doesn't apply. */
    fun keysDp(mode: OneHanded, widthDp: Int): Int =
        if (!applies(mode, widthDp)) widthDp else minOf(widthDp - RAIL_DP, MAX_KEYS_DP)

    /** The tool: off turns on toward the right, the thumb most people use; on turns off. */
    fun toggled(mode: OneHanded): OneHanded = if (mode == OneHanded.OFF) OneHanded.RIGHT else OneHanded.OFF

    /** The rail's move button: the keys go to the other side. */
    fun swapped(mode: OneHanded): OneHanded = when (mode) {
        OneHanded.LEFT -> OneHanded.RIGHT
        OneHanded.RIGHT -> OneHanded.LEFT
        OneHanded.OFF -> OneHanded.OFF
    }
}
