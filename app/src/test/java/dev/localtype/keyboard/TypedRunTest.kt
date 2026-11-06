package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedRunTest {
    /** Types [text] one character at a time after [before], as the keyboard appends it. */
    private fun type(run: TypedRun, before: String, text: String): String {
        var field = before
        for (c in text) {
            run.appended(field, c.toString())
            field += c
        }
        return field
    }

    @Test
    fun `a run typed from the start of the field or after a space is whole`() {
        val run = TypedRun()
        var field = type(run, "", "datapoems.io")
        assertEquals("datapoems.io", run.current(field))
        field = type(run, field, " see lukesteuber.com")
        assertEquals("lukesteuber.com", run.current(field))
    }

    @Test
    fun `text that was already there is not the keyboard's`() {
        val run = TypedRun()
        // The field held "data" before any key: a name the app filled in, or one pasted by its own menu.
        val field = type(run, "data", "poems.io")
        assertNull(run.current(field))
        // The next word, after a typed space, is clean again.
        assertEquals("example.com", run.current(type(run, "$field ", "example.com")))
    }

    @Test
    fun `a paste, a strip tap or a cursor move gives the run up until the next space`() {
        val run = TypedRun()
        var field = type(run, "", "data")
        run.reset()
        field = type(run, field + "poems", ".io")
        assertNull(run.current(field))
        field = type(run, field, " again.org")
        assertEquals("again.org", run.current(field))
    }

    @Test
    fun `Backspace inside the run keeps it whole`() {
        val run = TypedRun()
        var field = type(run, "go to ", "datapeo")
        run.deleted(field, 2)
        field = field.dropLast(2)
        field = type(run, field, "oems.io")
        assertEquals("datapoems.io", run.current(field))
        // Deleting past its start gives it up.
        run.deleted(field, 40)
        assertNull(run.current("go "))
    }

    @Test
    fun `Backspace somewhere else in the field gives the run up`() {
        val run = TypedRun()
        val field = type(run, "", "example.com")
        // The caret was moved back into the word, so the press took a letter the run does not end with.
        run.deleted("exa", 1)
        assertNull(run.current(field))
        assertNull(run.current("ex"))
        val whole = TypedRun()
        type(whole, "", "ab")
        whole.deleted("ab", 2)
        // Nothing of it is left; the next letter starts a new run at the start of the field.
        whole.appended("", "c")
        assertEquals("c", whole.current("c"))
        val none = TypedRun()
        type(none, "", "ab")
        none.deleted("ab", 0)
        assertNull(none.current("ab"))
    }

    @Test
    fun `the caret somewhere else is not the end of the run`() {
        val run = TypedRun()
        type(run, "", "example.com")
        assertNull(run.current("another example.org"))
        assertNull(run.current("xexample.com"))
        assertNull(run.current(""))
        assertEquals("example.com", run.current("then example.com"))
    }

    @Test
    fun `an append that could not be read, or a run too long to be a host, is given up`() {
        val run = TypedRun()
        type(run, "", "example")
        run.appended(null, ".")
        assertNull(run.current("example."))
        val long = TypedRun()
        val field = type(long, "", "a".repeat(261))
        assertNull(long.current(field))
    }

    @Test
    fun `several characters at once, with white space among them, start the run after the last of it`() {
        val run = TypedRun()
        run.appended("", "see example")
        assertEquals("example", run.current("see example"))
        run.appended("see example", ". ")
        assertNull(run.current("see example. "))
        run.appended("see example. ", "x")
        assertEquals("x", run.current("see example. x"))
    }
}
