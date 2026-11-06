package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorGesturesTest {
    private val key = 100f

    @Test fun `a press becomes a slide only after flat travel past the start zone`() {
        assertFalse(CursorGestures.starts(40f, 0f, key))
        assertTrue(CursorGestures.starts(61f, 0f, key))
        assertTrue(CursorGestures.starts(-75f, 20f, key))
        // A finger leaving upward for the letters is not a slide.
        assertFalse(CursorGestures.starts(70f, 60f, key))
        assertFalse(CursorGestures.starts(70f, 0f, 0f))
    }

    @Test fun `steps count from the start zone and back down again`() {
        val step = 0.3f
        assertEquals(0, CursorGestures.steps(59f, key, step))
        assertEquals(1, CursorGestures.steps(61f, key, step))
        assertEquals(1, CursorGestures.steps(89f, key, step))
        assertEquals(2, CursorGestures.steps(91f, key, step))
        assertEquals(-3, CursorGestures.steps(-125f, key, step))
        assertEquals(0, CursorGestures.steps(-10f, key, step))
        assertEquals(0, CursorGestures.steps(200f, 0f, step))
    }

    @Test fun `delete leaves a wide zone for just the last word and the caret steps quickly`() {
        val standard = CursorGestures.Speed.STANDARD
        // One word from the start zone (0.6 key widths) until 1.45 widths left of delete: wider than a key, not half of one.
        assertEquals(-1, CursorGestures.steps(-61f, key, standard.wordStep))
        assertEquals(-1, CursorGestures.steps(-144f, key, standard.wordStep))
        assertEquals(-2, CursorGestures.steps(-146f, key, standard.wordStep))
        // The caret: a character per fifth of a key width once the slide has started.
        assertEquals(5, CursorGestures.steps(141f, key, standard.caretStep))
    }

    @Test fun `the slide speed setting scales both steps and never the start zone`() {
        val slower = CursorGestures.Speed.SLOWER
        val faster = CursorGestures.Speed.FASTER
        assertTrue(slower.caretStep > CursorGestures.Speed.STANDARD.caretStep && faster.caretStep < CursorGestures.Speed.STANDARD.caretStep)
        assertTrue(slower.wordStep > CursorGestures.Speed.STANDARD.wordStep && faster.wordStep < CursorGestures.Speed.STANDARD.wordStep)
        for (speed in CursorGestures.Speed.entries) {
            assertEquals(0, CursorGestures.steps(59f, key, speed.caretStep))
            assertEquals(1, CursorGestures.steps(61f, key, speed.caretStep))
        }
        assertTrue(CursorGestures.steps(300f, key, faster.caretStep) > CursorGestures.steps(300f, key, slower.caretStep))
        // A missing or unknown saved value is the standard speed.
        assertEquals(CursorGestures.Speed.STANDARD, CursorGestures.Speed.of(null))
        assertEquals(CursorGestures.Speed.STANDARD, CursorGestures.Speed.of("SIDEWAYS"))
        assertEquals(slower, CursorGestures.Speed.of("SLOWER"))
    }

    @Test fun `the caret moves by whole characters and stops at the ends`() {
        assertEquals(-2, CursorGestures.caretOffset("hello", " there", -2))
        assertEquals(3, CursorGestures.caretOffset("hello", " there", 3))
        assertEquals(-5, CursorGestures.caretOffset("hello", "", -9))
        assertEquals(6, CursorGestures.caretOffset("", " there", 9))
        assertEquals(0, CursorGestures.caretOffset("hello", " there", 0))
        assertEquals(0, CursorGestures.caretOffset("", "", -1))
    }

    @Test fun `an emoji or an accented letter is one character in either direction`() {
        val family = "👨‍👩‍👧"
        val accented = "é"
        assertEquals(-family.length, CursorGestures.caretOffset("hi $family", "", -1))
        assertEquals(family.length, CursorGestures.caretOffset("", "$family hi", 1))
        assertEquals(family.length + 1, CursorGestures.caretOffset("", "$family hi", 2))
        assertEquals(-accented.length, CursorGestures.caretOffset("caf$accented", "", -1))
        assertEquals(accented.length + 1, CursorGestures.caretOffset("", "${accented}x", 2))
        assertEquals(-2, CursorGestures.caretOffset("ok 😀", "", -1))
    }

    @Test fun `a selection collapses first and that uses up one step`() {
        // "abcd" with "bc" selected: the text before the selection is "a", after it "d".
        assertEquals(1, CursorGestures.caretTarget(1, 3, "a", "d", -1))
        assertEquals(3, CursorGestures.caretTarget(1, 3, "a", "d", 1))
        assertEquals(0, CursorGestures.caretTarget(1, 3, "a", "d", -2))
        assertEquals(4, CursorGestures.caretTarget(3, 1, "a", "d", 2))
        assertEquals(2, CursorGestures.caretTarget(4, 4, "abcd", "", -2))
        assertEquals(4, CursorGestures.caretTarget(4, 4, "abcd", "", 3))
    }

    @Test fun `words go with what follows them and leave the space before`() {
        assertEquals("tomorrow".length, CursorGestures.wordsLength("see you tomorrow", 1))
        assertEquals("you tomorrow".length, CursorGestures.wordsLength("see you tomorrow", 2))
        assertEquals("see you tomorrow".length, CursorGestures.wordsLength("see you tomorrow", 9))
        assertEquals("tomorrow. ".length, CursorGestures.wordsLength("see you tomorrow. ", 1))
        assertEquals("don't".length, CursorGestures.wordsLength("I don't", 1))
        assertEquals("snake_case".length, CursorGestures.wordsLength("a snake_case", 1))
        assertEquals(0, CursorGestures.wordsLength("see you", 0))
        assertEquals(0, CursorGestures.wordsLength("", 3))
    }

    @Test fun `punctuation and emoji standing alone count as a word`() {
        assertEquals(":)".length, CursorGestures.wordsLength("ok :)", 1))
        assertEquals("ok :)".length, CursorGestures.wordsLength("ok :)", 2))
        assertEquals("😀".length, CursorGestures.wordsLength("ok 😀", 1))
        assertEquals("line\n".length, CursorGestures.wordsLength("first line\n", 1))
        assertEquals("datapoems.io".length, CursorGestures.wordsLength("see datapoems.io", 2))
    }
}
