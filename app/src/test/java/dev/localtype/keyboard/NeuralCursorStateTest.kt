package dev.localtype.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralCursorStateTest {
    @Test fun knownAppendRetainsState() {
        val state = NeuralCursorState()
        state.start(4, 4)
        state.edit(true, 1)
        assertFalse(state.selectionChanged(4, 4, 5, 5))
        state.edit(true, 2)
        assertFalse(state.selectionChanged(5, 5, 7, 7))
    }

    @Test fun cursorMoveImmediatelyAfterTypingStillResets() {
        val state = NeuralCursorState()
        state.start(4, 4)
        state.edit(true, 1)
        assertTrue(state.selectionChanged(4, 4, 8, 8))
        assertTrue(state.selectionChanged(8, 8, 3, 3))
        assertTrue(state.selectionChanged(3, 3, 3, 5))
    }

    @Test fun replacementAndUnknownSelectionReset() {
        val state = NeuralCursorState()
        state.start(4, 4)
        state.edit(false, 2)
        assertTrue(state.selectionChanged(4, 4, 6, 6))
        state.start(-1, -1)
        state.edit(true, 1)
        assertTrue(state.selectionChanged(-1, -1, 1, 1))
    }

    @Test fun coalescedAppendsAndRepeatedSelection() {
        val state = NeuralCursorState()
        state.start(0, 0)
        state.edit(true, 1)
        state.edit(true, 1)
        assertFalse(state.selectionChanged(0, 0, 2, 2))
        assertFalse(state.selectionChanged(2, 2, 2, 2))
        assertTrue(state.selectionChanged(2, 2, 3, 3))
    }
}
