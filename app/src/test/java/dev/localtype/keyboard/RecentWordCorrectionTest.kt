package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class RecentWordCorrectionTest {
    private val id = RecentWordCorrection.Identity(3, 7, "policy1")
    private val word = RecentWordCorrection.Word(id, "teh", " ", 4, "say ", "!", 100)
    private fun snapshot(text: String, caret: Int = text.length) = VerifiedWordTransaction.Snapshot(text, "!", EditorEditState.Selection(caret, caret))
    @Test fun lateRepairPreservesFollowingWordAndCaret() {
        val s = RecentWordCorrection(); s.begin(word); s.append("ne", 130)
        assertEquals(RecentWordCorrection.RangeEdit(4, 7, "the", 10), s.repair(id, snapshot("say teh ne"), "the", 150))
        s.applied(word, "the", 150); s.append("xt", 200)
        assertEquals(RecentWordCorrection.RangeEdit(4, 7, "teh", 12), s.undoEdit(id, snapshot("say the next"), 300))
    }
    @Test fun lengthChangingRepairAndUndoPreserveSuffixAndUnicodeUnits() {
        val s = RecentWordCorrection(); s.begin(word); s.append("😀", 130)
        val edit = s.repair(id, snapshot("say teh 😀"), "there", 140)!!
        assertEquals(12, edit.caret)
        s.applied(word, "there", 140)
        val undo = s.undoEdit(id, snapshot("say there 😀"), 200)!!
        assertEquals(10, undo.caret); assertEquals(9, undo.end)
    }
    @Test fun staleIdentityAnchorsCaretAndDeadlineFailClosed() {
        for (kind in 0..4) {
            val s = RecentWordCorrection(); s.begin(word)
            val identity = if (kind == 0) id.copy(field = 4) else id
            val snapshot = when (kind) {
                1 -> snapshot("say tex ")
                2 -> snapshot("say teh ", 4)
                3 -> snapshot("say teh ").copy(after = "?")
                else -> snapshot("say teh ")
            }
            assertNull(s.repair(identity, snapshot, "the", if (kind == 4) 601 else 200))
            assertNull(s.pending)
        }
    }
    @Test fun nextBoundaryCancelsRepairButRetainsUndo() {
        val s = RecentWordCorrection(); s.begin(word); s.applied(word, "the", 120, "")
        s.begin(word); s.append("next ", 130, boundary = true)
        assertNull(s.pending)
        assertNotNull(s.undoEdit(id, snapshot("say the next "), 200))
    }
    @Test fun confirmedPeriodUndoPreservesSentencePunctuationAndFollowingText() {
        val s = RecentWordCorrection()
        s.applied(word.copy(delimiter = ". "), "the", 120, "")
        s.append("next", 130)
        assertEquals(RecentWordCorrection.RangeEdit(4, 7, "teh", 13), s.undoEdit(id, snapshot("say the. next"), 200))
    }
    @Test fun fiveSecondsAnd64UnitsBoundUndo() {
        val s = RecentWordCorrection(); s.applied(word, "the", 120, "")
        assertFalse(s.hasUndo(5120))
        s.applied(word, "the", 120, ""); s.append("a".repeat(64), 130)
        assertFalse(s.hasUndo(140))
    }
    @Test fun onlyVerifiedApplicationGetsUndoAndUnsafeEditsCancelBoth() {
        val s = RecentWordCorrection(); s.begin(word)
        assertNull(s.undo)
        s.applied(word, "the", 120); s.begin(word); s.cancel()
        assertNull(s.pending); assertNull(s.undo)
    }
    @Test fun unqualifiedProfilesNeverRangeEdit() {
        assertFalse(EditorProfiles.allowsRangeEdit(EditorProfiles.Profile("com.android.chrome", 1, 37, 1)))
    }
}
