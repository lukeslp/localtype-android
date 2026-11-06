package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdaptiveNgramModelTest {
    private val dictionary = WordDictionary.parse(
        sequenceOf("the\t1000", "there\t400", "they\t500", "then\t300", "world\t200", "work\t250", "good\t600"),
    )

    @Test
    fun `context ranks observed continuation first`() {
        val model = AdaptiveNgramModel(seedPhrases = listOf("hello world", "hello world", "hello there"))

        assertEquals("world", model.predict("hello ").first())
    }

    @Test
    fun `active prefix filters candidates`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList())

        val results = model.predict("go")

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.startsWith("go") })
        assertFalse(results.contains("the"))
    }

    @Test
    fun `seed phrases do not inflate word frequency`() {
        val model = AdaptiveNgramModel(
            seedPhrases = listOf("I think so", "thank you", "see you", "love you", "miss you"),
            dictionary = WordDictionary.parse(sequenceOf("you\t1000", "so\t400")),
        )

        assertEquals("so", model.predict("I think ").first())
    }

    @Test
    fun `accepted candidates become preferred after a matching context`() {
        val model = AdaptiveNgramModel(seedPhrases = listOf("please review"))
        repeat(4) { model.accept("please", "confirm") }

        assertEquals("confirm", model.predict("please ").first())
    }

    @Test
    fun `prefix and completed word are separated correctly`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList())

        assertEquals("writ", model.activePrefix("Please writ"))
        assertEquals("please", model.lastCompletedWord("Please writ"))
        assertEquals("please", model.lastCompletedWord("Please "))
    }

    @Test
    fun `sentence boundary resets context`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList())

        assertNull(model.lastCompletedWord("All done. Th"))
        assertNull(model.lastCompletedWord("All done! "))
        assertNull(model.lastCompletedWord("so 12 "))
        assertNull(model.lastCompletedWord("thanks\nsee"))
        assertTrue(model.isSentenceStart("thanks\n"))
        assertTrue(model.isSentenceStart("thanks\n  wh"))
        assertFalse(model.isSentenceStart("thanks wh"))
    }

    @Test
    fun `dictionary completes prefixes by frequency`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList())

        assertEquals(listOf("the", "they", "there"), model.predict("Th"))
    }

    @Test
    fun `context outranks raw frequency`() {
        val model = AdaptiveNgramModel(seedPhrases = listOf("hello world"), dictionary = dictionary)

        assertEquals("world", model.predict("hello wor").first())
        assertEquals("world", model.predict("hello ").first())
    }

    @Test
    fun `clearing learned words forgets taps`() {
        val store = RecordingStore()
        val model = AdaptiveNgramModel(learningStore = store, seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList())
        repeat(5) { model.accept("good", "work") }
        assertEquals("work", model.predict("good ").first())

        model.clearLearned(seedPhrases = emptyList())

        assertTrue(store.cleared)
        assertEquals("the", model.predict("good ").first())
    }

    @Test
    fun `unpersisted accepts are not written to the store`() {
        val store = RecordingStore()
        val model = AdaptiveNgramModel(learningStore = store, seedPhrases = emptyList())

        model.accept("secret", "words", persist = false)

        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `learned sentence starts replay without a previous word`() {
        val store = RecordingStore()
        AdaptiveNgramModel(learningStore = store, seedPhrases = emptyList()).accept(null, "Morning")

        assertEquals(listOf(AdaptiveNgramModel.START to "morning"), store.records)
        val reloaded = AdaptiveNgramModel(learningStore = store, seedPhrases = emptyList())
        assertEquals("morning", reloaded.predict("mor").first())
    }

    @Test
    fun `typed words are learned but typos are not`() {
        val store = RecordingStore()
        val model = AdaptiveNgramModel(learningStore = store, seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList())

        assertTrue(model.learnTypedWord("good work", persist = true))
        assertFalse(model.learnTypedWord("good wrok", persist = true))
        assertTrue(model.learnTypedWord("good zorp", persist = true, isKept = { it == "zorp" }))

        assertEquals(listOf("good" to "work", "good" to "zorp"), store.records)
        assertTrue(model.knows("zorp"))
    }

    @Test
    fun `asking whether a word would be learned learns nothing`() {
        val store = RecordingStore()
        val model = AdaptiveNgramModel(learningStore = store, seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList())

        assertTrue(model.learnable("good work"))
        assertFalse(model.learnable("good wrok"))
        assertTrue(model.learnable("good wrok", isKept = { it == "wrok" }))
        assertFalse(model.learnable("good "))
        assertTrue(store.records.isEmpty())
        assertEquals(0, model.pairCount("good", "work"))
    }

    @Test
    fun `a new message starts with broadly useful words`() {
        val words = WordDictionary.parse(File("src/main/assets/words_en.txt").bufferedReader().lineSequence())
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = words)

        val starts = model.predict("")
        assertTrue(starts.toString(), starts.all { it in AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS.map(String::lowercase) })
        assertTrue(model.predict("See you soon. ").all { it in AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS.map(String::lowercase) })
    }

    @Test
    fun `learned first words join the starters`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = listOf("hey"))
        repeat(6) { model.accept(null, "morning") }

        assertEquals("morning", model.predict("").first())
    }

    @Test
    fun `predict is predictScored without the scores`() {
        val model = AdaptiveNgramModel(seedPhrases = listOf("hello world", "hello there"), dictionary = dictionary)
        repeat(4) { model.accept("please", "confirm") }

        listOf("", "hello ", "hello wor", "go", "Th", "please ", "I think ", "See you soon. ", "good w").forEach { input ->
            assertEquals(input, model.predict(input, 6), model.predictScored(input, 6).map { it.first })
            assertEquals(input, model.predict(input), model.predictScored(input).map { it.first })
        }
    }

    @Test
    fun `tables rebuild a model that predicts exactly the same`() {
        val store = RecordingStore()
        val model = AdaptiveNgramModel(learningStore = store, dictionary = dictionary)
        // Above the replay cap of 12, so the counts could only come from the tables, not the store.
        repeat(15) { model.accept("good", "work") }
        repeat(3) { model.accept(null, "Then") }
        assertTrue(model.learnTypedWord("good world", persist = true))
        // Never stored, so a fresh model from the store would not have it.
        model.accept("secret", "there", persist = false)
        model.countWord("they")

        val rebuilt = AdaptiveNgramModel.fromTables(model.tables(), dictionary)

        listOf("", "good ", "good w", "secret ", "secret th", "th", "Then ", "the").forEach { input ->
            assertEquals(input, model.predictScored(input, 6), rebuilt.predictScored(input, 6))
        }
        assertEquals(15, rebuilt.pairCount("good", "work"))
        assertTrue(rebuilt.knows("they"))
    }

    @Test
    fun `countWord raises a unigram score and no bigram`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList())
        val before = model.predictScored("wor").first { it.first == "work" }.second

        model.countWord("work")

        assertEquals(before + 6, model.predictScored("wor").first { it.first == "work" }.second)
        assertEquals(0, model.pairCount("good", "work"))
        assertEquals(0, model.pairCount(AdaptiveNgramModel.START, "work"))
        assertTrue(model.knows("work"))
    }

    @Test
    fun `fromTables ignores seeds and starters`() {
        val empty = AdaptiveNgramModel.Tables(emptyMap(), emptyList())

        val model = AdaptiveNgramModel.fromTables(empty, WordDictionary.EMPTY)

        assertTrue(model.predict("").isEmpty())
        assertTrue(model.predict("thank ").isEmpty())
        assertTrue(model.tables().unigrams.isEmpty())
        assertTrue(model.tables().bigrams.isEmpty())
    }

    @Test
    fun `companion helpers equal the instance methods`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList())

        listOf("", "Please writ", "Please ", "All done. Th", "so 12 ", "thanks\n  wh", "Don’t y", "café au").forEach { input ->
            assertEquals(input, model.activePrefix(input), AdaptiveNgramModel.activePrefix(input))
            assertEquals(input, model.lastCompletedWord(input), AdaptiveNgramModel.lastCompletedWord(input))
            assertEquals(input, model.isSentenceStart(input), AdaptiveNgramModel.isSentenceStart(input))
        }
        assertEquals("don't", AdaptiveNgramModel.normalize("Don’t"))
    }

    @Test
    fun `without a prior, scores are the ones recorded before the prior existed`() {
        val recorded = File("src/test/fixtures/ngram_scores_without_prior.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val real = WordDictionary.parse((asset("words_en.txt") + asset("words_en_modern.txt")).asSequence())
        val unrelated = CorpusPrior.of(mapOf("zebra" to listOf("crossing" to -0.1f)))
        val inputs = listOf(
            "", "hello ", "hello wor", "go", "Th", "please ", "I think ", "See you soon. ", "good w", "thank ", "thank y",
            "looking ", "how are ", "i ", "hi, ", "so 12 ", "thanks\n", "Don’t y", "I will be th", "mor", "Wh", "what time ",
        )
        // The default, an explicit null, and a prior that holds none of these contexts must all score identically.
        for (prior in listOf<CorpusPrior?>(null, unrelated)) {
            for (explicit in listOf(false, true)) {
                fun model(learning: Boolean, seeds: List<String>?, words: WordDictionary, starters: Boolean): AdaptiveNgramModel {
                    val seedPhrases = seeds ?: AdaptiveNgramModel.DEFAULT_SEED_PHRASES
                    val starterWords = if (starters) AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS else emptyList()
                    val m = if (explicit || prior != null) {
                        AdaptiveNgramModel(seedPhrases = seedPhrases, dictionary = words, sentenceStarters = starterWords, prior = prior)
                    } else {
                        AdaptiveNgramModel(seedPhrases = seedPhrases, dictionary = words, sentenceStarters = starterWords)
                    }
                    if (learning) {
                        repeat(4) { m.accept("please", "confirm") }
                        repeat(3) { m.accept(null, "Morning") }
                        m.accept("thank", "goodness")
                        m.learnTypedWord("good work", persist = false)
                    }
                    return m
                }
                val models = listOf(
                    "defaults" to model(learning = false, seeds = null, words = real, starters = true),
                    "learned" to model(learning = true, seeds = null, words = real, starters = true),
                    "seededSmall" to model(learning = false, seeds = listOf("hello world", "hello there"), words = dictionary, starters = true),
                    "bare" to model(learning = false, seeds = emptyList(), words = dictionary, starters = false),
                )
                val actual = models.flatMap { (name, m) ->
                    inputs.map { input ->
                        "$name|${input.replace("\n", "\\n")}|" + m.predictScored(input, 6).joinToString(",") { "${it.first}=${it.second}" }
                    }
                }
                assertEquals("prior=$prior explicit=$explicit", recorded, actual)
            }
        }
    }

    @Test
    fun `a prior lifts followers of the previous word, even ones outside the dictionary`() {
        val prior = CorpusPrior.of(mapOf("good" to listOf("work" to -1f, "night" to -2f)))
        val plain = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList())
        val primed = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(), prior = prior)

        assertEquals("the", plain.predict("good ").first())
        assertEquals("work", primed.predict("good ").first())
        // 80 from the dictionary plus 20 points per nat above ln P = -10.
        assertEquals(80 + 180, primed.predictScored("good w").first { it.first == "work" }.second)
        assertTrue(plain.predict("good n").isEmpty())
        assertEquals(listOf("night"), primed.predict("good n"))
        // Only after "good": the same words elsewhere score as before.
        assertEquals(plain.predictScored("hello w"), primed.predictScored("hello w"))
    }

    @Test
    fun `a prior covers sentence starts`() {
        val prior = CorpusPrior.of(mapOf(CorpusPrior.SENTENCE_START to listOf("then" to -0.5f)))
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(), prior = prior)

        assertEquals("then", model.predict("").first())
        assertEquals("then", model.predict("All done. ").first())
        assertEquals("the", model.predict("hello ").first())
    }

    @Test
    fun `the prior bonus is capped, floored and fades with the person's own count`() {
        assertEquals(200, AdaptiveNgramModel.priorBonus(0f, 0))
        assertEquals(200, AdaptiveNgramModel.priorBonus(1f, 0))
        assertEquals(100, AdaptiveNgramModel.priorBonus(-5f, 0))
        assertEquals(0, AdaptiveNgramModel.priorBonus(-10f, 0))
        assertEquals(0, AdaptiveNgramModel.priorBonus(-20f, 0))
        assertEquals(50, AdaptiveNgramModel.priorBonus(-5f, 16))
        assertEquals(50, AdaptiveNgramModel.priorBonus(0f, 48))
        val faded = (0..200).map { AdaptiveNgramModel.priorBonus(-1f, it) }
        assertEquals(faded.sortedDescending(), faded)
    }

    @Test
    fun `prior weights reshape the bonus, and the default is the setting that passed the corpus gate`() {
        assertEquals(AdaptiveNgramModel.PriorWeights(weight = 20f, floor = -10f, fade = 16f), AdaptiveNgramModel.PriorWeights.DEFAULT)
        assertEquals(CorpusEvaluation.SELECTED_PRIOR, AdaptiveNgramModel.PriorWeights.DEFAULT)
        // The original constants, weight 10: up to 100, the dictionary score's range.
        val ten = AdaptiveNgramModel.PriorWeights(weight = 10f)
        assertEquals(100, AdaptiveNgramModel.priorBonus(0f, 0, ten))
        assertEquals(50, AdaptiveNgramModel.priorBonus(-5f, 0, ten))
        assertEquals(25, AdaptiveNgramModel.priorBonus(-5f, 16, ten))
        assertEquals(30, AdaptiveNgramModel.priorBonus(-5f, 0, AdaptiveNgramModel.PriorWeights(weight = 10f, floor = -8f)))
        assertEquals(0, AdaptiveNgramModel.priorBonus(-8f, 0, AdaptiveNgramModel.PriorWeights(weight = 10f, floor = -8f)))
        assertEquals(50, AdaptiveNgramModel.priorBonus(0f, 4, AdaptiveNgramModel.PriorWeights(weight = 10f, fade = 4f)))
        assertTrue(runCatching { AdaptiveNgramModel.PriorWeights(fade = 0f) }.isFailure)

        val prior = CorpusPrior.of(mapOf("good" to listOf("work" to -1f)))
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(), prior = prior, priorWeights = ten)
        assertEquals(80 + 90, model.predictScored("good w").first { it.first == "work" }.second)
        val rebuilt = AdaptiveNgramModel.fromTables(model.tables(), dictionary, prior, priorWeights = ten)
        assertEquals(model.predictScored("good w", 6), rebuilt.predictScored("good w", 6))
    }

    @Test
    fun `the prior fades as the person's own words after that word grow, and seeds don't count`() {
        val prior = CorpusPrior.of(mapOf("good" to listOf("work" to 0f)))
        val model = AdaptiveNgramModel(seedPhrases = listOf("good night", "good night"), dictionary = dictionary, sentenceStarters = emptyList(), prior = prior)
        fun work() = model.predictScored("good wo").first { it.first == "work" }.second

        assertEquals(4, model.pairCount("good", "night"))
        assertEquals(0, model.ownCountAfter("good"))
        assertEquals(80 + 200, work())

        repeat(16) { model.accept("good", "world") }
        assertEquals(16, model.ownCountAfter("good"))
        assertEquals(80 + 100, work())

        model.clearLearned(seedPhrases = listOf("good night"))
        assertEquals(0, model.ownCountAfter("good"))
        assertEquals(80 + 200, work())
    }

    @Test
    fun `fromTables with the seeds fades the prior exactly as the live model did`() {
        val prior = CorpusPrior.read(File("src/test/fixtures/corpus_prior_tiny.bin").inputStream())
        val live = AdaptiveNgramModel(dictionary = dictionary, prior = prior)
        repeat(5) { live.accept(null, "Thank") }
        repeat(3) { live.accept("thank", "god") }
        live.accept("see", "you")

        val rebuilt = AdaptiveNgramModel.fromTables(
            live.tables(), dictionary, prior, AdaptiveNgramModel.DEFAULT_SEED_PHRASES, AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS,
        )

        listOf("", "Th", "thank ", "thank g", "see ", "god ", "you s", "Done. s").forEach { input ->
            assertEquals(input, live.predictScored(input, 6), rebuilt.predictScored(input, 6))
        }
        assertEquals(3, rebuilt.ownCountAfter("thank"))
        assertEquals(5, rebuilt.ownCountAfter(AdaptiveNgramModel.START))
        // Without the seeds, the starters would count as the person's and fade the sentence-start prior.
        assertTrue(AdaptiveNgramModel.fromTables(live.tables(), dictionary, prior).ownCountAfter(AdaptiveNgramModel.START) > 5)
    }

    @Test
    fun `personal followers subtract each seeded pair and exclude seeded sentence starters`() {
        val model = AdaptiveNgramModel(seedPhrases = listOf("good night", "good night", "good world"), sentenceStarters = listOf("hey"))
        assertTrue(model.learnedFollowers("good").isEmpty())
        assertTrue(model.learnedFollowers(AdaptiveNgramModel.START).isEmpty())
        repeat(3) { model.accept("good", "night") }
        repeat(2) { model.accept("good", "world") }
        model.accept("good", "work")
        repeat(2) { model.accept(null, "hey") }
        assertEquals(listOf("night" to 3, "world" to 2, "work" to 1), model.learnedFollowers("GOOD"))
        assertEquals(listOf("hey" to 2), model.learnedFollowers(AdaptiveNgramModel.START))
        assertTrue(model.learnedFollowers("absent").isEmpty())
    }

    @Test
    fun `personal follower ties are lexical and clearing removes their counts`() {
        val model = AdaptiveNgramModel(seedPhrases = listOf("good night"), sentenceStarters = emptyList())
        repeat(2) { model.accept("good", "world"); model.accept("good", "work") }
        assertEquals(listOf("work" to 2, "world" to 2), model.learnedFollowers("good"))
        model.clearLearned(seedPhrases = listOf("good night"))
        assertTrue(model.learnedFollowers("good").isEmpty())
    }

    @Test
    fun `a seeded snapshot and loaded baseline retain exact personal follower counts`() {
        val model = AdaptiveNgramModel(dictionary = dictionary)
        model.restoreLearned(listOf(Triple("thank", "you", 2), Triple("thank", "god", 30), Triple(AdaptiveNgramModel.START, "hey", 3)))
        model.accept("thank", "god")
        val rebuilt = AdaptiveNgramModel.fromTables(
            model.tables(), dictionary, seedPhrases = AdaptiveNgramModel.DEFAULT_SEED_PHRASES,
            sentenceStarters = AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS,
        )
        assertEquals(listOf("god" to 13, "you" to 2), model.learnedFollowers("thank"))
        assertEquals(model.learnedFollowers("thank"), rebuilt.learnedFollowers("thank"))
        assertEquals(model.learnedFollowers(AdaptiveNgramModel.START), rebuilt.learnedFollowers(AdaptiveNgramModel.START))
    }

    @Test
    fun `the bundled prior gives context on a fresh install`() {
        val words = WordDictionary.parse((asset("words_en.txt") + asset("words_en_modern.txt")).asSequence())
        val prior = CorpusPrior.read(File("src/main/assets/${CorpusPrior.ASSET}").inputStream())
        val plain = AdaptiveNgramModel(dictionary = words)
        val primed = AdaptiveNgramModel(dictionary = words, prior = prior)

        assertFalse("christmas" in plain.predict("Merry "))
        assertEquals("christmas", primed.predict("Merry ").first())
        assertFalse("time" in plain.predict("on the "))
        assertTrue("time" in primed.predict("on the "))
        // The seeded pairs and the starters still lead where they apply.
        assertEquals("you", primed.predict("thank ").first())
        assertTrue(primed.predict("").all { it in AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS.map(String::lowercase) })
    }

    @Test
    fun `the prior changes ranking only, never what is learned`() {
        val words = WordDictionary.parse((asset("words_en.txt") + asset("words_en_modern.txt")).asSequence())
        val prior = CorpusPrior.read(File("src/main/assets/${CorpusPrior.ASSET}").inputStream())
        val plainStore = RecordingStore()
        val primedStore = RecordingStore()
        val plain = AdaptiveNgramModel(plainStore, dictionary = words)
        val primed = AdaptiveNgramModel(primedStore, dictionary = words, prior = prior)
        // Real words, typos, a word only the prior knows to follow "merry", taps, and a kept word.
        val typed = listOf("Merry christmas", "Thank you", "see you tmrw", "on the way", "teh ", "I'm happy", "zxqv", "Merry christmas")
        for (text in typed) {
            assertEquals(text, plain.learnTypedWord(text, persist = true), primed.learnTypedWord(text, persist = true))
        }
        assertEquals(plain.learnTypedWord("see tmrw", persist = true) { it == "tmrw" }, primed.learnTypedWord("see tmrw", persist = true) { it == "tmrw" })
        listOf(null to "Hey", "merry" to "christmas", "the" to "time").forEach { (previous, word) ->
            plain.accept(previous, word)
            primed.accept(previous, word)
        }
        assertEquals(plain.tables(), primed.tables())
        assertEquals(plainStore.records, primedStore.records)
        for (word in listOf("christmas", "tmrw", "zxqv", "time")) assertEquals(word, plain.knows(word), primed.knows(word))
        assertEquals(plain.pairCount("merry", "christmas"), primed.pairCount("merry", "christmas"))
        assertEquals(plain.ownCountAfter("merry"), primed.ownCountAfter("merry"))
        // ...while the ranking does differ, which is the point of it.
        assertTrue(plain.predict("on the ") != primed.predict("on the "))
    }

    private fun asset(name: String) = File("src/main/assets/$name").readLines()

    private class RecordingStore : AdaptiveNgramModel.LearningStore {
        val records = mutableListOf<Pair<String, String>>()
        var cleared = false
        override fun entries() = records.groupingBy { it }.eachCount().map { (pair, count) -> Triple(pair.first, pair.second, count) }
        override fun record(previous: String, next: String) { records += previous to next }
        override fun clear() { records.clear(); cleared = true }
    }
}
