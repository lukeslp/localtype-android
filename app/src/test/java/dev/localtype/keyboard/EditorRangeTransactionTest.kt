package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class EditorRangeTransactionTest {
    private class Editor(var text: String = "say teh next!", var start: Int = 12, var end: Int = start) {
        var commands = 0
        fun read() = VerifiedWordTransaction.Snapshot(text.substring(0, start), text.substring(end), EditorEditState.Selection(start, end),
            selected = text.substring(start, end).takeIf { start != end })
        fun select(a: Int, b: Int): Boolean { commands++; start = a; end = b; return true }
        fun selected() = EditorRangeTransaction.SelectionRead(start, end, text.substring(start, end))
        fun commit(s: String, position: Int): Boolean {
            commands++; text = text.substring(0, start) + s + text.substring(end)
            start += s.length + position - 1; end = start; return true
        }
        fun range(a: Int, b: Int, s: String, pos: Int): Boolean { select(a, b); return commit(s, pos) }
    }
    private val edit = RecentWordCorrection.RangeEdit(4, 7, "there", 14)
    @Test fun directAndExplicitCompatibilityPreserveFollowingText() {
        for (direct in listOf(true, false)) {
            val e = Editor()
            assertTrue(EditorRangeTransaction.apply(edit, e.read(), direct, e::read, e::range, e::select, e::selected, e::commit))
            assertEquals("say there next!", e.text); assertEquals(14, e.start)
        }
    }
    @Test fun acceptedIgnoredRangeIsNotSuccessAndNotRetried() {
        val e = Editor()
        assertFalse(EditorRangeTransaction.apply(edit, e.read(), true, e::read,
            { _, _, _, _ -> e.commands++; true }, e::select, e::selected, e::commit))
        assertEquals(1, e.commands); assertEquals("say teh next!", e.text)
    }
    @Test fun ignoredOrRejectedSelectionNeverCommits() {
        for (sent in listOf(true, false)) {
            val e = Editor()
            assertFalse(EditorRangeTransaction.apply(edit, e.read(), false, e::read, e::range,
                { _, _ -> e.commands++; sent }, e::selected, e::commit))
            assertEquals(if (sent) 2 else 1, e.commands); assertEquals("say teh next!", e.text)
        }
    }
    @Test fun ignoredCompatibilityCommitRestoresCaretBeforeFurtherTyping() {
        val e = Editor()
        assertFalse(EditorRangeTransaction.apply(edit, e.read(), false, e::read, e::range, e::select, e::selected, { _, _ -> true }))
        assertEquals("say teh next!", e.text); assertEquals(12, e.start); assertEquals(12, e.end)
        e.commit("x", 1); assertEquals("say teh nextx!", e.text)
    }
    @Test fun stalePostconditionCannotClaimSuccess() {
        val e = Editor(); val stale = e.read()
        assertFalse(EditorRangeTransaction.apply(edit, stale, true, { stale }, e::range, e::select, e::selected, e::commit))
    }
    @Test fun emptyLeftReadCannotVerifyATruncatedReplacement() {
        val e = Editor(); val initial = e.read().copy(beforeComplete = false)
        assertFalse(EditorRangeTransaction.apply(edit, initial, true,
            { e.read().copy(before = "", beforeComplete = false) }, e::range, e::select, e::selected, e::commit))
    }
}
