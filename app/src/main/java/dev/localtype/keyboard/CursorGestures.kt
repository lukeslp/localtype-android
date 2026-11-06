package dev.localtype.keyboard

import kotlin.math.abs

/** Pure arithmetic for the space-bar caret slide and the delete-key word slide. */
object CursorGestures {
    /** Finger travel, in key widths, before a press on space or delete becomes a slide. */
    const val START_WIDTHS = 0.6f
    /** A resting thumb drifts; past this much travel a press on space is no longer a hold. */
    const val HOLD_SLOP_WIDTHS = 0.2f
    /** A finger holding delete to erase drifts less than this; one heading left past it has stopped holding. */
    const val DELETE_SLOP_WIDTHS = 0.3f
    /** One caret step per this much further travel along the space bar, at the standard slide speed. */
    const val CARET_STEP_WIDTHS = 0.2f
    /**
     * One more word per this much further travel left of delete, at the standard slide speed. Wider than a caret
     * step, so stopping after just the last word is easy: 0.55 until 2026-11 was a narrow zone to land in.
     */
    const val WORD_STEP_WIDTHS = 0.85f

    /** How far a finger travels for each caret step or word: the setting scales both step sizes, never the start zone. */
    enum class Speed(val scale: Float) {
        SLOWER(1.5f), STANDARD(1f), FASTER(0.7f);

        val caretStep get() = CARET_STEP_WIDTHS * scale
        val wordStep get() = WORD_STEP_WIDTHS * scale

        companion object {
            fun of(name: String?): Speed = entries.firstOrNull { it.name == name } ?: STANDARD
        }
    }

    /** True once a press has travelled far enough, and flatly enough, to be a slide rather than a wobble. */
    fun starts(dx: Float, dy: Float, keyWidth: Float): Boolean =
        keyWidth > 0f && abs(dx) >= START_WIDTHS * keyWidth && abs(dx) >= abs(dy) * 1.5f

    /**
     * What the slide stands for at [dx] from where the finger came down: 0 inside the start zone, then one for
     * reaching it and one more per [stepWidths] of travel, signed like [dx]. Sliding back counts back down.
     */
    fun steps(dx: Float, keyWidth: Float, stepWidths: Float): Int {
        if (keyWidth <= 0f || stepWidths <= 0f) return 0
        val beyond = abs(dx) - START_WIDTHS * keyWidth
        if (beyond < 0f) return 0
        val count = 1 + (beyond / (stepWidths * keyWidth)).toInt()
        return if (dx < 0f) -count else count
    }

    /**
     * How far the caret moves, in UTF-16 units, for [steps] characters ([steps] below zero is left). A whole emoji
     * or a letter with its accents is one character, and the move stops at either end of the text given.
     */
    fun caretOffset(before: String, after: String, steps: Int): Int {
        if (steps < 0) {
            var rest = before
            repeat(-steps) { rest = rest.dropLast(TypingRules.lastGraphemeLength(rest)) }
            return rest.length - before.length
        }
        if (steps == 0 || after.isEmpty()) return 0
        // Clusters are found from the end, so cut the text there and walk forward over their lengths.
        val lengths = ArrayList<Int>()
        var rest = after
        while (rest.isNotEmpty()) {
            val length = TypingRules.lastGraphemeLength(rest).coerceAtLeast(1)
            lengths += length
            rest = rest.dropLast(length)
        }
        return lengths.asReversed().take(steps).sum()
    }

    /**
     * Where the caret lands after [steps] characters from a selection of [start]..[end]. A selection first collapses
     * to the end the slide is heading for, as an arrow key does, and that uses up one step.
     */
    fun caretTarget(start: Int, end: Int, before: String, after: String, steps: Int): Int {
        val low = minOf(start, end)
        val high = maxOf(start, end)
        if (steps == 0) return high
        if (low == high) return low + caretOffset(before, after, steps)
        return if (steps < 0) low + caretOffset(before, after, steps + 1) else high + caretOffset(before, after, steps - 1)
    }

    /**
     * How many UTF-16 units before the caret [words] whole words take up. A word goes with the punctuation and
     * spaces after it, and leaves the space before it, so what remains is ready to type after.
     */
    fun wordsLength(before: String, words: Int): Int {
        var i = before.length
        fun codePoint() = Character.codePointBefore(before, i)
        repeat(words.coerceAtLeast(0)) {
            while (i > 0 && Character.isWhitespace(codePoint())) i -= Character.charCount(codePoint())
            // Punctuation, symbols and emoji attached to the word, or standing alone.
            while (i > 0 && !Character.isWhitespace(codePoint()) && !inWord(codePoint())) {
                i -= TypingRules.lastGraphemeLength(before.substring(0, i)).coerceAtLeast(1)
            }
            while (i > 0 && inWord(codePoint())) i -= Character.charCount(codePoint())
        }
        return before.length - i
    }

    private fun inWord(c: Int): Boolean = Character.isLetterOrDigit(c) || c == '\''.code || c == '’'.code || c == '_'.code ||
        when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
}
