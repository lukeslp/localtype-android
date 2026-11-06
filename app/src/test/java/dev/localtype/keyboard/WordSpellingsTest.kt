package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordSpellingsTest {
    private fun change(raw: String, sentenceStart: Boolean = false, capsLock: Boolean = false, common: Boolean = false) =
        WordSpellings.change(raw, sentenceStart, capsLock, common)

    @Test fun `capitals inside a word, all caps and a mid-sentence capital record the spelling`() {
        for (raw in listOf("LocalType", "iPhone", "McKenzie", "NASA", "BlackLivesMatter")) {
            assertEquals(raw, WordSpellings.Change.Record(raw), change(raw))
            assertEquals(raw, WordSpellings.Change.Record(raw), change(raw, sentenceStart = true))
        }
        assertEquals(WordSpellings.Change.Record("Charlotte"), change("Charlotte"))
    }

    @Test fun `a sentence start, caps lock and everyday words say nothing`() {
        assertEquals(WordSpellings.Change.Unchanged, change("Charlotte", sentenceStart = true))
        assertEquals(WordSpellings.Change.Unchanged, change("LOCALTYPE", capsLock = true))
        for (raw in listOf("Will", "WILL", "May", "IT")) assertEquals(raw, WordSpellings.Change.Unchanged, change(raw, common = true))
        assertEquals(WordSpellings.Change.Unchanged, change("v2"))
        assertEquals(WordSpellings.Change.Unchanged, change("snake_case"))
    }

    @Test fun `a word typed in lowercase forgets its spelling, except at a sentence start`() {
        assertEquals(WordSpellings.Change.Forget, change("localtype"))
        assertEquals(WordSpellings.Change.Unchanged, change("localtype", sentenceStart = true))
        var spellings: Map<String, String> = emptyMap()
        spellings = WordSpellings.apply(spellings, "LocalType", WordSpellings.Change.Record("LocalType"))!!
        assertEquals("LocalType", WordSpellings.display(spellings, "localtype"))
        assertEquals("LocalType's", WordSpellings.display(spellings, "localtype's"))
        assertNull(WordSpellings.apply(spellings, "LocalType", WordSpellings.Change.Record("LocalType")))
        spellings = WordSpellings.apply(spellings, "localtype", WordSpellings.Change.Forget)!!
        assertNull(WordSpellings.display(spellings, "localtype"))
    }

    @Test fun `added words keep the spelling typed in settings`() {
        assertEquals(WordSpellings.Change.Record("LocalType"), WordSpellings.added(" LocalType "))
        assertEquals(WordSpellings.Change.Unchanged, WordSpellings.added("localtype"))
        assertEquals(WordSpellings.Change.Unchanged, WordSpellings.added("Local Type"))
    }

    @Test fun `the file round-trips, drops bad lines and keeps the newest words`() {
        val spellings = linkedMapOf("localtype" to "LocalType", "iphone" to "iPhone")
        assertEquals(spellings, WordSpellings.decode(WordSpellings.encode(spellings)))
        assertEquals(mapOf("iphone" to "iPhone"), WordSpellings.decode("iphone\tiPhone\nother\tLocalType\nbroken\n\tX"))
        var many: Map<String, String> = emptyMap()
        for (i in 0..WordSpellings.MAX_WORDS) {
            val word = "Word" + "abcdefghijklmnopqrstuvwxyz"[i % 26] + "abcdefghijklmnopqrstuvwxyz"[i / 26 % 26]
            many = WordSpellings.apply(many, word, WordSpellings.Change.Record(word)) ?: many
        }
        assertEquals(WordSpellings.MAX_WORDS, many.size)
        assertNull(WordSpellings.display(many, "wordaa"))
    }
}
