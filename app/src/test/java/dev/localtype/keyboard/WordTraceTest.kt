package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTraceTest {
    private val t = Touch(1f, 1f)

    private fun type(trace: WordTrace, word: String) {
        word.forEachIndexed { i, _ -> trace.letter(word.substring(0, i), t) }
    }

    @Test
    fun `a word typed from its first letter keeps one touch per letter`() {
        val trace = WordTrace()
        type(trace, "woklr")
        assertFalse(trace.revisited)
        assertEquals(5, trace.touchesFor("woklr")?.size)
    }

    @Test
    fun `fixing the word while still typing it is not a revisit`() {
        val trace = WordTrace()
        type(trace, "wokl")
        trace.backspace("wok")
        trace.letter("wok", t)
        assertFalse(trace.revisited)
        assertEquals(4, trace.touchesFor("wokx")?.size)
    }

    @Test
    fun `backspacing into a finished word is a revisit`() {
        val trace = WordTrace()
        type(trace, "would")
        trace.finish() // space
        trace.backspace("would") // deleted the space
        assertTrue(trace.revisited)
        trace.backspace("woul")
        trace.letter("woul", t)
        assertTrue(trace.revisited)
        assertNull(trace.touchesFor("would"))
    }

    @Test
    fun `undo or a cursor move marks the word as revisited until a new word starts`() {
        val trace = WordTrace()
        trace.revisit()
        trace.letter("woklr", t)
        assertTrue(trace.revisited)
        trace.finish()
        trace.letter("", t)
        assertFalse(trace.revisited)
    }

    @Test
    fun `deleting a whole word starts fresh`() {
        val trace = WordTrace()
        trace.revisit()
        trace.backspace("")
        assertFalse(trace.revisited)
    }

    @Test
    fun `touchCount and hasTouchesFor agree with touchesFor`() {
        val trace = WordTrace()
        fun check(prefix: String) {
            val touches = trace.touchesFor(prefix)
            assertEquals(prefix, touches != null, trace.hasTouchesFor(prefix))
            if (touches != null) assertEquals(prefix, touches.size, trace.touchCount)
        }

        check("")
        type(trace, "woklr")
        assertEquals(5, trace.touchCount)
        check("woklr")
        check("wokl")
        trace.backspace("wokl")
        assertEquals(4, trace.touchCount)
        check("wokl")
        check("woklr")
        trace.finish()
        assertEquals(0, trace.touchCount)
        check("")
        check("woklr")
        type(trace, "would")
        trace.finish()
        trace.backspace("would")
        check("would")
        trace.revisit()
        trace.letter("woklr", t)
        assertEquals(1, trace.touchCount)
        check("woklra")
        check("a")
    }
}
