package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A typo the keyboard learned or kept ("tge", kept with a tap on the quoted word, then typed again) is left alone; once
 * the person blocks it, it is corrected again as an unlearned typo would be, while real words stay protected.
 */
class BlockedTypoCorrectionTest {
    /** Learned pairs held in memory, as the journal would hand them to a model built after a restart. */
    private class Pairs(vararg entries: Triple<String, String, Int>) : AdaptiveNgramModel.LearningStore {
        private val pairs = entries.toMutableList()
        override fun entries() = pairs.toList()
        override fun record(previous: String, next: String) { pairs += Triple(previous, next, 1) }
        override fun clear() = pairs.clear()
    }

    /** The service's corrector, built the way `LocalTypeInputMethodService.onCreate` builds it. */
    private fun androidCorrector(model: AdaptiveNgramModel, kept: Set<String>, blocked: Set<String>) = Corrector(
        dictionary = { TypoCorpus.dictionary },
        isKnown = { model.knows(it) || (it in kept && it !in blocked) },
        lexicon = { TypoCorpus.lexicon },
        contextCount = { previous, word -> model.pairCount(previous, word) },
        blocked = { it in blocked },
        isKept = { (it in kept && it !in blocked) || (model.isAddedWord(it) && it !in blocked) },
    )

    @Test fun `on Android a learned typo is left alone until it is blocked, and an unblock brings the learning back`() {
        val blocked = HashSet<String>()
        val model = AdaptiveNgramModel(Pairs(Triple("saw", "tge", 3)), seedPhrases = emptyList(), sentenceStarters = emptyList(),
            dictionary = TypoCorpus.dictionary, excluded = { it in blocked })
        val fresh = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })
        assertEquals("an unlearned typo is fixed", "the", fresh.correct("tge"))

        assertTrue(model.knows("tge"))
        assertNull(androidCorrector(model, emptySet(), blocked).propose("tge", previous = "saw"))

        blocked += "tge"
        assertFalse("a blocked word is not learned while it stays blocked", model.knows("tge"))
        val unlearned = androidCorrector(model, emptySet(), blocked)
        assertEquals(fresh.propose("tge"), unlearned.propose("tge", previous = "saw"))
        assertEquals("the", unlearned.correct("tge", previous = "saw"))
        assertEquals("the", unlearned.suggestForStrip("tge", previous = "saw"))

        blocked.clear()
        assertTrue("an unblock keeps the earlier learning", model.knows("tge"))
        assertNull(androidCorrector(model, emptySet(), blocked).propose("tge", previous = "saw"))
    }

    @Test fun `on Android a kept typo is corrected again once blocked, and a blocked real word stays a word`() {
        val blocked = HashSet<String>()
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList(), dictionary = TypoCorpus.dictionary,
            excluded = { it in blocked })
        assertNull(androidCorrector(model, setOf("tge"), blocked).propose("tge"))
        blocked += "tge"
        assertEquals("the", androidCorrector(model, setOf("tge"), blocked).correct("tge"))

        // Blocking a real word keeps it out of suggestions; the lists still say it is a word, so it is never "fixed".
        blocked += "there"
        assertNull(androidCorrector(model, emptySet(), blocked).propose("there"))
    }

    private fun asset(name: String) = File("src/main/assets/$name")

    private fun iphone(journal: ByteArray? = null, blocked: List<String> = emptyList()) = TypingEngine(
        language = LanguageProfile.ENGLISH,
        words = asset("words_en.txt").readText(),
        swears = asset("words_en_profanity.txt").readText(),
        lexicon = asset("words_known.txt").readText(),
        modern = asset("words_en_modern.txt").readText(),
        prior = asset("corpus_prior.bin").readBytes(),
        journal = journal,
        suggestSwears = true,
        options = TypingEngine.Options(blocked = blocked),
    )

    @Test fun `on the iPhone a tapped typo is kept, and blocking it brings the fix back after a reopen`() {
        val engine = iphone()
        assertEquals("the", engine.correction("I saw tge"))
        // The quoted word tapped in the strip: learned, so the next "tge" is left alone.
        engine.accept("I saw ", "tge", owned = true)
        assertNull(engine.correction("I saw tge"))

        val reopened = iphone(engine.journal(), blocked = listOf("tge"))
        assertEquals("the", reopened.correction("I saw tge"))
        assertFalse("tge" in reopened.suggestions("I saw tg").map { it.lowercase() })
    }
}
