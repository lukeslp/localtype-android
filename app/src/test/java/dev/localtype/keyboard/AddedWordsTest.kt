package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class AddedWordsTest {
    @Test
    fun `whole words normalize without accepting fragments of phrases or addresses`() {
        assertEquals("friend's", AddedWords.normalize("  Friend’s  "))
        assertEquals("parents'", AddedWords.normalize("parents'"))
        assertEquals("café", AddedWords.normalize("CAFÉ"))
        assertEquals("zorp-quill", AddedWords.normalize("zorp-quill"))
        for (input in listOf("", "two words", "word\nword", "word@example.test", "https://example.test", "abc123", "word!", "x", "zorp--quill", "a".repeat(81))) {
            assertNull(input, AddedWords.normalize(input))
        }
        assertEquals("i", AddedWords.normalize("I"))
    }

    @Test
    fun `duplicates and a full dictionary neither inflate counts nor evict words`() {
        val store = Store()
        repeat(AddedWords.MAX_WORDS) { n ->
            assertEquals(AddedWords.Result.ADDED, AddedWords.add(store, "zorp" + "a".repeat(n + 1)))
        }
        val original = store.entries()
        assertEquals(AddedWords.Result.EXISTS, AddedWords.add(store, "ZORPA"))
        assertEquals(AddedWords.Result.FULL, AddedWords.add(store, "quillzorp"))
        assertEquals(AddedWords.Result.INVALID, AddedWords.add(store, "two words"))
        assertEquals(original, store.entries())
        store.state.remove(AddedWords.CONTEXT, "zorpa")
        assertEquals(AddedWords.Result.ADDED, AddedWords.add(store, "quillzorp"))
    }

    @Test
    fun `additions predict prefixes without pretending to be sentence starts`() {
        val store = Store()
        AddedWords.add(store, "zorpquill")
        val model = model(store)
        assertTrue(model.knows("zorpquill"))
        assertEquals(listOf("zorpquill"), model.predict("zorpq"))
        assertTrue(model.predict("").isEmpty())
        assertEquals(0, model.ownCountAfter(AdaptiveNgramModel.START))
        assertEquals(listOf("zorpquill"), model.addedWords("ZORP"))
        assertTrue(model.addedWords("").isEmpty())
        assertTrue(model.learnedFollowers(AdaptiveNgramModel.START).isEmpty())
        assertTrue(model.learnTypedWord("hello zorpquill", persist = true))
        assertEquals(listOf("zorpquill" to 1), model.learnedFollowers("hello"))
    }

    @Test
    fun `journal restore and replay snapshots preserve explicit contraction protection`() {
        val store = Store()
        AddedWords.add(store, "dont")
        val restored = Store(LearnedPairJournal.decode(LearnedPairJournal.compact(store.entries())).state)
        val model = model(restored)
        val corrector = Corrector({ WordDictionary.EMPTY }, isKnown = model::knows, isKept = model::isAddedWord)
        assertTrue(model.isAddedWord("DONT"))
        assertNull(corrector.propose("dont", sentenceStart = true))
        val replay = AdaptiveNgramModel.fromTables(model.tables(), WordDictionary.EMPTY)
        assertTrue(replay.isAddedWord("dont"))
        for (context in listOf("", "do", "hello don")) assertEquals(model.predictScored(context), replay.predictScored(context))
        restored.state.remove(AddedWords.CONTEXT, "dont")
        val removed = model(restored)
        assertFalse(removed.isAddedWord("dont"))
        assertFalse(removed.knows("dont"))
        assertEquals("don't", Corrector({ WordDictionary.EMPTY }, isKnown = removed::knows, isKept = removed::isAddedWord).propose("dont", sentenceStart = true)?.word)
    }

    @Test
    fun `added words survive context retention and clear with learned words`() {
        val store = Store()
        AddedWords.add(store, "zorpquill")
        repeat(LearnedPairState.MAX_PREVIOUS_WORDS + 50) { n -> store.record("context$n", "ordinary") }
        assertEquals(listOf("zorpquill"), AddedWords.words(store.entries()))
        assertEquals(LearnedPairState.MAX_PREVIOUS_WORDS, store.entries().map { it.first }.distinct().size)
        val loaded = LearnedPairJournal.decode(LearnedPairJournal.compact(store.entries())).state
        assertEquals(listOf("zorpquill"), AddedWords.words(loaded.entries()))
        model(store).clearLearned(emptyList())
        assertTrue(AddedWords.words(store.entries()).isEmpty())
    }

    private fun model(store: Store) = AdaptiveNgramModel(store, emptyList(), WordDictionary.EMPTY, emptyList())

    private class Store(val state: LearnedPairState = LearnedPairState()) : AdaptiveNgramModel.LearningStore {
        override fun entries() = state.entries()
        override fun record(previous: String, next: String) = state.record(previous, next)
        override fun clear() = state.clear()
    }
}
