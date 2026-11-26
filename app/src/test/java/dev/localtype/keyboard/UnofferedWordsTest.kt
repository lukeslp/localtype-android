package dev.localtype.keyboard

import dev.localtype.keyboard.SpellCheckRules.Position
import dev.localtype.keyboard.SpellCheckRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Dropped-g spellings ("somethin", "nothin") from the frequency list are recognized when typed and never offered. */
class UnofferedWordsTest {
    private fun asset(name: String) = File("src/main/assets/$name")
    private fun listed(name: String) = asset(name).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.substringBefore('\t').lowercase() }

    /** Entries the rule finds that are names or other words, not dropped g's; they stay offered. */
    private val reviewedAsWords = setOf("jin", "min", "qin", "rin", "palin", "larkin", "tintin")

    @Test fun `the list is every dropped-g form the frequency list carries, by rule, less the reviewed names`() {
        val ranked = listed("words_en.txt")
        val scowl = listed("words_known.txt").toSet()
        val modern = listed("words_en_modern.txt").toSet()
        val real = ranked.toSet() + scowl + modern
        val byRule = ranked.filterTo(HashSet()) { it.endsWith("in") && it + "g" in real && it !in scowl && it !in modern }
        assertEquals(byRule - reviewedAsWords, UnofferedWords.ENGLISH)
        assertTrue(reviewedAsWords.all { it in byRule })
        // Real words that end in -in are not touched, whether or not the rule could reach them.
        for (word in listOf("begin", "within", "cabin", "robin", "again", "main", "rain", "skin", "violin", "margin", "cousin")) {
            assertFalse(word, word in UnofferedWords.ENGLISH)
        }
        assertTrue(UnofferedWords.forLanguage(LanguageProfile.SPANISH).isEmpty())
    }

    private val dictionary by lazy { WordDictionary.parse((asset("words_en.txt").readLines() + asset("words_en_modern.txt").readLines()).asSequence()) }
    private val prior by lazy { CorpusPrior.read(asset("corpus_prior.bin").readBytes()) }

    /** The service's English model (`buildLocalModel`), with nothing learned. */
    private fun model() = AdaptiveNgramModel(dictionary = dictionary, prior = prior, unoffered = UnofferedWords.ENGLISH)

    @Test fun `no prefix, context or learned count puts one in the strip, and none is a fix for a slip`() {
        val model = model()
        val corrector = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })
        val contexts = listOf("", "I was ", "what are you ", "Nothing. ", "good ")
        val offered = ArrayList<String>()
        for (word in UnofferedWords.ENGLISH) {
            for (length in 1..word.length) for (context in contexts) {
                if (word in model.predict(context + word.take(length), 9)) offered += "$context${word.take(length)} → $word"
            }
            // Typed on purpose it is a word, not a typo.
            assertNull(word, corrector.propose(word))
            // A slip next to it never turns into it: a letter dropped, swapped or replaced.
            val slips = word.indices.map { word.removeRange(it, it + 1) } +
                (0 until word.lastIndex).map { word.substring(0, it) + word[it + 1] + word[it] + word.substring(it + 2) } +
                word.indices.flatMap { i -> ('a'..'z').map { word.substring(0, i) + it + word.substring(i + 1) } }
            for (slip in slips.distinct()) if (slip != word) assertNotEquals(slip, word, corrector.propose(slip)?.word)
        }
        assertEquals(emptyList<String>(), offered)
        // Without the filter the list did offer them: the bug this guards.
        assertTrue("somethin" in AdaptiveNgramModel(dictionary = dictionary, prior = prior).predict("I want somethi", 3))
    }

    @Test fun `typed often it is still never offered, while a word the person added is`() {
        val model = model()
        repeat(5) { model.accept("i'm", "comin") }
        assertTrue(model.knows("comin"))
        assertFalse("comin" in model.predict("I'm ", 20))
        assertFalse("comin" in model.predict("I'm comi", 20))

        assertEquals(listOf("nothing"), NeuralRanking.eligibleWords(listOf("nothin", "nothing"), "", emptySet()))
        val ranked = NeuralRanking.rank(listOf("nothin", "nothing", "note"), listOf("nothin", "not"), listOf("nothin" to 4), "not", emptySet())
        assertFalse("nothin" in ranked)
        assertTrue("nothin" in NeuralRanking.rank(listOf("nothing"), emptyList(), emptyList(), "noth", emptySet(), added = listOf("nothin")))
    }

    private fun iphone() = TypingEngine(
        language = LanguageProfile.ENGLISH,
        words = asset("words_en.txt").readText(),
        swears = asset("words_en_profanity.txt").readText(),
        lexicon = asset("words_known.txt").readText(),
        modern = asset("words_en_modern.txt").readText(),
        prior = asset("corpus_prior.bin").readBytes(),
        journal = null,
        suggestSwears = true,
    )

    @Test fun `the iPhone engine and the spell checker recognize them and never offer them`() {
        val engine = iphone()
        assertFalse(engine.suggestions("I want somethi").any { it.equals("somethin", ignoreCase = true) })
        assertNull(engine.correction("I want somethin"))
        assertTrue(engine.syncAddedWords(listOf("somethin")))
        assertTrue(engine.suggestions("I want somethi").any { it.equals("somethin", ignoreCase = true) })

        val spelling = SpellCheckLists.build(LanguageProfile.ENGLISH) { asset(it).reader() }.rules
        assertEquals(Verdict.KNOWN, spelling.checkWord("nothin", Position.MID_SENTENCE).verdict)
        assertFalse("nothin" in spelling.checkWord("nothim", Position.MID_SENTENCE).suggestions)
    }
}
