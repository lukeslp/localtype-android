package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WordListImportTest {
    @Test
    fun `a plain list gives one candidate per line, in order, once each`() {
        val text = "﻿Zorpquill\r\n\r\n  McKenzie  \n# a comment\nzorpquill\nZorpquill\ntwo words\n"
        assertEquals(listOf("Zorpquill", "McKenzie", "zorpquill", "two words"), WordListImport.parse(text))
    }

    @Test
    fun `Gboard's export gives the word, not its shortcut or locale`() {
        val text = "# Gboard Dictionary version:1\n\n\tZorpquill\ten-US\nbrb\tquillzorp\ten-US\nlonely\n"
        assertEquals(listOf("Zorpquill", "quillzorp", "lonely"), WordListImport.parse(text))
    }

    @Test
    fun `other tabbed or comma rows give their first field, without quotes`() {
        assertEquals(listOf("zorpquill", "quillzorp", "Zed"), WordListImport.parse("zorpquill\t120\nquillzorp,7\n\"Zed\",1\n"))
    }

    @Test
    fun `only a bounded number of lines is looked at`() {
        val text = (0 until WordListImport.MAX_LINES + 50).joinToString("\n") { "w$it" }
        assertEquals(WordListImport.MAX_LINES, WordListImport.parse(text).size)
    }

    @Test
    fun `an import adds what the Add button would, keeps spellings and stops at the limit`() {
        val store = Store()
        AddedWords.add(store, "already")
        val inputs = WordListImport.parse("Already\nZorpquill\nMcKenzie\ntwo words\nabc123\nblockedword\nzorpquill\n")
        val result = AddedWords.addAll(store, inputs) { it == "blockedword" }
        assertEquals(listOf("zorpquill" to "Zorpquill", "mckenzie" to "McKenzie"), result.added)
        assertEquals(2, result.existing)
        assertEquals(2, result.invalid)
        assertEquals(1, result.blocked)
        assertEquals(0, result.full)
        assertEquals(listOf("already", "mckenzie", "zorpquill"), AddedWords.words(store.entries()))
        assertEquals(WordSpellings.Change.Record("McKenzie"), WordSpellings.added(result.added[1].second))

        val many = (0 until AddedWords.MAX_WORDS + 5).map { "zorp" + letters(it) }
        val filled = AddedWords.addAll(store, many)
        assertEquals(AddedWords.MAX_WORDS - 3, filled.added.size)
        assertEquals(8, filled.full)
        assertEquals(AddedWords.MAX_WORDS, AddedWords.words(store.entries()).size)
        assertEquals(AddedWords.Result.FULL, AddedWords.add(store, "onemore"))
    }

    @Test
    fun `a full list of added words survives the journal and leaves ordinary rows at their own limit`() {
        val store = Store()
        AddedWords.addAll(store, (0 until AddedWords.MAX_WORDS).map { "zorp" + letters(it) })
        repeat(LearnedPairState.MAX_FOLLOWERS + 10) { store.record("the", "word" + letters(it)) }
        val loaded = LearnedPairJournal.decode(LearnedPairJournal.compact(store.entries())).state.entries()
        assertEquals(AddedWords.MAX_WORDS, AddedWords.words(loaded).size)
        // An ordinary row at its limit fades to make room, as before; it never grows past it.
        assertTrue(loaded.count { it.first == "the" } in 1..LearnedPairState.MAX_FOLLOWERS)
        assertTrue(loaded.filter { it.first == AddedWords.CONTEXT }.all { it.third == 1 })
        val model = AdaptiveNgramModel(Store(LearnedPairState(loaded)), emptyList(), WordDictionary.EMPTY, emptyList())
        assertTrue(model.isAddedWord("zorp" + letters(AddedWords.MAX_WORDS - 1)))
    }

    @Test
    fun `the file reader takes text, the first txt in a zip, and refuses what is too large or not text`() {
        assertEquals(WordListFile.Read.Text("zorpquill\n"), WordListFile.read(ByteArrayInputStream("zorpquill\n".toByteArray())))
        val zipped = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("notes.md")); zip.write("no".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("dictionary.txt")); zip.write("# Gboard Dictionary version:1\n\tZorpquill\ten-US\n".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val read = WordListFile.read(ByteArrayInputStream(zipped))
        assertEquals(listOf("Zorpquill"), WordListImport.parse((read as WordListFile.Read.Text).text))
        assertEquals(WordListFile.Read.TooLarge, WordListFile.read(ByteArrayInputStream(ByteArray(WordListImport.MAX_BYTES + 1) { 'a'.code.toByte() })))
        assertEquals(WordListFile.Read.Unreadable, WordListFile.read(ByteArrayInputStream(byteArrayOf(1, 0, 2, 0))))
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("big.txt")); zip.write(ByteArray(WordListImport.MAX_BYTES + 1) { 'a'.code.toByte() }); zip.closeEntry()
            }
        }.toByteArray()
        assertEquals(WordListFile.Read.TooLarge, WordListFile.read(ByteArrayInputStream(bomb)))
    }

    @Test
    fun `a whole imported list keeps its capitals, written as one change`() {
        val names = (0 until AddedWords.MAX_WORDS).map { "Zorp" + letters(it) }
        val all = WordSpellings.applyAll(mapOf("older" to "OLDer"), names.map { it to WordSpellings.added(it) })!!
        assertEquals(AddedWords.MAX_WORDS + 1, all.size)
        assertEquals(names.first(), WordSpellings.display(all, names.first().lowercase()))
        assertEquals("OLDer", WordSpellings.display(all, "older"))
        assertEquals(null, WordSpellings.applyAll(all, listOf("plain" to WordSpellings.added("plain"))))
        val one = WordSpellings.apply(emptyMap(), "McKenzie", WordSpellings.added("McKenzie"))
        assertEquals(one, WordSpellings.applyAll(emptyMap(), listOf("McKenzie" to WordSpellings.added("McKenzie"))))
    }

    @Test
    fun `an added word's capitals are never the ones dropped to make room`() {
        var spellings: Map<String, String> = mapOf("mckenzie" to "McKenzie")
        val keep: (String) -> Boolean = { it == "mckenzie" }
        for (i in 0 until WordSpellings.MAX_WORDS + 20) {
            val word = "Typed" + letters(i)
            spellings = WordSpellings.apply(spellings, word, WordSpellings.Change.Record(word), keep) ?: spellings
        }
        assertEquals(WordSpellings.MAX_WORDS, spellings.size)
        assertEquals("McKenzie", WordSpellings.display(spellings, "mckenzie"))
        // The oldest learned ones went instead, and without a keeper the oldest of all goes.
        assertEquals(null, WordSpellings.display(spellings, "typed" + letters(0)))
        var plain: Map<String, String> = mapOf("mckenzie" to "McKenzie")
        for (i in 0 until WordSpellings.MAX_WORDS) {
            val word = "Typed" + letters(i)
            plain = WordSpellings.apply(plain, word, WordSpellings.Change.Record(word)) ?: plain
        }
        assertEquals(null, WordSpellings.display(plain, "mckenzie"))
        // Every slot kept: the oldest still goes, so the store never grows past its bound.
        val full = (0 until WordSpellings.MAX_WORDS).associate { ("kept" + letters(it)) to ("Kept" + letters(it)) }
        val over = WordSpellings.apply(full, "Newer", WordSpellings.Change.Record("Newer")) { true }!!
        assertEquals(WordSpellings.MAX_WORDS, over.size)
        assertEquals("Newer", WordSpellings.display(over, "newer"))
    }

    @Test
    fun `a long list is narrowed by the start of a word and drawn up to a limit`() {
        val words = listOf("mckenzie", "quill", "zorp", "zorpquill", "zorpquilla")
        assertEquals(AddedWords.Shown(listOf("mckenzie", "quill"), 5), AddedWords.shown(words, "", 2))
        assertEquals(AddedWords.Shown(listOf("zorp", "zorpquill"), 3), AddedWords.shown(words, " ZORP ", 2))
        assertEquals(AddedWords.Shown(emptyList(), 0), AddedWords.shown(words, "x", 2))
        assertEquals(AddedWords.Shown(listOf("mckenzie"), 1), AddedWords.shown(words, "McK", 100))
    }

    /** Letters only, so each is a valid word: 0 → "a", 27 → "bb". */
    private fun letters(n: Int): String = buildString {
        var value = n
        do { append('a' + value % 26); value = value / 26 } while (value > 0)
        append("q".repeat(2))
    }

    private class Store(val state: LearnedPairState = LearnedPairState()) : AdaptiveNgramModel.LearningStore {
        override fun entries() = state.entries()
        override fun record(previous: String, next: String) = state.record(previous, next)
        override fun clear() = state.clear()
    }
}
