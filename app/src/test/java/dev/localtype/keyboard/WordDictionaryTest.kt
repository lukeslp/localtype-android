package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordDictionaryTest {
    @Test
    fun `parses counts and ignores comments`() {
        val dictionary = WordDictionary.parse(sequenceOf("# header", "", "Apple\t10", "banana\t1000", "broken"))

        assertEquals(2, dictionary.size)
        assertTrue("apple" in dictionary)
        assertEquals(WordDictionary.MAX_SCORE, dictionary.score("banana"))
        assertTrue(dictionary.score("apple") in 1 until WordDictionary.MAX_SCORE)
    }

    @Test
    fun `prefix completions are frequency ordered and bounded`() {
        val dictionary = WordDictionary.parse(sequenceOf("car\t5", "cart\t50", "care\t500", "dog\t9000"))

        assertEquals(listOf("care", "cart"), dictionary.completions("car", 2).map { it.first })
        assertTrue(dictionary.completions("zebra", 3).isEmpty())
        assertEquals("dog", dictionary.completions("", 1).single().first)
    }

    @Test
    fun `bundled word list loads with common words and no blocked ones`() {
        val asset = File("src/main/assets/words_en.txt")
        val dictionary = asset.bufferedReader().useLines { WordDictionary.parse(it) }

        assertTrue(dictionary.size > 15_000)
        listOf("the", "you", "don't", "tomorrow", "because").forEach { assertTrue(it, it in dictionary) }
        assertFalse("don" in dictionary)
        assertEquals("the", dictionary.completions("th", 1).single().first)
    }
}
