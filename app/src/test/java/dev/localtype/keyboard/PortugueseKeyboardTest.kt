package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Portuguese in the keyboard: Android's Portuguese layout, its holds, its words and its place in the chooser. */
class PortugueseKeyboardTest {
    private val portuguese = LanguageProfile.PORTUGUESE

    private fun letters(numberRow: Boolean = false) =
        KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, language = portuguese, spaceLabel = "Português")

    private fun KeyboardLayout.key(label: String) = rows.flatten().first { it.label == label }

    @Test
    fun `plain qwerty with c's cedilla and each vowel's accents first on the hold`() {
        val keys = letters()
        assertEquals(4, keys.rows.size)
        assertEquals("asdfghjkl".map { it.toString() }, keys.rows[1].filter { !it.isSpacer }.map { it.label })
        assertFalse(keys.rows.flatten().any { it.label == "ç" })
        assertEquals("ç", keys.key("c").alternates.first())
        assertEquals(listOf("á", "ã", "à", "â"), keys.key("a").alternates.take(4))
        assertEquals(listOf("é", "ê", "3"), keys.key("e").alternates.take(3))
        assertEquals(listOf("ó", "õ", "ô", "9"), keys.key("o").alternates.take(4))
        assertEquals(listOf("ú", "7"), keys.key("u").alternates.take(2))
        assertEquals(listOf("í", "8"), keys.key("i").alternates.take(2))
        keys.rows.flatten().forEach { assertEquals(it.alternates.distinct(), it.alternates) }
        // Its geometry is English's: the same keys where English has them.
        for (c in 'a'..'z') assertEquals("$c", LetterGeometry.QWERTY.center(c), portuguese.geometry.center(c))
        assertEquals("Space, Português", keys.rows.last().first { it.action == KeyAction.Space }.spokenLabel)
    }

    @Test
    fun `its words, one-letter words, folds and voice`() {
        assertTrue(portuguese.words)
        assertEquals(setOf("a", "e", "o", "é", "à"), portuguese.oneLetterWords)
        assertEquals("acao", portuguese.fold("ação"))
        assertEquals("voce", portuguese.fold("você"))
        assertEquals("pt-BR", portuguese.speechTag(listOf(Locale("en", "US"))))
        assertEquals("pt-PT", portuguese.speechTag(listOf(Locale("pt", "PT"))))
        assertEquals(listOf("words_pt.txt", "words_pt_profanity.txt", "words_known_pt.txt"),
            listOf(portuguese.wordsAsset, portuguese.profanityAsset, portuguese.lexiconAsset))
        assertEquals(listOf(LanguageProfile.ENGLISH, LanguageProfile.SPANISH, portuguese, LanguageProfile.HEBREW,
            LanguageProfile.WELSH, LanguageProfile.IRISH, LanguageProfile.MAORI, LanguageProfile.HAWAIIAN) + LanguageProfile.EXTRA, LanguageProfile.WITH_WORDS)
        assertEquals(LanguageProfile.WITH_WORDS + LanguageProfile.IPA, LanguageProfile.ALL)
        assertEquals(portuguese, LanguageProfile.of("pt"))
    }

    @Test
    fun `portuguese ships the prior that cleared its bar, at the weight chosen on dev`() {
        assertEquals("corpus_prior_pt.bin", portuguese.priorAsset)
        assertEquals(80f, portuguese.priorWeight)
        val prior = java.io.File("src/main/assets/corpus_prior_pt.bin").inputStream().use { CorpusPrior.read(it) }
        assertEquals(CorpusPortugueseEvaluationTest.PRIOR_SHA256, prior.sha256)
        assertTrue(CorpusPortugueseEvaluationTest.CHOSEN == AdaptiveNgramModel.PriorWeights(weight = portuguese.priorWeight) &&
            CorpusPortugueseEvaluationTest.BAR_AGREED)
        // Its followers become candidates, so every word must be one the keyboard may offer: no slur, nothing outside the lists.
        fun words(name: String) = java.io.File("src/main/assets/$name").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        val allowed = words("words_pt.txt") + words("words_pt_profanity.txt") + CorpusPrior.SENTENCE_START
        val words = prior.allWords()
        assertTrue(words.size > 10_000)
        assertTrue(words.all { it in allowed })
        assertTrue(words.none { SuggestionWordRules.isExcluded(it, portuguese) })
        // The prior's context shows with nothing learned: after "muitas", "vezes" leads, which frequency alone never offers there.
        val dictionary = WordDictionary.parse(java.io.File("src/main/assets/words_pt.txt").readLines().asSequence(), portuguese)
        fun model(prior: CorpusPrior?) = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
            prior = prior, priorWeights = AdaptiveNgramModel.PriorWeights(weight = portuguese.priorWeight),
            openingMarks = portuguese.openingMarks, oneLetterWords = portuguese.oneLetterWords)
        assertEquals("vezes", model(prior).predict("muitas ", 3).first())
        assertTrue("vezes" !in model(null).predict("muitas ", 3))
    }

    @Test
    fun `english contraction rules stay out of portuguese`() {
        val words = WordDictionary.parse(sequenceOf("dont\t5", "casa\t800"), portuguese)
        assertNull(Corrector({ words }, language = { portuguese }).correct("dont"))
    }
}
