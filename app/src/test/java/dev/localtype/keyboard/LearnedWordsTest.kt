package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

/** Settings' Learned words list: the filter by either word's start, the words it finds, and one word forgotten. */
class LearnedWordsTest {
    private val start = AdaptiveNgramModel.START
    private val pairs = listOf(
        Triple(start, "teh", 5), Triple("the", "zorp", 4), Triple("teh", "cat", 3), Triple("zorp", "quill", 2),
        Triple(start, "started", 1), Triple("of", "teh", 1),
    )

    @Test
    fun `the filter keeps pairs whose previous or next word starts with it, in order`() {
        val shown = LearnedWords.shown(pairs, "TE", 150)
        assertEquals(listOf(Triple(start, "teh", 5), Triple("teh", "cat", 3), Triple("of", "teh", 1)), shown.rows)
        assertEquals(3, shown.matching)
        assertEquals(listOf(Triple("the", "zorp", 4), Triple("zorp", "quill", 2)), LearnedWords.shown(pairs, " zorp ", 150).rows)
        // The sentence-start marker is not a word: "start" finds "started" only.
        assertEquals(listOf(Triple(start, "started", 1)), LearnedWords.shown(pairs, "start", 150).rows)
        assertEquals(0, LearnedWords.shown(pairs, "xyz", 150).matching)
    }

    @Test
    fun `a blank filter shows every pair and the limit only caps what is drawn`() {
        assertEquals(pairs, LearnedWords.shown(pairs, "  ", 150).rows)
        val capped = LearnedWords.shown(pairs, "", 2)
        assertEquals(pairs.take(2), capped.rows)
        assertEquals(pairs.size, capped.matching)
        val many = (1..400).map { Triple("zorp", "zorp$it", 1) }
        assertEquals(150, LearnedWords.shown(many, "zorp", 150).rows.size)
        assertEquals(400, LearnedWords.shown(many, "zorp", 150).matching)
    }

    @Test
    fun `the words found are counted as the words they end and sorted by use`() {
        assertEquals(listOf(LearnedWords.Word("teh", 6)), LearnedWords.words(pairs, "teh", 20))
        // "the" only opens a pair, so it has no uses, and is listed so it can still be forgotten.
        assertEquals(listOf(LearnedWords.Word("teh", 6), LearnedWords.Word("the", 0)), LearnedWords.words(pairs, "t", 20))
        assertEquals(listOf(LearnedWords.Word("started", 1)), LearnedWords.words(pairs, "st", 20))
        assertEquals(listOf(LearnedWords.Word("zorp", 4)), LearnedWords.words(pairs, "Zo", 20))
        assertTrue(LearnedWords.words(pairs, "", 20).isEmpty())
        assertEquals(1, LearnedWords.words(pairs, "t", 1).size)
        // Added words are their own list: the reserved row adds nothing here.
        assertTrue(LearnedWords.words(listOf(Triple(AddedWords.CONTEXT, "zorpquill", 1)), "zorp", 20).isEmpty())
    }

    @Test
    fun `forgetting a word takes every pair it is in, on either side, and its capitals`() {
        val plan = LearnedWords.forget(pairs, "Teh")
        assertEquals("teh", plan.word)
        assertEquals(listOf(start to "teh", "teh" to "cat", "of" to "teh"), plan.pairs)
        assertFalse(plan.keepsSpelling)
        assertTrue(LearnedWords.forget(pairs, "nothing").pairs.isEmpty())
    }

    @Test
    fun `an added word keeps its row and capitals when its learned pairs go`() {
        val entries = pairs + Triple(AddedWords.CONTEXT, "teh", 1)
        val plan = LearnedWords.forget(entries, "teh")
        assertTrue(plan.keepsSpelling)
        assertFalse(AddedWords.CONTEXT to "teh" in plan.pairs)
    }

    @Test
    fun `applying the plan leaves the model knowing nothing of the word and the rest unchanged`() {
        val store = Store()
        pairs.forEach { (previous, next, count) -> repeat(count) { store.record(previous, next) } }
        assertTrue(model(store).knows("teh"))
        LearnedWords.forget(store.entries(), "teh").pairs.forEach { (previous, next) -> store.state.remove(previous, next) }
        val model = model(store)
        assertFalse(model.knows("teh"))
        assertEquals(0, model.pairCount("teh", "cat"))
        assertEquals(0, model.pairCount("of", "teh"))
        assertFalse(model.knows("cat"))
        assertTrue(model.knows("zorp") && model.knows("quill") && model.knows("started"))
        assertEquals(listOf(Triple("the", "zorp", 4), Triple("zorp", "quill", 2), Triple(start, "started", 1)),
            store.entries().sortedByDescending { it.third })

        // An added word stays known through its own row.
        AddedWords.add(store, "zorp")
        LearnedWords.forget(store.entries(), "zorp").pairs.forEach { (previous, next) -> store.state.remove(previous, next) }
        assertTrue(model(store).knows("zorp"))
        assertEquals(listOf("zorp"), AddedWords.words(store.entries()))
    }

    private fun model(store: Store) = AdaptiveNgramModel(store, emptyList(), WordDictionary.EMPTY, emptyList())

    private class Store(val state: LearnedPairState = LearnedPairState()) : AdaptiveNgramModel.LearningStore {
        override fun entries() = state.entries()
        override fun record(previous: String, next: String) = state.record(previous, next)
        override fun clear() = state.clear()
    }
}
