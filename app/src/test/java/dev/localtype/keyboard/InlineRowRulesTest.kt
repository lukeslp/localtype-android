package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineRowRulesTest {
    @Test
    fun `no chips never take the strip`() {
        for (wordSlots in listOf(true, false)) for (typed in listOf(true, false)) {
            assertFalse(InlineRowRules.shows(chips = 0, unpinned = 0, wordSlots = wordSlots, typed = typed))
        }
    }

    @Test
    fun `a field without word slots shows any chip, typed in or not`() {
        assertTrue(InlineRowRules.shows(chips = 1, unpinned = 0, wordSlots = false, typed = false))
        assertTrue(InlineRowRules.shows(chips = 1, unpinned = 0, wordSlots = false, typed = true))
        assertTrue(InlineRowRules.shows(chips = 3, unpinned = 2, wordSlots = false, typed = true))
    }

    @Test
    fun `a suggestion for the field covers the word slots while the manager offers it`() {
        assertTrue(InlineRowRules.shows(chips = 2, unpinned = 1, wordSlots = true, typed = false))
        assertTrue(InlineRowRules.shows(chips = 2, unpinned = 1, wordSlots = true, typed = true))
    }

    @Test
    fun `pinned buttons alone give the word slots back at the first key`() {
        assertTrue(InlineRowRules.shows(chips = 1, unpinned = 0, wordSlots = true, typed = false))
        assertFalse(InlineRowRules.shows(chips = 1, unpinned = 0, wordSlots = true, typed = true))
    }

    @Test
    fun `pinned chips come first and each group keeps the manager's order`() {
        assertEquals(listOf(1, 3, 0, 2), InlineRowRules.order(listOf(false, true, false, true)))
        assertEquals(listOf(0, 1, 2), InlineRowRules.order(listOf(false, false, false)))
        assertEquals(emptyList<Int>(), InlineRowRules.order(emptyList()))
    }

    @Test
    fun `an answer counts the chips it expects before any is drawn`() {
        val answer = InlineAnswer<String>(listOf(false, true, false))
        assertEquals(3, answer.count)
        assertEquals(2, answer.unpinned)
        assertEquals(emptyList<Pair<String, Boolean>>(), answer.ordered())
        answer.drawn(2, "c")
        answer.drawn(1, "b")
        assertEquals(listOf("b" to true, "c" to false), answer.ordered())
        assertEquals(3, answer.count)
    }

    @Test
    fun `a chip that could not be drawn stops counting, so pinned buttons alone give the words back`() {
        val answer = InlineAnswer<String>(listOf(false, true))
        answer.drawn(0, null)
        answer.drawn(1, "pinned")
        assertEquals(1, answer.count)
        assertEquals(0, answer.unpinned)
        assertFalse(InlineRowRules.shows(answer.count, answer.unpinned, wordSlots = true, typed = true))
        // A view that turns up for a chip already counted out is not taken.
        answer.drawn(0, "late")
        assertEquals(listOf("pinned" to true), answer.ordered())
    }

    @Test
    fun `giving up counts out what was never drawn and keeps what was`() {
        val answer = InlineAnswer<String>(listOf(false, false, true))
        answer.drawn(1, "b")
        assertTrue(answer.giveUp())
        assertEquals(1, answer.count)
        assertEquals(1, answer.unpinned)
        assertEquals(listOf("b" to false), answer.ordered())
        assertFalse(answer.giveUp())
        val nothing = InlineAnswer<String>(listOf(false, true))
        assertTrue(nothing.giveUp())
        assertEquals(0, nothing.count)
        assertFalse(InlineRowRules.shows(nothing.count, nothing.unpinned, wordSlots = false, typed = false))
    }

    @Test
    fun `a reply for a chip the answer does not have is ignored`() {
        val empty = InlineAnswer<String>(emptyList())
        empty.drawn(0, "late")
        empty.drawn(-1, "x")
        assertEquals(0, empty.count)
        assertEquals(emptyList<Pair<String, Boolean>>(), empty.ordered())
        assertFalse(empty.giveUp())
    }
}
