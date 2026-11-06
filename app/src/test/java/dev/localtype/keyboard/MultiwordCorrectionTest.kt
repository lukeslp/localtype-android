package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class MultiwordCorrectionTest {
    private val corrector = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })
    private val search = MultiwordCorrection(TypoCorpus.dictionary)
    @Test fun offersMissingSpacesAndBottomRowSeparatorSlips() {
        val exact = MultiwordCorrection(WordDictionary.parse(sequenceOf("thank\t100", "you\t100")))
        assertEquals("thank you", exact.suggest("thankyou", false) { emptyList() }?.text)
        // The bundled list recognizes this spelling, so production must preserve it.
        assertNull(search.suggest("thankyou", false) { corrector.candidates(it).candidates.take(3).map { c -> c.word } })
        val prior = java.io.File("src/main/assets/corpus_prior.bin").inputStream().use(CorpusPrior::read)
        val example = search.suggest("itndossnt", false, context = { a, b -> prior.followers(a).firstOrNull { it.word == b }?.logProbability }) { corrector.candidates(it).candidates.take(3).map { c -> c.word } }
        assertEquals("it doesn't", example?.text)
        val separator = MultiwordCorrection(WordDictionary.parse(sequenceOf("it\t100", "doesnt\t100", "doesn't\t100")))
        assertEquals('n', separator.suggest("itndoesnt", false) { emptyList() }?.separatorLetter)
    }
    @Test fun protectedAcronymsNamesIdentifiersAndRealWordsNeverSplit() {
        for (raw in listOf("xkcd", "NASA", "OpenAI", "McKay", "kjones", "thankyou", "itndossnt")) {
            assertNull(raw, search.suggest(raw, true) { error("protected spelling must not search") })
        }
        for (raw in listOf("NASA", "McKay", "a.com", "ab_cd", "xkcd", "thanks", "a".repeat(25))) {
            assertNull(raw, search.suggest(raw, false) { corrector.candidates(it).candidates.take(3).map { c -> c.word } })
        }
    }
    @Test fun unusualPairsMatchEveryStringPairInTheDictionary() {
        // The pairs as Strings, as they were kept before 2026-10-26; the compact table must answer the same.
        fun reference(dictionary: WordDictionary): (String) -> Set<String> {
            val pairs = dictionary.allWords().flatMap { it.zipWithNext().map { (a, b) -> "$a$b" } }.toHashSet()
            return { word -> word.zipWithNext().map { (a, b) -> "$a$b" }.filterTo(HashSet()) { it !in pairs } }
        }
        val english = reference(TypoCorpus.dictionary)
        val words = TypoCorpus.dictionary.allWords().take(2000) +
            listOf("qzxkjq", "itndossnt", "thankyou", "naïve", "café", "jalapeño", "don't", "a", "", "QZ", "x1y2", "😀ab")
        for (word in words) assertEquals(word, english(word), search.unusualPairs(word))
        val spanish = WordDictionary.parse(java.io.File("src/main/assets/words_es.txt").readLines().asSequence(), LanguageProfile.SPANISH)
        val accented = MultiwordCorrection(spanish)
        val spanishReference = reference(spanish)
        for (word in spanish.allWords().take(2000) + listOf("ñzx", "acción", "pingüino", "qzx")) {
            assertEquals(word, spanishReference(word), accented.unusualPairs(word))
        }
    }
    @Test fun arbitraryUnusualPairsAreNotAnAutomaticDecisionOrAPlausibleSplit() {
        assertTrue(search.unusualPairs("qzxkjq").isNotEmpty())
        assertNull(search.suggest("qzxkjq", false) { corrector.candidates(it).candidates.take(3).map { c -> c.word } })
        var calls = 0
        search.suggest("qzxkjqthankyou", false) { calls++; emptyList() }
        assertTrue(calls <= 8)
    }
    @Test fun aLetterTypedForTheSpaceBeatsTheSingleWordGuess() {
        // Reported from a month of daily use: "ofbthe" was offered as "other", never as "of the".
        val prior = java.io.File("src/main/assets/corpus_prior.bin").inputStream().use(CorpusPrior::read)
        val words = { raw: String, exactOnly: Boolean ->
            search.suggest(raw, false, context = { a, b -> prior.followers(a).firstOrNull { it.word == b }?.logProbability },
                exactOnly = exactOnly) { corrector.candidates(it).candidates.take(3).map { c -> c.word } }
        }
        for ((raw, meant) in listOf("ofbthe" to "of the", "tonthe" to "to the", "fornthe" to "for the", "andnthen" to "and then")) {
            for (exactOnly in listOf(false, true)) {
                val split = words(raw, exactOnly)
                assertEquals(raw, meant, split?.text)
                assertTrue(raw, split!!.oneSlip)
                assertTrue(raw, MultiwordCorrection.preferred(split, "other"))
            }
        }
        // The same phrase reached through a repair must not hide the exact cut ("ofn" repaired to "in").
        assertEquals("of the", words("ofnthe", false)?.text)
        assertEquals("on the", words("onbthe", false)?.text)
        // Short rare entries in the list make plenty of exact cuts; only a pair the prior knows can win.
        for (raw in listOf("batbbet", "hamboi", "munds", "gabge")) {
            val split = words(raw, true)
            assertFalse("$raw ${split?.text}", MultiwordCorrection.preferred(split, "single"))
        }
        // Without the prior nothing replaces a single-word offer.
        assertFalse(search.suggest("ofbthe", false, exactOnly = true) { emptyList() }!!.oneSlip)
    }
    @Test fun aLeftOutSpaceOrAOneLetterWordStaysBehindTheSingleWordGuess() {
        val prior = java.io.File("src/main/assets/corpus_prior.bin").inputStream().use(CorpusPrior::read)
        val left = search.suggest("inthe", false, context = { a, b -> prior.followers(a).firstOrNull { it.word == b }?.logProbability }) {
            corrector.candidates(it).candidates.take(3).map { c -> c.word }
        }!!
        assertTrue(left.knownPair)
        assertEquals("in the", left.text)
        assertNull(left.separatorLetter)
        assertFalse(left.oneSlip)
        assertFalse(MultiwordCorrection.preferred(left, "it"))
        assertTrue(MultiwordCorrection.preferred(left, null))
        val one = MultiwordCorrection(WordDictionary.parse(sequenceOf("a\t100", "big\t100"))).suggest("anbig", false, context = { _, _ -> -1f }) { emptyList() }!!
        assertTrue(one.knownPair)
        assertEquals("a big", one.text)
        assertEquals('n', one.separatorLetter)
        assertFalse(one.oneSlip)
        assertFalse(MultiwordCorrection.preferred(one, "abig"))
        assertFalse(MultiwordCorrection.preferred(null, null))
        // Without repairs a split needing one is not found, and no repair is attempted.
        assertNull(search.suggest("itndossnt", false, exactOnly = true) { error("exact search must not repair") })
    }
    @Test fun blockedPartsNeverBecomePhraseOffers() {
        val exact = MultiwordCorrection(WordDictionary.parse(sequenceOf("thank\t100", "you\t100")))
        assertNull(exact.suggest("thankyou", false, blocked = { it == "you" }) { emptyList() })
    }
}
