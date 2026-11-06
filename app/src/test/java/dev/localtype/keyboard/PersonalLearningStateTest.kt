package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class PersonalLearningStateTest {
    private val context = listOf("see", "you", "at")
    private fun record(state: PersonalLearningState, word: String = "home", origin: LearningOrigin = LearningOrigin.TYPED) =
        state.record(context, word, origin)!!

    @Test fun `passive observations need repetition behind one word and explicit origins need only one`() {
        for (origin in LearningOrigin.values()) {
            val state = PersonalLearningState()
            record(state, origin = origin)
            val explicit = origin == LearningOrigin.EXPLICIT_KEEP || origin == LearningOrigin.SUGGESTION_ACCEPT
            // One observation qualifies behind two or three own words; a single word needs two or an explicit choice.
            assertEquals(3, state.followers(context).single().contextLength)
            assertEquals(2, state.followers(context.takeLast(2)).single().contextLength)
            assertEquals(explicit, state.followers(context.takeLast(1)).isNotEmpty())
            assertEquals(listOf(1, 1, 1), state.entries().map { it.observations })
            assertTrue(state.entries().all { it.explicitObservations == if (explicit) 1 else 0 })
            record(state, origin = origin)
            assertEquals(2, state.followers(context).single().observations)
            assertEquals(2, state.followers(context.takeLast(1)).single().observations)
        }
    }

    @Test fun `long context disambiguates ambiguous unigram and backs off only without eligible choices`() {
        val state = PersonalLearningState()
        state.record(listOf("meet", "at"), "home", LearningOrigin.EXPLICIT_KEEP)
        state.record(listOf("work", "at"), "office", LearningOrigin.SUGGESTION_ACCEPT)
        assertEquals(listOf("home"), state.followers(listOf("meet", "at")).map { it.word })
        assertEquals(2, state.followers(listOf("meet", "at")).single().contextLength)
        assertEquals(listOf("office", "home"), state.followers(listOf("at")).map { it.word })
        state.record(listOf("other", "at"), "park", LearningOrigin.VERIFIED_REPAIR)
        // A passive observation behind two words qualifies once; retrieval backs off only without any.
        assertEquals(listOf("park"), state.followers(listOf("other", "at")).map { it.word })
        state.record(listOf("at"), "lake", LearningOrigin.TYPED)
        val backedOff = state.followers(listOf("never", "at"))
        assertEquals(listOf("office", "home"), backedOff.map { it.word })
        assertTrue(backedOff.all { it.contextLength == 1 })
        assertEquals(backedOff, state.followers(listOf("meet", "at"), 1))
        assertTrue(state.followers(context, 0).isEmpty())
    }

    @Test fun `only last three context words are stored and normalized without parsing`() {
        val state = PersonalLearningState()
        state.record(listOf("ignored", "I", "DON’T", "Know"), "MAÑANA", LearningOrigin.EXPLICIT_KEEP)
        assertEquals(listOf(listOf("know"), listOf("don't", "know"), listOf("i", "don't", "know")), state.entries().map { it.context })
        assertEquals("mañana", state.followers(listOf("I", "don’t", "KNOW")).single().word)
        assertEquals(3, state.followers(listOf("i", "don't", "know"), 100).single().contextLength)
        val snapshot = state.entries()
        for (word in listOf("", "two words", "42", "x", " hello", "hello!", "\ud800")) {
            assertNull(state.record(context, word, LearningOrigin.EXPLICIT_KEEP))
        }
        assertNull(state.record(emptyList(), "hello", LearningOrigin.EXPLICIT_KEEP))
        assertNull(state.record(listOf("bad context"), "hello", LearningOrigin.TYPED))
        assertTrue(state.followers(listOf("bad context")).isEmpty())
        assertEquals(snapshot, state.entries())
        assertNotNull(state.record(listOf("x"), "a", LearningOrigin.EXPLICIT_KEEP))
    }

    @Test fun `context eviction follows observations including saturated ones and reads do not refresh`() {
        val state = PersonalLearningState(maxContexts = 2, maxCount = 1)
        fun observe(word: String) = state.record(listOf(word), "home", LearningOrigin.EXPLICIT_KEEP)
        observe("first"); observe("second")
        state.followers(listOf("first")); state.entries()
        observe("third")
        assertEquals(listOf(listOf("second"), listOf("third")), state.entries().map { it.context })
        observe("second"); observe("fourth")
        assertEquals(listOf(listOf("second"), listOf("fourth")), state.entries().map { it.context })
    }

    @Test fun `follower eviction chooses weakest then oldest without decaying survivors`() {
        val state = PersonalLearningState(maxFollowers = 2)
        state.record(listOf("at"), "strong", LearningOrigin.EXPLICIT_KEEP)
        state.record(listOf("at"), "strong", LearningOrigin.TYPED)
        state.record(listOf("at"), "weak", LearningOrigin.EXPLICIT_KEEP)
        state.record(listOf("at"), "new", LearningOrigin.EXPLICIT_KEEP)
        assertEquals(setOf("strong", "new"), state.entries().map { it.word }.toSet())
        assertEquals(2, state.entries().single { it.word == "strong" }.observations)
        state.record(listOf("at"), "new", LearningOrigin.TYPED)
        state.record(listOf("at"), "latest", LearningOrigin.EXPLICIT_KEEP)
        assertFalse(state.entries().any { it.word == "strong" })
    }

    @Test fun `ranking uses count recency then lexical and eviction has deterministic lexical ties`() {
        fun entry(word: String, count: Int, time: Long) = PersonalLearningState.Entry(listOf("at"), word, count, 1, time)
        val input = listOf(entry("zebra", 2, 5), entry("alpha", 2, 5), entry("recent", 2, 6), entry("strong", 3, 1))
        for (entries in listOf(input, input.reversed())) {
            val state = PersonalLearningState()
            state.restore(entries)
            assertEquals(listOf("strong", "recent", "alpha", "zebra"), state.followers(listOf("at")).map { it.word })
            val bounded = PersonalLearningState(maxFollowers = 3)
            bounded.restore(entries)
            assertEquals(setOf("strong", "recent", "zebra"), bounded.entries().map { it.word }.toSet())
        }
    }

    @Test fun `undo subtracts one contribution from every suffix while preserving later records and recency`() {
        val state = PersonalLearningState()
        val first = record(state)
        record(state, origin = LearningOrigin.EXPLICIT_KEEP)
        record(state, "office", LearningOrigin.SUGGESTION_ACCEPT)
        val later = state.entries().filter { it.word == "home" }.map { it.lastObserved }
        assertTrue(state.undo(first))
        assertFalse(state.undo(first))
        assertTrue(state.entries().filter { it.word == "home" }.all { it.observations == 1 && it.explicitObservations == 1 })
        assertEquals(later, state.entries().filter { it.word == "home" }.map { it.lastObserved })
        assertEquals(6, state.entries().size)
    }

    @Test fun `undo explicit evidence leaves remaining passive observation ineligible behind one word`() {
        val state = PersonalLearningState()
        val explicit = record(state, origin = LearningOrigin.EXPLICIT_KEEP)
        record(state)
        assertTrue(state.undo(explicit))
        assertTrue(state.followers(context.takeLast(1)).isEmpty())
        assertEquals(0, state.followers(context).single().explicitObservations)
        assertTrue(state.entries().all { it.observations == 1 && it.explicitObservations == 0 })
    }

    @Test fun `saturated records refresh recency but undo only actually added counts`() {
        val state = PersonalLearningState(maxCount = 2)
        val first = record(state, origin = LearningOrigin.EXPLICIT_KEEP)
        val second = record(state)
        val saturated = record(state, origin = LearningOrigin.SUGGESTION_ACCEPT)
        val snapshot = state.entries()
        assertTrue(snapshot.all { it.observations == 2 && it.explicitObservations == 2 })
        assertTrue(state.undo(saturated))
        assertTrue(state.entries().all { it.observations == 2 && it.explicitObservations == 1 })
        assertTrue(state.undo(first))
        assertTrue(state.entries().all { it.observations == 1 && it.explicitObservations == 0 })
        assertTrue(state.undo(second))
        assertTrue(state.entries().isEmpty())
    }

    @Test fun `evicted and recreated followers cannot be affected by old receipts`() {
        val state = PersonalLearningState(maxContexts = 1, maxFollowers = 1)
        val old = state.record(listOf("at"), "home", LearningOrigin.EXPLICIT_KEEP)!!
        state.record(listOf("at"), "office", LearningOrigin.EXPLICIT_KEEP)
        state.record(listOf("at"), "home", LearningOrigin.TYPED)
        val replacement = state.entries()
        assertTrue(state.undo(old))
        assertEquals(replacement, state.entries())
        val evicted = state.record(listOf("at"), "home", LearningOrigin.TYPED)!!
        state.record(listOf("other"), "place", LearningOrigin.EXPLICIT_KEEP)
        state.record(listOf("at"), "home", LearningOrigin.EXPLICIT_KEEP)
        val recreated = state.entries()
        assertTrue(state.undo(evicted))
        assertEquals(recreated, state.entries())
    }

    @Test fun `undo survives partial suffix eviction without resurrection`() {
        val state = PersonalLearningState(maxContexts = 2)
        val receipt = record(state, origin = LearningOrigin.EXPLICIT_KEEP)
        assertEquals(2, state.entries().size)
        assertTrue(state.undo(receipt))
        assertTrue(state.entries().isEmpty())
    }

    @Test fun `receipt window is bounded to last sixty four and clear and restore invalidate it`() {
        val state = PersonalLearningState()
        val ids = (0..64).map { record(state) }
        assertFalse(state.undo(ids.first()))
        assertTrue(state.undo(ids[1]))
        assertFalse(state.undo(-1))
        state.clear()
        assertTrue(state.entries().isEmpty())
        assertFalse(state.undo(ids.last()))
        val fresh = record(state)
        assertTrue(fresh > ids.last())
        val saved = state.entries()
        state.restore(saved)
        assertFalse(state.undo(fresh))
        val newId = record(state)
        assertTrue(newId > fresh)
        assertTrue(state.entries().all { it.lastObserved > saved.first().lastObserved })
    }

    @Test fun `restore validates clamps bounds and never invents explicit intent`() {
        fun entry(ctx: List<String>, word: String, count: Int, explicit: Int, time: Long) =
            PersonalLearningState.Entry(ctx, word, count, explicit, time)
        val state = PersonalLearningState(maxContexts = 1, maxFollowers = 2, maxCount = 3)
        state.restore(listOf(
            entry(listOf("old"), "lost", 2, 0, 1),
            entry(listOf("AT"), "HOME", 999, 999, 50),
            entry(listOf("at"), "office", 2, -1, 51),
            entry(listOf("at"), "weak", 1, 0, 52),
            entry(emptyList(), "bad", 2, 0, 100),
            entry(listOf("a", "b", "c", "d"), "bad", 2, 0, 100),
            entry(listOf("bad!"), "bad", 2, 0, 100),
            entry(listOf("at"), "two words", 2, 0, 100),
            entry(listOf("at"), "zero", 0, 1, 100),
            entry(listOf("at"), "negative", 2, 1, -1),
        ))
        assertEquals(listOf("home", "office"), state.entries().map { it.word })
        assertEquals(listOf(3, 2), state.entries().map { it.observations })
        assertEquals(listOf(3, 0), state.entries().map { it.explicitObservations })
        state.record(listOf("at"), "office", LearningOrigin.TYPED)
        assertEquals(53L, state.entries().single { it.word == "office" }.lastObserved)
        val snapshot = state.entries()
        state.restore(snapshot)
        assertEquals(snapshot, state.entries())
    }

    @Test fun `inputs snapshots and followers are detached`() {
        val state = PersonalLearningState()
        val input = mutableListOf("at")
        state.record(input, "home", LearningOrigin.EXPLICIT_KEEP)
        input[0] = "changed"
        val entries = state.entries()
        val followers = state.followers(listOf("at"))
        state.record(listOf("at"), "home", LearningOrigin.TYPED)
        state.clear()
        assertEquals(listOf("at"), entries.single().context)
        assertEquals(1, followers.single().observations)
        val restoredContext = mutableListOf("new", "context")
        state.restore(listOf(PersonalLearningState.Entry(restoredContext, "word", 1, 1, 4)))
        restoredContext.clear()
        val snapshot = state.entries()
        (snapshot.single().context as MutableList<String>)[0] = "external"
        assertEquals(listOf("new", "context"), state.entries().single().context)
    }

    @Test fun `configurable receipt window and invalid bounds`() {
        val state = PersonalLearningState(maxUndoReceipts = 1)
        val first = record(state)
        val second = record(state)
        assertFalse(state.undo(first))
        assertTrue(state.undo(second))
        val disabled = PersonalLearningState(maxUndoReceipts = 0)
        assertFalse(disabled.undo(record(disabled)))
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningState(maxContexts = 0) }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningState(maxFollowers = 0) }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningState(maxCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningState(maxUndoReceipts = 65) }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningState(maxContexts = 1025) }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningState(maxFollowers = 9) }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningState(maxCount = 101) }
    }
    @Test fun `saturated explicit contribution survives old passive undo with clamping`() {
        val state = PersonalLearningState(maxCount = 2)
        val old = record(state)
        record(state)
        val first = record(state, origin = LearningOrigin.EXPLICIT_KEEP)
        val second = record(state, origin = LearningOrigin.SUGGESTION_ACCEPT)
        val full = record(state, origin = LearningOrigin.EXPLICIT_KEEP)
        assertTrue(state.undo(full))
        assertTrue(state.entries().all { it.explicitObservations == 2 })
        assertTrue(state.undo(old))
        assertTrue(state.entries().all { it.observations == 1 && it.explicitObservations == 1 })
        assertTrue(state.undo(second))
        assertTrue(state.entries().all { it.observations == 1 && it.explicitObservations == 0 })
        assertTrue(state.undo(first))
        assertTrue(state.entries().all { it.observations == 1 && it.explicitObservations == 0 })
    }

    @Test fun `raw and normalized words have independent size bounds`() {
        val state = PersonalLearningState()
        assertNotNull(record(state, "é".repeat(128), LearningOrigin.EXPLICIT_KEEP))
        for (word in listOf("a".repeat(129), "İ".repeat(128), "a".repeat(513))) {
            assertNull(state.record(context, word, LearningOrigin.EXPLICIT_KEEP))
            assertNull(state.record(listOf(word), "valid", LearningOrigin.TYPED))
            state.restore(listOf(PersonalLearningState.Entry(listOf("at"), word, 2, 0, 1)))
            assertTrue(state.entries().isEmpty())
        }
    }

}
