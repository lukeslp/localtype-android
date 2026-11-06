package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class ProofreadControlsTest {
    private fun receipt(text: String, start: Int = text.length, end: Int = start) =
        VerifiedWordTransaction.window(text, 0, start, end, ProofreadRequest.LIMIT)!!

    @Test fun selectionPreservesSurroundingsWhitespaceAndReversedUtf16Offsets() {
        val text = "🙂 before\n  thjs is fine  \nfollowing"
        val start = text.indexOf("  thjs")
        val end = text.indexOf("\nfollowing")
        for (s in listOf(receipt(text, start, end), receipt(text, end, start))) {
            val request = ProofreadRequest.capture(7, s)!!
            assertTrue(request.selected)
            assertEquals("thjs is fine", request.original)
            assertEquals("  thjs is fine  ", request.replaced)
            assertEquals(WordEdits.Replacement(0, 0, "  This is fine.  "), request.replacement("This is fine."))
            assertTrue(request.matches(7, s))
            assertFalse(request.matches(8, s))
            assertFalse(request.matches(7, s.copy(after = "changed")))
            assertFalse(request.matches(7, s.copy(selected = "other words")))
            assertFalse(request.matches(7, s.copy(selection = EditorEditState.Selection(end, end))))
        }
        assertTrue(ProofreadRequest.sentenceSelection("thjs is fine"))
        assertFalse(ProofreadRequest.sentenceSelection("doesnt"))
        assertFalse(ProofreadRequest.sentenceSelection("🙂 👋"))
        assertFalse(ProofreadRequest.sentenceSelection("long ".repeat(121)))
    }

    @Test fun caretRequestIncludesVerifiedPeriodButNotPreviousSentence() {
        val s = receipt("Earlier. thjs is fine. \n")
        val r = ProofreadRequest.capture(1, s)!!
        assertFalse(r.selected)
        assertEquals("thjs is fine.", r.original)
        assertEquals(" \n", r.trailing)
        assertEquals(WordEdits.Replacement("thjs is fine. \n".length, 0, "This is fine. \n"), r.replacement("This is fine."))
        assertNull(ProofreadRequest.capture(1, null))
        assertNull(ProofreadRequest.capture(1, receipt("no punctuation").copy(beforeComplete = false)))
        assertNotNull(ProofreadRequest.capture(1, s.copy(beforeComplete = false)))
        assertNull(ProofreadRequest.capture(1, receipt("🙂")))
    }

    private class Editor(var text: String, var start: Int, var end: Int = start) {
        var commands = 0
        var ignore = false
        var reject = false
        fun read() = VerifiedWordTransaction.window(text, 0, start, end, ProofreadRequest.LIMIT)!!
        fun select(a: Int, b: Int): Boolean { start = a; end = b; return true }
        fun commit(value: String): Boolean {
            commands++
            if (reject) return false
            if (!ignore) {
                val a = minOf(start, end); val b = maxOf(start, end)
                text = text.substring(0, a) + value + text.substring(b)
                start = a + value.length; end = start
            }
            return true
        }
        fun range(a: Int, b: Int, value: String): Boolean {
            if (ignore || reject) { commands++; return !reject }
            select(a, b); return commit(value)
        }
        fun apply(edit: WordEdits.Replacement, direct: Boolean, verified: () -> Unit = {}) =
            VerifiedWordTransaction.apply(edit, read(), direct, ::read, ::range, ::select, ::commit, verified = { verified() })
    }

    @Test fun selectedAndCaretProposalsAndUndoPreserveFollowingTextOnBothEditorPaths() {
        for (direct in listOf(false, true)) for (selected in listOf(false, true)) {
            val text = "🙂 Before. thjs is fine. next text"
            val start = text.indexOf("thjs"); val end = text.indexOf(" next")
            val e = Editor(text, if (selected) start else end, end)
            val r = ProofreadRequest.capture(1, e.read())!!
            val edit = r.replacement("This is fine.")
            var verified = 0
            assertEquals(edit, e.apply(edit, direct) { verified++ })
            assertEquals("🙂 Before. This is fine. next text", e.text)
            assertEquals(start + "This is fine.".length, e.start)
            assertEquals(1, verified)
            val undo = WordEdits.Replacement(edit.text.length, 0, r.replaced)
            assertEquals(undo, e.apply(undo, direct))
            assertEquals(text, e.text)
        }
    }

    @Test fun ignoredAndRejectedProposalsNeverDeleteRetryOrCertifySuccess() {
        for (direct in listOf(false, true)) for (reject in listOf(false, true)) {
            val text = "before thjs is fine after"
            val e = Editor(text, 7, 19).apply { this.reject = reject; ignore = !reject }
            val initial = e.read()
            val r = ProofreadRequest.capture(1, initial)!!
            var verified = 0
            assertNull(e.apply(r.replacement("This is fine"), direct) { verified++ })
            assertEquals(text, e.text)
            assertEquals(initial.selection, e.read().selection)
            assertEquals(1, e.commands)
            assertEquals(0, verified)
        }
    }

    @Test fun batchDeferredDeliveryIsFlushedOnceBeforeVerifyingWithoutRetry() {
        val e = Editor("this is fine ", 13)
        val initial = e.read()
        val edit = WordEdits.Replacement(1, 0, ". ")
        var pending: (() -> Boolean)? = null
        var flushes = 0
        assertEquals(edit, VerifiedWordTransaction.apply(edit, initial, true, e::read,
            { a, b, text -> pending = { e.range(a, b, text) }; true }, e::select, e::commit,
            finishBatch = { flushes++; pending?.invoke() }))
        assertEquals("this is fine. ", e.text)
        assertEquals(1, e.commands)
        assertEquals(1, flushes)
    }

    @Test fun threePhysicalSpacesRequireTwoVerifiedEditsInOneUnchangedField() {
        val g = ProofreadSpaceGesture()
        val raw = receipt("thjs is fine")
        val first = receipt("thjs is fine ")
        val period = receipt("thjs is fine. ")
        assertFalse(g.consume(100, 1, raw))
        g.committed(100, 1, raw, first, false, false)
        assertFalse(g.consume(200, 1, first))
        g.committed(200, 1, first, period, true, false)
        assertTrue(g.consume(300, 1, period))
        assertFalse(g.consume(301, 1, period))
    }

    @Test fun automaticSpacesTimeoutsResetsMissingReadsAndStaleFieldsNeverTrigger() {
        val raw = receipt("thjs is fine")
        val first = receipt("thjs is fine ")
        val period = receipt("thjs is fine. ")
        fun armed() = ProofreadSpaceGesture().apply {
            committed(100, 1, raw, first, false, false)
            committed(200, 1, first, period, true, false)
        }
        assertFalse(armed().consume(900, 1, period))
        assertFalse(armed().consume(199, 1, period))
        assertFalse(armed().consume(300, 2, period))
        assertFalse(armed().consume(300, 1, receipt("changed. ")))
        assertFalse(armed().consume(300, 1, null))
        assertFalse(armed().apply { reset() }.consume(300, 1, period))
        val auto = ProofreadSpaceGesture().apply { committed(200, 1, first, period, true, true) }
        assertFalse(auto.consume(300, 1, period))
        val missed = ProofreadSpaceGesture().apply {
            committed(100, 1, raw, null, false, false)
            committed(200, 1, first, period, true, false)
        }
        assertFalse(missed.consume(300, 1, period))
        val slow = ProofreadSpaceGesture().apply {
            committed(100, 1, raw, first, false, false)
            committed(800, 1, first, period, true, false)
        }
        assertFalse(slow.consume(850, 1, period))
    }
}
