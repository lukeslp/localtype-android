package dev.localtype.keyboard

/** Keys on a wide window: centered at [KeyWidthRules.MAX_KEYS_DP], or across the whole window. Read once per field. */
enum class WideKeys {
    CENTERED, FULL_WIDTH;

    companion object {
        fun of(name: String?): WideKeys = entries.firstOrNull { it.name == name } ?: CENTERED
    }
}

/**
 * How wide the keys are and where they sit: one-handed toward an edge ([OneHandedRules]), centered at a maximum width on
 * a wide window, or the full width. Pure, so the sizes are tested. Only the keys' view gets narrower: its geometry stays
 * in key widths, so `LetterGeometry`, the touch offsets and correction costs read the same on every width.
 */
object KeyWidthRules {
    /** The start of the Medium width class: tablets, unfolded Folds and desktop windows. Phones in portrait are narrower. */
    const val WIDE_WINDOW_DP = 600

    /**
     * The widest the keys get on a wide window. Ten letter cells share it less 6 dp of side padding, so each is about
     * 59 dp, 9.4 mm at Android's nominal 160 dp to the inch: the 9.6 mm Parhi, Karlson and Bederson (MobileHCI 2006)
     * found big enough for thumb typing, against 90 to 120 dp keys across a Fold or a tablet. It equals
     * [WIDE_WINDOW_DP], so a window resized across that edge never makes the keys jump.
     */
    const val MAX_KEYS_DP = 600

    enum class Side { FULL, CENTER, LEFT, RIGHT }

    /** Keys [widthDp] wide at [side]; [rail] when one-handed's rail fills the space they leave. */
    data class Placement(val widthDp: Int, val side: Side, val rail: Boolean)

    /**
     * Wide enough for the cap, and not a phone on its side: a window under [TypingRules.COMPACT_HEIGHT_DP] tall is the
     * one where the keyboard takes the whole screen ([TypingRules.fullscreen]) and the thumbs hold its ends, so its keys
     * keep the full width. An unknown height (0) changes nothing.
     */
    fun wideWindow(widthDp: Int, heightDp: Int): Boolean =
        widthDp >= WIDE_WINDOW_DP && heightDp >= TypingRules.COMPACT_HEIGHT_DP

    /** Where the keys go in a window [widthDp] by [heightDp]. One-handed, when it applies, wins over the cap. */
    fun place(oneHanded: OneHanded, wide: WideKeys, widthDp: Int, heightDp: Int): Placement = when {
        OneHandedRules.applies(oneHanded, widthDp) -> Placement(OneHandedRules.keysDp(oneHanded, widthDp),
            if (oneHanded == OneHanded.LEFT) Side.LEFT else Side.RIGHT, rail = true)
        wide == WideKeys.CENTERED && wideWindow(widthDp, heightDp) && widthDp > MAX_KEYS_DP ->
            Placement(MAX_KEYS_DP, Side.CENTER, rail = false)
        else -> Placement(widthDp, Side.FULL, rail = false)
    }
}
