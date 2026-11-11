package dev.localtype.keyboard

import kotlin.math.sqrt

/** Where a finger landed, in key units: x in key widths from the row's left edge, y in rows from the top. */
data class Touch(val x: Float, val y: Float)

/**
 * Where each letter key sits in a letters layout, in key widths across and rows down, so a slip onto
 * the key beside or below costs about the same. [fromRows] places a language's letter rows as the
 * keyboards draw them, so a layout with other letter keys (Spanish ñ, later Swedish å ä ö) brings its
 * own geometry; the Android keyboard checks it against the drawn layout (LetterGeometry.from).
 */
class LetterGeometry private constructor(private val centers: Map<Char, Touch>) {
    /** The letter keys, in character order: the order tap offsets are kept in. */
    val keys: List<Char> = centers.keys.sorted()

    fun center(letter: Char): Touch? = centers[letter]

    /** A touch [dx] key widths right and [dy] rows below the center of [letter]'s key. */
    fun touchOn(letter: Char, dx: Float, dy: Float): Touch? = centers[letter]?.let { Touch(it.x + dx, it.y + dy) }

    /** The letter key whose center is closest to [touch]. */
    fun nearest(touch: Touch): Char = centers.minBy { (_, c) -> squaredDistance(touch, c) }.key

    /** Squared distance from [touch] to the center of [letter]'s key, or null for a non-letter. */
    fun squaredDistance(touch: Touch, letter: Char): Float? = centers[letter]?.let { squaredDistance(touch, it) }

    fun distance(a: Char, b: Char): Float? {
        val ca = centers[a] ?: return null
        val cb = centers[b] ?: return null
        return sqrt(squaredDistance(ca, cb))
    }

    override fun equals(other: Any?): Boolean = other is LetterGeometry && other.centers == centers

    override fun hashCode(): Int = centers.hashCode()

    companion object {
        /**
         * The QWERTY letter grid as the keyboard draws it: the home row is indented half a key and
         * the bottom row a key and a half (after Shift). `LanguageEngineTest` checks that this is
         * what the Android keyboard reads off its English letters layout.
         */
        val QWERTY: LetterGeometry = rows("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)

        /** Letter rows, top first, each with its indent in key widths. */
        fun rows(vararg rows: Pair<String, Float>): LetterGeometry = rows(1f, *rows)

        /**
         * Letter rows whose keys are [keyWidth] of a row unit wide, each row with its indent in keys. The centers are
         * summed key by key, the way [the drawn layout][LetterGeometry] is read back, so the two agree to the bit.
         */
        fun rows(keyWidth: Float, vararg rows: Pair<String, Float>): LetterGeometry = LetterGeometry(buildMap {
            rows.forEachIndexed { row, (letters, indent) ->
                var x = indent * keyWidth
                for (c in letters) {
                    put(c, Touch(x + keyWidth / 2f, row + 0.5f))
                    x += keyWidth
                }
            }
        })

        /** Exactly these key centers. */
        fun of(centers: Map<Char, Touch>): LetterGeometry = LetterGeometry(centers.toMap())

        /**
         * A language's letter rows, top first, placed as the letters layout draws them on a row of
         * [ROW_UNITS] keys: the top row from the left edge, a shorter home row centered, and the bottom
         * row after Shift, which takes half of what its letters leave.
         */
        fun fromRows(rows: List<String>, columns: Int = ROW_UNITS.toInt(), bottomFromLeft: Boolean = false): LetterGeometry =
            rows(ROW_UNITS / columns, *rows.mapIndexed { index, letters ->
                letters to if (index == 0 || (bottomFromLeft && index == rows.lastIndex)) 0f else (columns - letters.length) / 2f
            }.toTypedArray())

        const val ROW_UNITS = 10f
    }
}

/** File-level so the corrector's inner loop calls it directly, without a companion accessor. */
private fun squaredDistance(a: Touch, b: Touch): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}

/** The English QWERTY geometry, for the callers that only ever type English (swipe, the touch trace). */
object KeyGeometry {
    private val qwerty = LetterGeometry.QWERTY

    fun center(letter: Char): Touch? = qwerty.center(letter)

    fun touchOn(letter: Char, dx: Float, dy: Float): Touch? = qwerty.touchOn(letter, dx, dy)

    fun nearest(touch: Touch): Char = qwerty.nearest(touch)

    fun squaredDistance(touch: Touch, letter: Char): Float? = qwerty.squaredDistance(touch, letter)

    fun distance(a: Char, b: Char): Float? = qwerty.distance(a, b)
}
