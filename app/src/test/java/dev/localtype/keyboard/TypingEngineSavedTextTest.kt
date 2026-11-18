package dev.localtype.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Saved text on the engine the iPhone keyboard calls: a saved text's words never become the previous word of a
 * learned pair unless the person typed that word right there. The facade copies the app's SavedWords, so the rule
 * is checked against it here.
 */
class TypingEngineSavedTextTest {
    private fun asset(name: String) = File("src/main/assets/$name")

    private fun open(options: TypingEngine.Options = TypingEngine.Options.NONE) = TypingEngine(
        language = LanguageProfile.ENGLISH,
        words = asset("words_en.txt").readText(),
        swears = asset("words_en_profanity.txt").readText(),
        lexicon = asset("words_known.txt").readText(),
        modern = asset("words_en_modern.txt").readText(),
        prior = null,
        journal = null,
        suggestSwears = true,
        emoji = asset("emoji_en.txt").readText(),
        options = options,
    )

    private val address = "221B Baker Street, London"

    private fun TypingEngine.pairs() = LearnedPairJournal.decode(journal()).state.entries().toSet()

    @Test
    fun `with no saved texts nothing is blocked and learning is as it was`() {
        val plain = open()
        val emptied = open().apply { setSavedTexts(listOf(address)); setSavedTexts(emptyList()) }
        for (text in listOf("Street hello", "London ", "", "hello")) {
            assertFalse(text, plain.savedTextBlocks(text))
            assertFalse(text, emptied.savedTextBlocks(text))
        }
        for (engine in listOf(plain, emptied)) {
            assertTrue(engine.learn("$address hello"))
            assertTrue(engine.learn("$address hello there"))
            engine.accept("$address hello there ", "friend")
        }
        assertArrayEquals(plain.journal(), emptied.journal())
        // Fixed values, not one engine against another: one pair for each word, after the word before it.
        val expected = setOf(Triple("london", "hello", 1), Triple("hello", "there", 1), Triple("there", "friend", 1))
        assertEquals(expected, plain.pairs())
        assertEquals(expected, emptied.pairs())
    }

    @Test
    fun `a strip word over a saved text's word the keys did not type is not the person's own`() {
        val engine = open().apply { setSavedTexts(listOf(address)) }
        engine.accept("221B Baker Street, ", "Londoner", owned = false)
        assertTrue(engine.savedTextBlocks("221B Baker Street, Londoner "))
        // Typed here, the same tap is the person's own.
        engine.accept("221B Baker Street, ", "Londoner", owned = true)
        assertFalse(engine.savedTextBlocks("221B Baker Street, Londoner "))
    }

    @Test
    fun `the word after a saved text's word is not learned`() {
        val engine = open().apply { setSavedTexts(listOf(address)) }
        val before = engine.journal()
        assertTrue(engine.savedTextBlocks("$address "))
        assertTrue(engine.savedTextBlocks("Street "))
        assertFalse(engine.learn("$address hello"))
        assertArrayEquals(before, engine.journal())
        // The blocked word is still the person's own: the next word is learned after it.
        assertTrue(engine.learn("$address hello there"))
        assertEquals(setOf(Triple("hello", "there", 1)), engine.pairs() - LearnedPairJournal.decode(before).state.entries().toSet())
    }

    @Test
    fun `a tapped word after a saved text's word is not learned either`() {
        val engine = open().apply { setSavedTexts(listOf(address)) }
        val before = engine.journal()
        engine.accept("$address ", "hello")
        assertArrayEquals(before, engine.journal())
        engine.accept("$address hello ", "there")
        assertTrue(Triple("hello", "there", 1) in engine.pairs())
    }

    @Test
    fun `a word the person typed here teaches as usual, even when a saved text holds it`() {
        val engine = open().apply { setSavedTexts(listOf(address)) }
        assertTrue(engine.learn("we met in"))
        assertTrue(engine.learn("we met in London"))
        assertFalse(engine.savedTextBlocks("we met in London "))
        assertTrue(engine.learn("we met in London hello"))
        assertTrue(Triple("london", "hello", 1) in engine.pairs())
        // The same words standing somewhere else were not typed there.
        assertTrue(engine.savedTextBlocks("see London "))
    }

    @Test
    fun `with Learn my phrases on, a word typed at the start of a new line is the person's own`() {
        for (phrases in listOf(false, true)) {
            val engine = open(TypingEngine.Options(personalLearning = phrases)).apply { setSavedTexts(listOf(address)) }
            // Return: the host ends the phrase, then the words are typed on the new line.
            engine.resetContext()
            assertTrue("phrases $phrases", engine.learn("see you\nLondon"))
            assertFalse("phrases $phrases", engine.savedTextBlocks("see you\nLondon "))
            assertTrue("phrases $phrases", engine.learn("see you\nLondon hello"))
            assertTrue("phrases $phrases", Triple("london", "hello", 1) in engine.pairs())
            // The same for a word tapped in the strip there.
            engine.resetContext()
            engine.accept("see you\n", "Street", owned = true)
            assertFalse("phrases $phrases", engine.savedTextBlocks("see you\nStreet "))
            assertTrue("phrases $phrases", engine.learn("see you\nStreet party"))
            assertTrue("phrases $phrases", Triple("street", "party", 1) in engine.pairs())
            // A word that stands after a new line and was not typed here is still not the person's own.
            engine.resetContext()
            assertTrue("phrases $phrases", engine.savedTextBlocks("see you\nLondon "))
        }
    }

    @Test
    fun `an edit that is not typing ends what is known to be typed here`() {
        val engine = open().apply { setSavedTexts(listOf(address)) }
        assertTrue(engine.learn("we met in London"))
        engine.resetContext()
        assertTrue(engine.savedTextBlocks("we met in London "))
        assertFalse(engine.learn("we met in London hello"))
        assertFalse(Triple("london", "hello", 1) in engine.pairs())
    }

    @Test
    fun `a sentence start and a word in no saved text are never blocked`() {
        val engine = open().apply { setSavedTexts(listOf(address)) }
        assertFalse(engine.savedTextBlocks(""))
        assertFalse(engine.savedTextBlocks("London. "))
        assertFalse(engine.savedTextBlocks("London, "))
        assertFalse(engine.savedTextBlocks("hello "))
        assertTrue(engine.learn("London. Hello"))
    }

    @Test
    fun `letters typed onto a saved word still block, short saved words do not`() {
        val engine = open().apply { setSavedTexts(listOf("Street the")) }
        assertTrue(engine.savedTextBlocks("streethello "))
        assertTrue(engine.savedTextBlocks("mainstreet "))
        assertTrue(engine.savedTextBlocks("the "))
        assertFalse(engine.savedTextBlocks("then "))
        assertFalse(engine.savedTextBlocks("bathe "))
    }

    @Test
    fun `the rule agrees with the app's SavedWords`() {
        val texts = listOf(address, "luke@example.com", "On my way 🚗", "the")
        val engine = open().apply { setSavedTexts(texts) }
        val saved = SavedWords.of(texts)
        for (previous in listOf("London", "street", "Streets", "baker", "bakery", "example", "com", "the", "then", "hello", "way", "away", "luke")) {
            assertEquals(previous, SavedWords.blocks(previous, null, saved), engine.savedTextBlocks("$previous "))
        }
    }

    @Test
    fun `forget ends what is known to be typed here`() {
        val engine = open().apply { setSavedTexts(listOf(address)) }
        assertTrue(engine.learn("in London"))
        engine.forget()
        assertTrue(engine.savedTextBlocks("in London "))
    }
}
