package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The iPhone keyboard's Learned words page on the engine it calls: TypingEngine.learnedList, learnedPairCount and
 * forgetPair, which list and remove what Android's settings list and remove (SettingsActivity.refreshLearnedSummary).
 */
class TypingEngineLearnedListTest {
    private fun asset(name: String) = File("src/main/assets/$name")

    private fun open(journal: ByteArray? = null, phrases: Boolean = false, personal: ByteArray? = null) = TypingEngine(
        language = LanguageProfile.ENGLISH,
        words = asset("words_en.txt").readText(),
        swears = asset("words_en_profanity.txt").readText(),
        lexicon = asset("words_known.txt").readText(),
        modern = asset("words_en_modern.txt").readText(),
        prior = asset("corpus_prior.bin").readBytes(),
        journal = journal,
        suggestSwears = true,
        emoji = null,
        options = TypingEngine.Options(personalLearning = phrases),
    ).also { engine -> personal?.let { engine.restorePersonal(it) } }

    private fun TypingEngine.pairs(limit: Int = 50) = learnedList(limit).map { Triple(it.previous, it.next, it.uses) }

    @Test
    fun `the list leaves out added words, is ordered by use, and forgetting one pair removes only it`() {
        val engine = open()
        repeat(2) { assertTrue(engine.learn("purple elephant")) }
        assertTrue(engine.learn("purple giraffe"))
        assertTrue(engine.learn("Thanks"))
        assertTrue(engine.syncAddedWords(listOf("Blarg")))

        // What Android's list shows: every entry but the added-words row, most used first.
        val android = LearnedPairJournal.decode(engine.journal()).state.entries()
            .filter { it.first != AddedWords.CONTEXT }.sortedByDescending { it.third }
        assertEquals(android, engine.pairs())
        assertEquals(Triple("purple", "elephant", 2), engine.pairs().first())
        assertTrue(engine.pairs().none { it.first == AddedWords.CONTEXT })
        assertEquals(android.size, engine.learnedPairCount)
        assertEquals(engine.learnedPairs - 1, engine.learnedPairCount)
        assertEquals(1, engine.learnedList(1).size)
        assertEquals(0, engine.learnedList(0).size)
        val start = engine.learnedList(50).single { it.next == "thanks" }
        assertTrue(start.sentenceStart)
        assertEquals(AdaptiveNgramModel.START, start.previous)
        assertFalse(engine.learnedList(50).first().sentenceStart)

        assertTrue(engine.forgetPair("purple", "elephant"))
        assertFalse(engine.forgetPair("purple", "elephant"))
        assertFalse(engine.forgetPair("purple", "nothing"))
        assertFalse(engine.forgetPair(AddedWords.CONTEXT, "blarg"))
        assertEquals(listOf("blarg"), engine.addedWords)
        assertEquals(android.size - 1, engine.learnedPairCount)
        assertTrue(engine.pairs().any { it.first == "purple" && it.second == "giraffe" })

        val reopened = open(engine.journal())
        assertTrue(reopened.pairs().none { it.first == "purple" && it.second == "elephant" })
        assertTrue(reopened.pairs().any { it.first == "purple" && it.second == "giraffe" })
        assertEquals(listOf("blarg"), reopened.addedWords)
        assertTrue(reopened.forgetPair(AdaptiveNgramModel.START, "thanks"))
    }

    @Test
    fun `a forgotten pair stops being offered at once`() {
        val engine = open()
        repeat(8) { engine.learn("purple elephant") }
        assertTrue("elephant" in engine.suggestions("purple "))
        assertTrue(engine.forgetPair("purple", "elephant"))
        assertFalse("elephant" in engine.suggestions("purple "))
        assertEquals(open().suggestions("purple "), engine.suggestions("purple "))
    }

    @Test
    fun `forgetting a pair keeps capitals and tap offsets`() {
        val engine = open()
        "Hello".forEach { engine.typed(it.toString(), 0.2, 0.1) }
        engine.correction("Hello")
        assertTrue(engine.learn("Hello"))
        engine.typed(" ", Double.NaN, Double.NaN)
        assertTrue(engine.syncAddedWords(listOf("zorblax")))
        assertTrue(engine.learn("I use ZorbLax"))
        val spellings = engine.spellings()
        val offsets = engine.offsets()
        assertEquals(1, engine.learnedSpellingCount)
        assertEquals(5, engine.tapSamples)
        assertTrue(engine.forgetPair("use", "zorblax"))
        assertEquals(spellings, engine.spellings())
        assertEquals(offsets, engine.offsets())
    }

    @Test
    fun `with phrases on, forgetting a pair drops the phrases that end in it and no others`() {
        val engine = open(phrases = true)
        var text = ""
        for (word in "see you thursday".split(" ")) { text += word; engine.learn(text); text += " " }
        engine.resetContext()
        text = ""
        for (word in "purple elephant".split(" ")) { text += word; engine.learn(text); text += " " }
        fun rows() = PersonalLearningCodec.decode(engine.personal(), LanguageProfile.ENGLISH.oneLetterWords)
        assertTrue(rows().any { it.context.last() == "you" && it.word == "thursday" })
        val others = rows().filterNot { it.context.last() == "you" && it.word == "thursday" }.map { it.context to it.word }
        assertTrue(others.isNotEmpty())
        assertTrue(engine.forgetPair("you", "thursday"))
        assertEquals(others, rows().map { it.context to it.word })
        // As Android's PersonalLearningStore.removePair leaves it.
        assertTrue(engine.personalChanged)
    }
}
