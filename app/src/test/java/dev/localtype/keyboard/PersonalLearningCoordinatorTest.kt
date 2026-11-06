package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class PersonalLearningCoordinatorTest {
    private val state = PersonalLearningState()
    private val coordinator = PersonalLearningCoordinator(state::record, state::undo)
    private fun complete(before: String, word: String, origin: LearningOrigin = LearningOrigin.TYPED,
                         allowed: Boolean = true, verified: Boolean = true, end: Boolean = false) =
        coordinator.complete(before, word, word, origin, allowed, verified, end)

    @Test fun `preexisting text never seeds suffix context and ordinary words require two observations`() {
        complete("foreign pasted ", "hello")
        assertTrue(state.entries().isEmpty())
        complete("foreign pasted hello ", "world")
        assertTrue(state.followers(listOf("hello")).isEmpty())
        coordinator.reset(PersonalContextTrace.ResetReason.FIELD)
        complete("", "hello")
        complete("hello ", "world")
        assertEquals(2, state.followers(listOf("hello")).single().observations)
        assertEquals(0, state.followers(listOf("hello")).single().explicitObservations)
    }

    @Test fun `one use qualifies behind two own words but not behind one`() {
        complete("", "see")
        complete("see ", "you")
        complete("see you ", "soon")
        assertEquals(listOf("soon"), state.followers(listOf("see", "you")).map { it.word })
        assertEquals(2, state.followers(listOf("see", "you")).single().contextLength)
        assertTrue(state.followers(listOf("you")).isEmpty())
        assertEquals(listOf("soon"), state.followers(listOf("other", "see", "you")).map { it.word })
    }

    @Test fun `deliberate choices qualify once and completion whitespace cannot count twice`() {
        complete("", "hello")
        complete("hello ", "zorp", LearningOrigin.EXPLICIT_KEEP)
        assertEquals(1, state.followers(listOf("hello")).single().explicitObservations)
        complete("hello zorp ", "", verified = false)
        assertEquals(1, state.entries().single().observations)
    }

    @Test fun `privacy uncertainty and all lifecycle resets break ownership`() {
        for (reason in PersonalContextTrace.ResetReason.entries) {
            complete("", "hello")
            coordinator.reset(reason)
            assertTrue(coordinator.context("hello ").isEmpty())
        }
        complete("", "hello")
        assertNull(complete("hello ", "private", LearningOrigin.SUGGESTION_ACCEPT, allowed = false))
        assertTrue(state.entries().isEmpty())
        assertTrue(coordinator.context("hello private ").isEmpty())
        complete("", "hello")
        assertTrue(coordinator.context("hello pasted ").isEmpty())
    }

    @Test fun `context is bounded and terminates at sentence boundary`() {
        complete("", "one")
        complete("one ", "two")
        complete("one two ", "three")
        complete("one two three ", "four")
        assertEquals(listOf("two", "three", "four"), coordinator.context("one two three four "))
        complete("one two three four ", "five", end = true)
        assertTrue(coordinator.context("one two three four five. ").isEmpty())
    }

    @Test fun `verified repair uses nonexplicit evidence and owned corrected spelling`() {
        complete("", "hello")
        val receipt = coordinator.complete("hello ", "wrold", "world", LearningOrigin.VERIFIED_REPAIR, true, true)
        assertEquals(listOf("hello", "world"), coordinator.context("hello world "))
        assertEquals(0, state.entries().single().explicitObservations)
        assertTrue(state.followers(listOf("hello")).isEmpty())
        coordinator.undo(receipt)
        assertTrue(state.entries().isEmpty())
        assertTrue(coordinator.context("hello wrold").isEmpty())
    }

    @Test fun `exact undo preserves a later observation and cannot be consumed twice`() {
        complete("", "hello")
        val receipt = complete("hello ", "world", LearningOrigin.SUGGESTION_ACCEPT)
        state.record(listOf("hello"), "world", LearningOrigin.TYPED)
        coordinator.undo(receipt)
        coordinator.undo(receipt)
        assertEquals(1, state.entries().single().observations)
        assertEquals(0, state.entries().single().explicitObservations)
    }

    @Test fun `late repair uses captured own context without inventing later ownership`() {
        complete("", "hello")
        val context = coordinator.context("hello ")
        complete("hello ", "raw", verified = false)
        val receipt = coordinator.repair(context, "world", true)
        assertEquals(listOf("hello"), state.entries().single().context)
        assertEquals(0, state.entries().single().explicitObservations)
        assertTrue(coordinator.context("hello world later ").isEmpty())
        coordinator.undo(receipt)
        assertTrue(state.entries().isEmpty())
    }

    @Test fun `a typed word that teaches nothing still counts as typed for the word after it`() {
        // "london" is in the field from a saved text: nothing says the person typed it.
        assertTrue(coordinator.context("send to flat london ").isEmpty())
        coordinator.own("send to flat london ", "see")
        // Nothing was stored for it, and it is the last own word at exactly that spot.
        assertTrue(state.entries().isEmpty())
        assertEquals(listOf("see"), coordinator.context("send to flat london see "))
        // The word after it is recorded behind it as usual.
        complete("send to flat london see ", "you")
        assertEquals(listOf("see", "you"), coordinator.context("send to flat london see you "))
        // Somewhere else the same words are not known to be the person's.
        assertTrue(coordinator.context("send to flat london ").isEmpty())
    }
}
