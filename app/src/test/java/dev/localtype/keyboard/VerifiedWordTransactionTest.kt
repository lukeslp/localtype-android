package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class VerifiedWordTransactionTest {
    private class Editor(var text: String = "say teh!") {
        var start = 7; var end = 7; var commits = 0; var ranges = 0; var selections = 0
        fun read() = VerifiedWordTransaction.Snapshot(text.substring(0, start), text.substring(end),
            EditorEditState.Selection(start, end), selected = text.substring(start, end).takeIf { start != end })
        fun select(a: Int, b: Int): Boolean { selections++; start = a; end = b; return true }
        fun commit(value: String): Boolean {
            commits++; text = text.substring(0, start) + value + text.substring(end)
            start += value.length; end = start; return true
        }
        fun range(a: Int, b: Int, value: String): Boolean { ranges++; start = a; end = b; return commit(value) }
    }
    private val edit = WordEdits.Replacement(3, 0, "the ")
    @Test fun externalInsertionVerifiesSelectionUnicodeAndFollowingTextOnce() {
        for (value in listOf("teh", "first\nsecond", "👨‍👩‍👧", "friend’s", "x".repeat(5000))) {
            val e = Editor("say old suffix").apply { start = 4; end = 7 }
            assertEquals(VerifiedWordTransaction.Insertion.APPLIED,
                VerifiedWordTransaction.insert(value, e.read(), e::commit, e::read))
            assertEquals("say $value suffix", e.text)
            assertEquals(4 + value.length, e.start); assertEquals(e.start, e.end)
            assertEquals(1, e.commits)
        }
    }
    @Test fun refusedIgnoredAndAmbiguousInsertionsNeverRetryOrClaimSuccess() {
        val e = Editor(); val original = e.read()
        assertEquals(VerifiedWordTransaction.Insertion.REJECTED,
            VerifiedWordTransaction.insert("🙂", original, { e.commits++; false }, e::read))
        assertEquals(VerifiedWordTransaction.Insertion.UNCHANGED,
            VerifiedWordTransaction.insert("🙂", original, { e.commits++; true }, e::read))
        assertEquals(VerifiedWordTransaction.Insertion.UNVERIFIED,
            VerifiedWordTransaction.insert("🙂", original, e::commit, { null }))
        assertEquals(3, e.commits); assertEquals("say teh🙂!", e.text)
    }
    @Test fun aShortOrChangedReceiptCannotCertifyTheWholePaste() {
        val e = Editor(); val initial = e.read().copy(beforeComplete = false)
        assertEquals(VerifiedWordTransaction.Insertion.UNVERIFIED,
            VerifiedWordTransaction.insert("abcabc", initial, e::commit,
                { e.read().copy(before = "abc", beforeComplete = false) }))
        val f = Editor(); val original = f.read()
        assertEquals(VerifiedWordTransaction.Insertion.UNVERIFIED,
            VerifiedWordTransaction.insert("text", original, f::commit,
                { f.read().copy(after = "different") }))
        val missing = Editor()
        assertEquals(VerifiedWordTransaction.Insertion.UNVERIFIED,
            VerifiedWordTransaction.insert("text", null, missing::commit, missing::read))
        assertEquals(1, missing.commits)
    }
    @Test fun coherentExtractedWindowCarriesAbsoluteSelectionAndBothAnchors() {
        val s = VerifiedWordTransaction.window("say teh suffix", 100, 7, 7, 5)!!
        assertEquals("y teh", s.before); assertEquals(" suff", s.after)
        assertEquals(EditorEditState.Selection(107, 107), s.selection); assertFalse(s.beforeComplete)
        assertNull(VerifiedWordTransaction.window(null, 0, 0, 0, 280))
        assertNull(VerifiedWordTransaction.window("teh", 0, 4, 4, 280))
        assertNull(VerifiedWordTransaction.window("teh", Int.MAX_VALUE, 3, 3, 280))
        assertEquals("teh", VerifiedWordTransaction.window("say teh suffix", 0, 4, 7, 280)!!.selected)
    }
    @Test fun directAndCompatibilityReplaceWithoutADeletionGap() {
        for (direct in listOf(true, false)) {
            val e = Editor()
            assertEquals(edit, VerifiedWordTransaction.apply(edit, e.read(), direct, e::read, e::range, e::select, e::commit))
            assertEquals("say the !", e.text); assertEquals(8, e.start); assertEquals(1, e.commits)
            assertEquals(if (direct) 1 else 0, e.ranges)
        }
    }
    @Test fun ignoredOrRejectedRangePreservesWordAndNeverRetries() {
        for (accepted in listOf(true, false)) {
            val e = Editor()
            assertNull(VerifiedWordTransaction.apply(edit, e.read(), true, e::read,
                { _, _, _ -> e.ranges++; accepted }, e::select, e::commit))
            assertEquals("say teh!", e.text); assertEquals(0, e.commits); assertEquals(1, e.ranges)
        }
    }
    @Test fun restartAfterAtomicReplacementDoesNotLoseTheWordOrClaimLearning() {
        val e = Editor()
        assertNull(VerifiedWordTransaction.apply(edit, e.read(), true, { null }, e::range, e::select, e::commit))
        assertEquals("say the !", e.text); assertEquals(1, e.ranges)
    }
    @Test fun restartDuringCompatibilitySelectionLeavesOriginalText() {
        val e = Editor()
        assertNull(VerifiedWordTransaction.apply(edit, e.read(), false, { null }, e::range, e::select, e::commit))
        assertEquals("say teh!", e.text); assertEquals(0, e.commits); assertEquals(7, e.start); assertEquals(7, e.end)
    }
    @Test fun ignoredSelectionNeverAppendsReplacement() {
        val e = Editor()
        assertNull(VerifiedWordTransaction.apply(edit, e.read(), false, e::read, e::range, { _, _ -> true }, e::commit))
        assertEquals("say teh!", e.text); assertEquals(0, e.commits)
    }
    @Test fun ignoredCompatibilityCommitRestoresCaretAndPreservesNextKey() {
        val e = Editor()
        assertNull(VerifiedWordTransaction.apply(edit, e.read(), false, e::read, e::range, e::select, { e.commits++; true }))
        assertEquals("say teh!", e.text); assertEquals(7, e.start); assertEquals(7, e.end)
        e.commit("x"); assertEquals("say tehx!", e.text)
    }
    @Test fun stalePostconditionAndShortenedWindowCannotClaimSuccess() {
        val e = Editor(); val initial = e.read()
        assertNull(VerifiedWordTransaction.apply(edit, initial, true, { initial }, e::range, e::select, e::commit))
        val f = Editor(); val truncated = f.read().copy(beforeComplete = false)
        assertNull(VerifiedWordTransaction.apply(edit, truncated, true,
            { f.read().copy(before = "", beforeComplete = false) }, f::range, f::select, f::commit))
    }
    @Test fun wholeWordRangeIncludesSuffixWithoutTouchingFollowingText() {
        val e = Editor("say tehh!"); val suffixEdit = WordEdits.Replacement(3, 1, "the")
        assertEquals(suffixEdit, VerifiedWordTransaction.apply(suffixEdit, e.read(), true, e::read, e::range, e::select, e::commit))
        assertEquals("say the!", e.text)
    }
    @Test fun compatibilitySelectionCanWidenTheBoundedLeftWindow() {
        val e = Editor("a".repeat(300) + " teh!").apply { start = 304; end = 304 }
        fun bounded() = VerifiedWordTransaction.window(e.text, 0, e.start, e.end, 280)!!
        val initial = bounded(); var receipts = 0
        assertEquals(edit, VerifiedWordTransaction.apply(edit, initial, false, ::bounded, e::range, e::select, e::commit,
            verified = { receipts++; assertEquals(bounded(), it) }))
        assertEquals("a".repeat(300) + " the !", e.text); assertEquals(1, receipts)
    }
    @Test fun ignoredLongCompatibilityCommitRestoresCaretWithoutCertifyingEffect() {
        val e = Editor("a".repeat(300) + " teh!").apply { start = 304; end = 304 }
        fun bounded() = VerifiedWordTransaction.window(e.text, 0, e.start, e.end, 280)!!
        var receipts = 0
        assertNull(VerifiedWordTransaction.apply(edit, bounded(), false, ::bounded, e::range, e::select, { true }, verified = { receipts++ }))
        assertEquals(304, e.start); assertEquals(304, e.end); assertEquals(0, receipts)
    }
    @Test fun selectedCompletionReusesSpaceAndVerifiesFollowingTextOnBothEditorPaths() {
        for (direct in listOf(false, true)) {
            val e = Editor("say doesnt next").apply { start = 4; end = 10 }
            val edit = WordEdits.suggestion(e.read().before, e.read().after, true, "doesn't", true, 280)!!.replacement
            assertEquals(edit, VerifiedWordTransaction.apply(edit, e.read(), direct, e::read, e::range, e::select, e::commit))
            assertEquals("say doesn't next", e.text)
            assertEquals(12, e.start)
            e.commit("word ")
            assertEquals("say doesn't word next", e.text)
        }
    }
    @Test fun ignoredSelectedReplacementDoesNotRetryOrCertifySuccess() {
        for (direct in listOf(false, true)) {
            val e = Editor("say doesnt next").apply { start = 4; end = 10 }
            val initial = e.read()
            val edit = WordEdits.Replacement(0, 1, "doesn't ")
            var attempts = 0
            assertNull(VerifiedWordTransaction.apply(edit, initial, direct, e::read,
                { _, _, _ -> attempts++; true }, e::select, { attempts++; true }))
            assertEquals(1, attempts)
            assertEquals("say doesnt next", e.text)
            assertEquals(4, e.start); assertEquals(10, e.end)
        }
    }

}
