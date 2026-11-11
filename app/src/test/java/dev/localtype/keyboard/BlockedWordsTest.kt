package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BlockedWordsTest {
    @Test fun `case and apostrophes normalize without folding distinct accents`() {
        assertEquals("don't", BlockedWords.normalize(" DON’T "))
        assertTrue(BlockedWords.contains(setOf("sí"), "SÍ"))
        assertFalse(BlockedWords.contains(setOf("sí"), "si"))
        assertEquals("ação", BlockedWords.normalize("AÇÃO"))
        assertEquals("tge", BlockedWords.normalize("TGE"))
        assertTrue(BlockedWords.blocksText(setOf("the"), "of the house"))
        assertFalse(BlockedWords.blocksText(setOf("the"), "other theme"))
        assertEquals("x", BlockedWords.normalize("x"))
        for (invalid in listOf("", "two words", "https://x.test", "x\ny", "a\u202eb", "a".repeat(81)))
            assertNull(invalid, BlockedWords.normalize(invalid))
    }

    @Test fun `bounded private file round trips every language with an explicit language identity`() {
        for (language in LanguageProfile.WITH_WORDS) {
            val words = setOf("tge", "ação", "don't", "sí")
            val bytes = BlockedWords.encode(words, language)
            assertEquals(words, BlockedWords.decode(bytes, language))
            assertEquals(emptySet<String>(), BlockedWords.decode(BlockedWords.encode(emptySet(), language), language))
            val other = if (language == LanguageProfile.ENGLISH) LanguageProfile.SPANISH else LanguageProfile.ENGLISH
            assertNull(BlockedWords.decode(bytes, other))
        }
        val words = (1..BlockedWords.MAX_WORDS).map { "word" + alphabetic(it) }.toSet()
        assertEquals(words, BlockedWords.decode(BlockedWords.encode(words, LanguageProfile.ENGLISH), LanguageProfile.ENGLISH))
        assertThrows(IllegalArgumentException::class.java) { BlockedWords.encode(words + "another", LanguageProfile.ENGLISH) }
    }

    private fun alphabetic(n: Int): String = if (n < 26) ('a' + n).toString() else alphabetic(n / 26) + ('a' + n % 26)

    @Test fun `truncation corruption oversized input and malformed utf8 never silently clear exclusions`() {
        val bytes = BlockedWords.encode(setOf("tge"), LanguageProfile.ENGLISH)
        for (length in bytes.indices) assertNull(BlockedWords.decode(bytes.copyOf(length), LanguageProfile.ENGLISH))
        val changed = bytes.copyOf(); changed[changed.indexOf('t'.code.toByte())] = 'x'.code.toByte()
        assertNull(BlockedWords.decode(changed, LanguageProfile.ENGLISH))
        assertNull(BlockedWords.decode(ByteArray(BlockedWords.MAX_BYTES + 1), LanguageProfile.ENGLISH))
        assertNull(BlockedWords.decode(byteArrayOf(0xc3.toByte(), 0x28), LanguageProfile.ENGLISH))
    }

    private class Disk : BlockedWordsFile.Disk {
        var bytes: ByteArray? = null
        var failRead = false
        var failWrite = false
        var writes = 0
        override fun read(maxBytes: Int): ByteArray? {
            if (failRead) throw IOException("read")
            check(bytes == null || bytes!!.size <= maxBytes)
            return bytes?.copyOf()
        }
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("write")
            this.bytes = bytes.copyOf(); writes++
        }
    }

    @Test fun `restart unblock and failed write preserve the last durable list`() {
        val disk = Disk(); val language = LanguageProfile.ENGLISH
        val file = BlockedWordsFile(disk, language)
        assertEquals(emptySet<String>(), file.load())
        assertEquals(setOf("tge"), file.save(setOf("tge")))
        assertEquals(setOf("tge"), BlockedWordsFile(disk, language).load())
        disk.failWrite = true
        assertThrows(IOException::class.java) { file.save(emptySet()) }
        assertEquals(setOf("tge"), file.load())
        disk.failWrite = false
        assertEquals(emptySet<String>(), file.save(emptySet()))
        assertEquals(emptySet<String>(), BlockedWordsFile(disk, language).load())
    }

    @Test fun `unreadable and malformed files remain intact until an explicit reset succeeds`() {
        val disk = Disk(); val file = BlockedWordsFile(disk, LanguageProfile.ENGLISH)
        disk.bytes = "damaged original".encodeToByteArray(); val original = disk.bytes!!.copyOf()
        assertThrows(IllegalStateException::class.java) { file.load() }
        assertArrayEquals(original, disk.bytes); assertEquals(0, disk.writes)
        disk.failRead = true
        assertThrows(IOException::class.java) { file.load() }
        disk.failRead = false
        assertEquals(emptySet<String>(), file.save(emptySet()))
        assertEquals(1, disk.writes)
    }

    @Test fun `publication requires a successful read back`() {
        val disk = Disk(); val file = BlockedWordsFile(disk, LanguageProfile.ENGLISH)
        val old = file.save(setOf("tge"))
        disk.failRead = true
        assertThrows(IOException::class.java) { file.save(setOf("teh")) }
        // No returned success can publish the failed verification; a later retry resolves the durable state.
        assertEquals(setOf("tge"), old)
        disk.failRead = false
        assertEquals(setOf("teh"), BlockedWordsFile(disk, LanguageProfile.ENGLISH).load())
    }

    @Test fun `exclusions filter dictionary and learned completions before limiting and unblock restores eligibility`() {
        val dictionary = WordDictionary.parse(sequenceOf("the\t100", "there\t90", "them\t80", "these\t70"))
        val blocked = hashSetOf("the", "there")
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList(), dictionary = dictionary, excluded = { it in blocked })
        model.accept("go", "them")
        assertEquals(listOf("them", "these"), model.predict("th", 2))
        assertFalse(model.predict("", 100).any { it in blocked })
        val seeded = AdaptiveNgramModel(excluded = { it in blocked })
        assertFalse(seeded.predict("", 100).any { it in blocked })
        blocked.clear()
        assertTrue("the" in model.predict("th", 4))
    }

    @Test fun `blocked targets and contexts never become new learned counts even after an explicit tap`() {
        val blocked = setOf("tge", "the")
        val dictionary = WordDictionary.parse(sequenceOf("the\t100", "home\t90"))
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList(), dictionary = dictionary,
            excluded = { it in blocked })
        model.accept("hello", "TGE")
        model.accept("the", "home")
        model.countWord("tge")
        assertFalse(model.learnTypedWord("hello the", persist = true))
        assertFalse(model.learnTypedWord("the home", persist = true))
        assertEquals(emptyMap<String, Int>(), model.tables().unigrams)
        assertEquals(emptyList<Triple<String, String, Int>>(), model.tables().bigrams)
        assertFalse(BlockedWords.mayLearn(blocked, listOf("TGE"), "home"))
        assertFalse(BlockedWords.mayLearn(blocked, listOf("hello"), "THE"))
        assertTrue(BlockedWords.mayLearn(blocked, listOf("hello"), "home"))
    }
}
