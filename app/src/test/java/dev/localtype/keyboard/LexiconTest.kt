package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class LexiconTest {
    private val lexicon = File("src/main/assets/words_known.txt").bufferedReader().useLines { Lexicon.parse(it) }
    private val dictionary = File("src/main/assets/words_en.txt").bufferedReader().useLines { WordDictionary.parse(it) }
    private val corrector = Corrector({ dictionary }, lexicon = { lexicon })

    @Test
    fun `small lexicons answer membership exactly`() {
        val small = Lexicon.parse(sequenceOf("# header", "zebra", "apple", "Mango", "apple"))
        assertEquals(3, small.size)
        assertTrue("apple" in small && "mango" in small && "zebra" in small)
        assertFalse("app" in small || "apples" in small || "zzz" in small || "" in small)
    }

    /** The parse before 2026-10-26, which held every word at once; the one-pass parse must keep the same set. */
    private fun reference(lines: List<String>): List<String> =
        lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct().sorted()

    private fun assertParsesAsBefore(lines: List<String>) {
        val expected = reference(lines)
        val parsed = Lexicon.parse(lines.asSequence())
        assertEquals(expected.size, parsed.size)
        expected.forEach { assertTrue(it, it in parsed) }
    }

    @Test
    fun `one-pass parse keeps the same words, in order or not`() {
        val modern = File("src/main/assets/words_en_modern.txt").readLines().map { it.substringBefore('\t') }
        val known = File("src/main/assets/words_known.txt").readLines()
        assertParsesAsBefore(known + modern) // what TypingEngine reads on the iPhone
        listOf("words_known_es.txt", "words_known_pt.txt").forEach { assertParsesAsBefore(File("src/main/assets/$it").readLines()) }
        assertParsesAsBefore(known.shuffled(Random(7)))
        assertParsesAsBefore(known.takeLast(5000).reversed() + listOf("Apple", " apple ", "APPLE", "# x", "", "zz", "a", "Zz"))
        assertParsesAsBefore(listOf("b", "a", "c", "a", "B", "d", "c", "ñu", "nu", "éclair", "eclair"))
        assertEquals(0, Lexicon.parse(sequenceOf("", "# only a comment")).size)
    }

    @Test
    fun `bundled lexicon knows rare, variant and modern words`() {
        assertTrue(lexicon.size > 120_000)
        listOf("labyrinth", "colour", "defence", "sesquipedalian", "clipboard", "screenshot", "wifi", "chatbot").forEach {
            assertTrue(it, it in lexicon)
        }
    }

    @Test
    fun `rare real words are never corrected`() {
        assertNull(corrector.correct("clipboard"))
        assertNull(corrector.suggest("clipboard"))
        assertNull(corrector.correct("sesquipedalian"))
        assertNull(corrector.correct("colour"))
    }

    @Test
    fun `slips on rare words correct to the rare word`() {
        assertEquals("labyrinth", corrector.correct("labyirnth"))
        assertEquals("sesquipedalian", corrector.correct("sesquipedlaian"))
    }
}
