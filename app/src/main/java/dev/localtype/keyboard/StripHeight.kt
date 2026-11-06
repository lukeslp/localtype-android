package dev.localtype.keyboard

/**
 * The suggestion strip's height and the largest size its words take. Standard is the 48 dp strip; Taller and
 * Tallest give a bigger target and larger words for people who asked for more room above the keys. A word still
 * shrinks to [MIN_WORD_SP] before it is cut.
 */
enum class StripHeight(val dp: Int, val wordSp: Int) {
    STANDARD(48, 17), TALLER(56, 19), TALLEST(64, 21);

    companion object {
        const val MIN_WORD_SP = 12
        fun of(name: String?): StripHeight = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
