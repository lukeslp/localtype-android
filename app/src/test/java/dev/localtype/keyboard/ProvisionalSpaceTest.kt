package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class ProvisionalSpaceTest {
    private fun boundary(before: String, value: String, provisional: Boolean, after: String? = "", selected: Boolean = false) =
        WordEdits.separator(before, after, selected, value, revisited = true,
            suggestions = true, automatic = true, readLimit = 280, autoSpaced = provisional) {
            error("a completed suggestion must not be corrected or learned again")
        }

    private fun apply(before: String, edit: WordEdits.Replacement) = before.dropLast(edit.before) + edit.text

    @Test fun `space confirms the separator after attached punctuation once`() {
        // The word sits inside an open quotation, so the straight mark in the list is one that closes it.
        val before = "\"hello "
        for (punctuation in listOf(",", "!", "?", ":", ";", "\"", "”")) {
            val attached = WordEdits.attachPunctuation(before, punctuation, true, false)!!
            val text = apply(before, attached)
            assertTrue(attached.autoSpaced)
            assertNull(WordEdits.doubleSpace(text.takeLast(2), true, false, true, autoSpaced = true))
            val confirmed = boundary(text, " ", attached.autoSpaced)
            assertEquals("\"hello$punctuation ", apply(text, confirmed.replacement))
            assertFalse(confirmed.learningEligible)
            assertFalse(confirmed.corrected)
            // Once confirmed, another deliberate space remains literal.
            assertEquals("\"hello$punctuation  ", apply(text, boundary(text, " ", false).replacement))
        }
    }

    @Test fun `newline removes just the provisional space and preserves following text`() {
        for (before in listOf("hello ", "hello, ", "hello! ")) {
            val edit = boundary(before, "\n", true, after = "next").replacement
            assertEquals(before.dropLast(1) + "\nnext", apply(before, edit) + "next")
            assertEquals(0, edit.after)
        }
    }

    @Test fun `manual whitespace selection and missing reads never consume a space`() {
        for (value in listOf(" ", "\n")) {
            assertEquals("hello, $value", apply("hello, ", boundary("hello, ", value, false).replacement))
            assertEquals(0, boundary("hello ", value, true, after = null).replacement.before)
            assertEquals(0, boundary("hello ", value, true, selected = true).replacement.before)
        }
    }

    @Test fun `completion double space period and domain apostrophe attachment retain their behavior`() {
        assertEquals("hello. ", apply("hello ", WordEdits.doubleSpace("hello ", true, false, true)!!))
        for ((value, expected) in listOf("." to "hello.", ".com" to "hello.com", "'" to "hello'", "’" to "hello’")) {
            val edit = WordEdits.attachPunctuation("hello ", value, true, false)!!
            assertEquals(expected, apply("hello ", edit))
            assertFalse(edit.autoSpaced)
        }
    }
}
