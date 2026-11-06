package dev.localtype.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Spanish in the running keyboard: its keys, holds, marks, space bar and dictation (docs/languages-route-a-2026-10-17.md). */
class SpanishKeyboardTest {
    private val spanish = LanguageProfile.SPANISH
    private val english = LanguageProfile.ENGLISH

    private fun letters(language: LanguageProfile, numberRow: Boolean = false, spaceLabel: String = "") =
        KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, language = language, spaceLabel = spaceLabel)

    private fun KeyboardLayout.key(label: String) = rows.flatten().first { it.label == label }

    @Test
    fun `a hold offers the spanish accent before the digit, so a still hold types it`() {
        val keys = letters(spanish)
        assertEquals(listOf("é", "3"), keys.key("e").alternates.take(2))
        assertEquals(listOf("ú", "ü", "7"), keys.key("u").alternates.take(3))
        assertEquals(listOf("í", "8"), keys.key("i").alternates.take(2))
        assertEquals(listOf("ó", "9"), keys.key("o").alternates.take(2))
        assertEquals("á", keys.key("a").alternates.first())
        // A letter with no Spanish accent keeps the digit first, and nothing is offered twice.
        assertEquals("1", keys.key("q").alternates.first())
        keys.rows.flatten().forEach { assertEquals(it.alternates.distinct(), it.alternates) }
        // With the number row on, the accent leads on its own.
        assertEquals("é", letters(spanish, numberRow = true).key("e").alternates.first())
    }

    @Test
    fun `english keys are exactly as they were`() {
        val keys = letters(english)
        assertEquals(listOf("3") + KeyboardLayout.ACCENTS.getValue('e'), keys.key("e").alternates)
        assertEquals(KeyboardLayout.ACCENTS.getValue('a'), keys.key("a").alternates)
        assertEquals(listOf("?", "!", ",", ";", ":", "…", "'", "\""), keys.key(".").alternates)
        val space = keys.rows.last().first { it.action == KeyAction.Space }
        assertEquals("", space.label)
        assertEquals("Space", space.spokenLabel)
    }

    @Test
    fun `spanish opens a question or exclamation from the period key and the symbols`() {
        assertEquals(listOf("?", "¿", "!", "¡"), letters(spanish).key(".").alternates.take(4))
        val symbols = KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, showSwitchKey = false, language = spanish)
        assertEquals(listOf("¿"), symbols.key("?").alternates)
        assertEquals(listOf("¡"), symbols.key("!").alternates)
        val englishSymbols = KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, showSwitchKey = false)
        assertTrue(englishSymbols.key("?").alternates.isEmpty())
        assertEquals("Opening question mark", KeyboardLayout.spokenName("¿"))
    }

    @Test
    fun `the space bar names the language when there is a choice`() {
        val space = letters(spanish, spaceLabel = "Español").rows.last().first { it.action == KeyAction.Space }
        assertEquals("Español", space.label)
        assertEquals("Space, Español", space.spokenLabel)
        // A web address field keeps its .com key beside a labelled space bar.
        val url = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, language = spanish, spaceLabel = "Español")
        url.rows.forEach { row -> assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.0001f) }
    }

    @Test
    fun `a sentence still starts after an opening mark`() {
        val marks = spanish.openingMarks
        assertTrue(AdaptiveNgramModel.isSentenceStart("¿", marks))
        assertTrue(AdaptiveNgramModel.isSentenceStart("Hola. ¿", marks))
        assertTrue(AdaptiveNgramModel.isSentenceStart("¡Hola! ¿qu", marks))
        assertTrue(AdaptiveNgramModel.isSentenceStart("Hola.\n¡", marks))
        assertFalse(AdaptiveNgramModel.isSentenceStart("Hola ¿", marks))
        assertFalse(AdaptiveNgramModel.isSentenceStart("Mira, ¿", marks))
        // English reads the marks as it always did.
        assertFalse(AdaptiveNgramModel.isSentenceStart("Hola. ¿"))
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList(), openingMarks = marks)
        assertTrue(model.isSentenceStart("Bien. ¿"))
        assertFalse(AdaptiveNgramModel().isSentenceStart("Bien. ¿"))
    }

    @Test
    fun `dictation asks for the phone's own spanish, or spain's`() {
        assertEquals("es-MX", spanish.speechTag(listOf(Locale("en", "US"), Locale("es", "MX"))))
        assertEquals("es-US", spanish.speechTag(listOf(Locale("es", "US"))))
        assertEquals("es-ES", spanish.speechTag(listOf(Locale("en", "US"))))
        assertEquals("es-ES", spanish.speechTag(listOf(Locale("es"))))
        assertEquals("en-US", english.speechTag(listOf(Locale("es", "MX"))))
    }

    @Test
    fun `each language has its own lists and only english is english`() {
        assertTrue(english.english)
        assertFalse(spanish.english)
        assertEquals(listOf("words_en.txt", "words_en_profanity.txt", "words_known.txt"),
            listOf(english.wordsAsset, english.profanityAsset, english.lexiconAsset))
        assertEquals(listOf("words_es.txt", "words_es_profanity.txt", "words_known_es.txt"),
            listOf(spanish.wordsAsset, spanish.profanityAsset, spanish.lexiconAsset))
        assertEquals(spanish, LanguageProfile.of("es"))
        assertNull(LanguageProfile.of("fr"))
    }

    @Test
    fun `spanish one-letter words are offered, learned and kept in personal learning`() {
        val words = WordDictionary.parse(sequenceOf("y\t900000", "yo\t500000", "casa\t1000", "perro\t800"), spanish)
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = words, sentenceStarters = emptyList(),
            oneLetterWords = spanish.oneLetterWords)
        assertTrue(model.learnTypedWord("casa y", persist = false))
        assertTrue("y" in model.predict("casa ", 3))
        assertTrue(model.predict("casa y", 3).first() == "y")
        // English keeps its two: "y" is not an English word.
        val englishModel = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = words, sentenceStarters = emptyList())
        assertFalse(englishModel.learnTypedWord("casa y", persist = false))
        assertTrue(CifgModel.isOfferable("y", spanish.oneLetterWords))
        assertFalse(CifgModel.isOfferable("y"))
        assertEquals(listOf("y"), NeuralRanking.eligibleWords(listOf("y"), "", emptySet(), spanish))
        assertTrue(NeuralRanking.eligibleWords(listOf("y"), "", emptySet()).isEmpty())
    }

    @Test
    fun `a spanish personal-learning snapshot holding y round-trips, and english still refuses it`() {
        val state = PersonalLearningState(oneLetterWords = spanish.oneLetterWords)
        state.record(listOf("perro"), "y", LearningOrigin.TYPED)
        state.record(listOf("perro"), "y", LearningOrigin.TYPED)
        val entries = state.entries()
        assertEquals(listOf("y"), entries.map { it.word })
        val bytes = PersonalLearningCodec.encode(entries, spanish.oneLetterWords)
        assertEquals(entries, PersonalLearningCodec.decode(bytes, spanish.oneLetterWords))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { PersonalLearningCodec.decode(bytes) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { PersonalLearningCodec.encode(entries) }
    }

    @Test
    fun `added words follow the language they are added to`() {
        assertEquals("y", AddedWords.normalize("Y", spanish))
        assertNull(AddedWords.normalize("y"))
        assertEquals("mañana", AddedWords.normalize("Mañana", spanish))
        val store = object : AdaptiveNgramModel.LearningStore {
            val rows = ArrayList<Triple<String, String, Int>>()
            override fun entries() = rows.toList()
            override fun record(previous: String, next: String) { rows += Triple(previous, next, 1) }
            override fun clear() = rows.clear()
        }
        assertEquals(AddedWords.Result.ADDED, AddedWords.add(store, "o", spanish))
        assertEquals(AddedWords.Result.INVALID, AddedWords.add(store, "o"))
        assertEquals(listOf("o"), AddedWords.words(store.entries()))
    }

    @Test
    fun `spanish ships the prior that cleared its bar, at the weight chosen on dev`() {
        assertEquals("corpus_prior_es.bin", spanish.priorAsset)
        assertEquals(80f, spanish.priorWeight)
        // English's are the asset and the shipped default weight, exactly as before languages.
        assertEquals(CorpusPrior.ASSET, english.priorAsset)
        assertEquals(AdaptiveNgramModel.PriorWeights.DEFAULT, AdaptiveNgramModel.PriorWeights(weight = english.priorWeight))
        val prior = java.io.File("src/main/assets/corpus_prior_es.bin").inputStream().use { CorpusPrior.read(it) }
        assertEquals(CorpusSpanishEvaluationTest.PRIOR_SHA256, prior.sha256)
        assertTrue(CorpusSpanishEvaluationTest.CHOSEN == AdaptiveNgramModel.PriorWeights(weight = spanish.priorWeight) && CorpusSpanishEvaluationTest.BAR_AGREED)
        // Its followers become candidates, so every word must be one the keyboard may offer: no slur, nothing outside the lists.
        fun words(name: String) = java.io.File("src/main/assets/$name").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        val allowed = words("words_es.txt") + words("words_es_profanity.txt") + CorpusPrior.SENTENCE_START
        val words = prior.allWords()
        assertTrue(words.size > 10_000)
        assertTrue(words.all { it in allowed })
        assertTrue(words.none { SuggestionWordRules.isExcluded(it, spanish) })
        // The prior's context shows with nothing learned: after "muchas", "veces" leads, which frequency alone never offers there.
        // It counts web and news text, not chat, so "gracias" isn't among its first three.
        val dictionary = WordDictionary.parse(java.io.File("src/main/assets/words_es.txt").readLines().asSequence(), spanish)
        fun model(prior: CorpusPrior?) = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
            prior = prior, priorWeights = AdaptiveNgramModel.PriorWeights(weight = spanish.priorWeight), openingMarks = spanish.openingMarks, oneLetterWords = spanish.oneLetterWords)
        assertEquals("veces", model(prior).predict("muchas ", 3).first())
        assertTrue("veces" !in model(null).predict("muchas ", 3))
    }

    @Test
    fun `english contraction and possessive fixes stay out of spanish`() {
        // The contraction table answers without the dictionary, so a list without "don't" still shows which language asked it.
        val lines = sequenceOf("dont\t5", "amigo\t800", "amigos\t700")
        val englishWords = WordDictionary.parse(lines)
        val spanishWords = WordDictionary.parse(lines, spanish)
        assertEquals("don't", Corrector({ englishWords }).correct("dont"))
        assertNull(Corrector({ spanishWords }, language = { spanish }).correct("dont"))
        assertNull(Corrector({ spanishWords }, language = { spanish }).possessiveSuggestion("amigos"))
    }
}
