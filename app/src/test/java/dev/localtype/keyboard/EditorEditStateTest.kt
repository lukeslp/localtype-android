package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class EditorEditStateTest {
    private val own = EditorEditState.Acknowledgement.OWN
    private val unsafe = EditorEditState.Acknowledgement.UNEXPLAINED
    private val delayed = EditorEditState.Acknowledgement.DELAYED
    @Test fun lateIntermediateCoalescedAndReorderedReceipts() {
        val s = EditorEditState()
        s.start(0, 0)
        repeat(3) { s.edit(0, 0, 1) }
        val current = EditorEditState.Selection(3, 3)
        assertEquals(own, s.acknowledge(1, 1, current))
        assertEquals(own, s.acknowledge(3, 3, current))
        assertEquals(own, s.acknowledge(2, 2, current))
    }
    @Test fun realMoveInsideFormerTimingWindowIsUnsafeOnceLaterReadsStopCatchingUp() {
        val s = EditorEditState()
        s.start(0, 0)
        repeat(3) { s.edit(0, 0, 1) }
        // The receipt and the read agree on an own position behind the latest one: undecided.
        assertEquals(delayed, s.acknowledge(1, 1, EditorEditState.Selection(1, 1)))
        assertEquals(EditorEditState.Selection(3, 3), s.expected)
        repeat(s.maxVerifications - 1) { assertEquals(delayed, s.verify(EditorEditState.Selection(1, 1))) }
        assertEquals(unsafe, s.verify(EditorEditState.Selection(1, 1)))
        // Still an unsafe move for the current word, but a fresh anchor for future edits.
        assertEquals(EditorEditState.Selection(1, 1), s.expected)
    }
    @Test fun delayedWebReceiptResolvesAsOwnWhenTheEditorCatchesUp() {
        val s = EditorEditState()
        s.start(19, 19)
        s.edit(0, 0, 1); s.edit(0, 0, 1)
        // The renderer still reports the first letter when the second is already committed.
        assertEquals(delayed, s.acknowledge(20, 20, EditorEditState.Selection(20, 20)))
        assertEquals(delayed, s.verify(EditorEditState.Selection(20, 20)))
        assertEquals(own, s.verify(EditorEditState.Selection(21, 21)))
        assertEquals(own, s.acknowledge(21, 21))
        // A read ahead of the receipt is still an own position behind the latest expected one.
        s.edit(0, 0, 1); s.edit(0, 0, 1)
        assertEquals(delayed, s.acknowledge(21, 21, EditorEditState.Selection(22, 22)))
        assertEquals(own, s.verify(EditorEditState.Selection(23, 23)))
        // A read behind the receipt is also undecided while both are own positions after the confirmed one.
        s.edit(0, 0, 1); s.edit(0, 0, 1)
        assertEquals(delayed, s.acknowledge(24, 24, EditorEditState.Selection(23, 23)))
        assertEquals(own, s.verify(EditorEditState.Selection(25, 25)))
        // A read outside own history is not a catching-up editor.
        s.start(5, 5); s.edit(0, 0, 1); s.edit(0, 0, 1)
        assertEquals(unsafe, s.acknowledge(6, 6, EditorEditState.Selection(2, 2)))
        s.start(5, 5); s.edit(0, 0, 1); s.edit(0, 0, 1)
        assertEquals(delayed, s.acknowledge(6, 6, EditorEditState.Selection(6, 6)))
        assertEquals(unsafe, s.verify(EditorEditState.Selection(2, 2)))
        assertEquals(unsafe, s.verify(null))
    }
    @Test fun missingObservationCannotCertifyIntermediateCallback() {
        val s = EditorEditState()
        s.start(4, 4)
        s.edit(0, 0, 1); s.edit(0, 0, 1)
        assertEquals(unsafe, s.acknowledge(5, 5))
    }
    @Test fun replacementAndSelectionCollapseAreKnownEdits() {
        val s = EditorEditState()
        s.start(7, 10); s.edit(0, 0, 4)
        assertEquals(own, s.acknowledge(11, 11))
        s.edit(4, 0, 6)
        assertEquals(own, s.acknowledge(13, 13))
    }
    @Test fun unknownStartAndSelectionChangesFailClosed() {
        val s = EditorEditState()
        s.start(-1, -1); s.edit(0, 0, 1)
        assertEquals(unsafe, s.acknowledge(1, 1))
        s.start(2, 2)
        assertEquals(unsafe, s.acknowledge(2, 4, EditorEditState.Selection(2, 4)))
    }
    @Test fun boundedHistoryAndRestartGeneration() {
        val s = EditorEditState(2)
        s.start(0, 0); val generation = s.generation
        repeat(3) { s.edit(0, 0, 1) }
        assertEquals(unsafe, s.acknowledge(1, 1, EditorEditState.Selection(3, 3)))
        s.start(3, 3)
        assertEquals(generation + 1, s.generation)
        // The anchor is an own position, so the editor confirming it is own rather than merely unchanged.
        assertEquals(own, s.acknowledge(3, 3))
        assertEquals(EditorEditState.Selection(3, 3), s.expected)
    }
    @Test fun verifiedRestartRetainsOwnReceiptsButInvalidatesRequests() {
        val s = EditorEditState()
        s.start(0, 0)
        repeat(3) { s.edit(0, 0, 1) }
        val generation = s.generation
        s.start(3, 3, retainHistory = true)
        assertEquals(generation + 1, s.generation)
        assertEquals(own, s.acknowledge(1, 1, EditorEditState.Selection(3, 3)))
        // The restart confirmed position 3, so a read of 1 cannot be an editor still catching up.
        assertEquals(unsafe, s.acknowledge(1, 1, EditorEditState.Selection(1, 1)))
    }

    @Test fun confirmedCursorMoveDoesNotDisableAllFollowingWords() {
        val s = EditorEditState()
        val trace = WordTrace()
        s.start(19, 19)
        for (position in listOf(3, 19)) {
            assertEquals(unsafe, s.acknowledge(position, position, EditorEditState.Selection(position, position)))
            trace.revisit()
        }
        "ppebble".forEachIndexed { i, _ ->
            trace.letter("ppebble".take(i), Touch(1f, 1f))
            s.edit(0, 0, 1)
            assertEquals(own, s.acknowledge(20 + i, 20 + i))
        }
        assertTrue(trace.hasTouchesFor("ppebble"))
        assertFalse(trace.revisited)
    }

    @Test fun reanchoringDoesNotAuthorizeTheRevisitedWordOrOldReceipts() {
        val s = EditorEditState()
        val trace = WordTrace()
        s.start(0, 0)
        repeat(3) { s.edit(0, 0, 1) }
        assertEquals(delayed, s.acknowledge(1, 1, EditorEditState.Selection(1, 1)))
        repeat(s.maxVerifications - 1) { assertEquals(delayed, s.verify(EditorEditState.Selection(1, 1))) }
        assertEquals(unsafe, s.verify(EditorEditState.Selection(1, 1)))
        trace.revisit()
        trace.letter("t", Touch(1f, 1f))
        s.edit(0, 0, 1)
        assertEquals(own, s.acknowledge(2, 2))
        assertTrue(trace.revisited)
        assertFalse(trace.hasTouchesFor("te"))
        assertEquals(unsafe, s.acknowledge(3, 3, EditorEditState.Selection(2, 2)))
        assertNull(s.expected)
    }

    @Test fun staleReadAtTheAnchorAfterTheFirstLetterIsDelayedNotAMove() {
        // Pixel 10, web field, 2026-10-10: the callback for the first letter arrived while the
        // WebView's extracted text still named the anchor, and the word was marked revisited.
        val s = EditorEditState()
        s.start(19, 19); s.edit(0, 0, 1)
        assertEquals(delayed, s.acknowledge(20, 20, EditorEditState.Selection(19, 19)))
        assertEquals(own, s.verify(EditorEditState.Selection(20, 20)))
        // A late report of the anchor itself, with a current read, is simply own.
        s.start(19, 19); s.edit(0, 0, 1)
        assertEquals(own, s.acknowledge(19, 19, EditorEditState.Selection(20, 20)))
        // Before any edit, a read elsewhere is still a move.
        s.start(19, 19)
        assertEquals(unsafe, s.acknowledge(19, 19, EditorEditState.Selection(7, 7)))
    }

    @Test fun unexplainedReceiptsNameTheTestTheyFailed() {
        val s = EditorEditState()
        assertEquals(EditorEditState.Cause.NONE, s.lastCause)
        s.start(5, 5); s.edit(0, 0, 1)
        assertEquals(unsafe, s.acknowledge(-1, -1)); assertEquals(EditorEditState.Cause.NEGATIVE, s.lastCause)
        assertEquals(unsafe, s.acknowledge(6, 6)); assertEquals(EditorEditState.Cause.NO_ANCHOR, s.lastCause)
        s.start(5, 5); s.edit(0, 0, 1)
        assertEquals(unsafe, s.acknowledge(9, 9, EditorEditState.Selection(9, 9))); assertEquals(EditorEditState.Cause.READ_MISMATCH, s.lastCause)
        s.start(5, 5); s.edit(0, 0, 1); s.edit(0, 0, 1)
        assertEquals(unsafe, s.acknowledge(6, 6)); assertEquals(EditorEditState.Cause.READ_MISSING, s.lastCause)
        s.start(5, 5); s.edit(0, 0, 1)
        assertEquals(unsafe, s.acknowledge(2, 2, EditorEditState.Selection(6, 6))); assertEquals(EditorEditState.Cause.UNKNOWN_RECEIPT, s.lastCause)
        s.start(5, 5); s.edit(0, 0, 1); s.edit(0, 0, 1)
        assertEquals(delayed, s.acknowledge(6, 6, EditorEditState.Selection(6, 6)))
        assertEquals(unsafe, s.verify(EditorEditState.Selection(1, 1))); assertEquals(EditorEditState.Cause.VERIFY_FAILED, s.lastCause)
    }

    @Test fun missingConflictingAndInvalidReceiptsCannotReanchor() {
        for ((received, observed) in listOf(
            EditorEditState.Selection(1, 1) to null,
            EditorEditState.Selection(1, 1) to EditorEditState.Selection(2, 2),
            EditorEditState.Selection(-1, -1) to EditorEditState.Selection(-1, -1),
        )) {
            val s = EditorEditState()
            s.start(5, 5)
            assertEquals(unsafe, s.acknowledge(received.start, received.end, observed))
            assertNull(s.expected)
            s.edit(0, 0, 1)
            assertNull(s.expected)
        }
    }
}
