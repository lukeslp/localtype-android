package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupRowsTest {
    /** Approximate widths in dp at 16sp with 14dp padding each side, as SuggestionPopup draws the language chooser. */
    private val english = 82
    private val espanol = 90
    private val portugues = 108
    private val ipa = 58
    private val other = 137

    @Test
    fun everyLanguageAndOtherKeyboardsFitWholeOnAPhone() {
        // A 411dp phone less the popup's margins and padding: the five names need about 475dp, so one row squeezed them.
        val rows = PopupRows.pack(listOf(english, espanol, portugues, ipa, other), 411 - 16)
        assertEquals(listOf(0..3, 4..4), rows)
    }

    @Test
    fun aNarrowScreenOrLargeTextUsesMoreRows() {
        assertEquals(listOf(0..2, 3..4), PopupRows.pack(listOf(english, espanol, portugues, ipa, other), 320 - 16))
        val doubled = listOf(english, espanol, portugues, ipa, other).map { it * 2 }
        assertEquals(listOf(0..1, 2..3, 4..4), PopupRows.pack(doubled, 411 - 16))
    }

    @Test
    fun whatFitsStaysOneRowAsBefore() {
        // Six skin tones at 46dp, and two languages with Other keyboards.
        assertEquals(listOf(0..5), PopupRows.pack(List(6) { 46 }, 411 - 16))
        assertEquals(listOf(0..2), PopupRows.pack(listOf(english, espanol, other), 411 - 16))
    }

    @Test
    fun anOptionWiderThanTheScreenGetsItsOwnRow() {
        assertEquals(listOf(0..0, 1..1, 2..2), PopupRows.pack(listOf(50, 500, 50), 300))
        assertEquals(emptyList<IntRange>(), PopupRows.pack(emptyList(), 300))
    }
}
