package dev.localtype.keyboard

import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CurrentVocabularyTest {
    private val bundled = CurrentVocabulary.parse(File("src/main/assets/${CurrentVocabulary.ASSET}").readText())
    private val day = LocalDate.parse("2026-10-05").toEpochDay()
    private fun model(boost: Int = CurrentVocabulary.NEWS_CAP) = AdaptiveNgramModel(dictionary = TypoCorpus.dictionary, vocabulary = bundled.withBoost(boost))

    @Test fun `reviewed prefixes and factual pairs are offered with correct display`() {
        val model = model()
        for ((prefix, expected) in listOf("gaz" to "gaza", "neta" to "netanyahu", "mamd" to "mamdani", "zohr" to "zohran", "infer" to "inference", "gma" to "gmail")) {
            assertTrue("$prefix: ${model.predict(prefix)}", expected in model.predict(prefix))
        }
        assertEquals("mamdani", model.predict("Zohran ").first())
        assertEquals("netanyahu", model.predict("Benjamin ").first())
        assertEquals("Gaza", bundled.display("gaza", "gaz", false))
        assertEquals("Mamdani", bundled.display("mamdani", "mamd", false))
        assertEquals("ChatGPT", bundled.display("chatgpt", "chat", false))
        assertEquals("AI", bundled.display("ai", "ai", false))
        assertEquals("Gmail", bundled.display("gmail", "gma", false))
        assertEquals("Mamdani's", bundled.preferred("mamdani's"))
    }

    @Test fun `caps lock explicit uppercase and protected spellings win`() {
        assertEquals("MAMDANI", bundled.display("mamdani", "MA", false))
        assertEquals("CHATGPT", bundled.display("chatgpt", "chat", false, capsLock = true))
        assertEquals("ai", bundled.display("AI", "ai", false, protected = true))
        assertEquals("mamdani", bundled.display("Mamdani", "mam", false, protected = true))
        assertEquals("AI", bundled.capitalization("ai"))
        assertEquals("AI", bundled.capitalization("Ai"))
        for (word in listOf("AI", "aI", "us", "it", "mamdani")) assertNull(bundled.capitalization(word))
    }

    @Test fun `news bonus fades without removing recognition and future clocks fail closed`() {
        for ((age, boost) in listOf(-1 to 0, 0 to 4, 13 to 4, 14 to 2, 29 to 2, 30 to 0, 365 to 0)) {
            val phase = bundled.at(day + age)
            assertEquals(boost, phase.newsBoost)
            assertTrue("mamdani" in phase)
            assertEquals("Mamdani", phase.preferred("mamdani"))
        }
        assertEquals(4, bundled.withBoost(4).predictionLift("israel", 60, null))
        assertEquals(0, bundled.withBoost(0).predictionLift("israel", 60, null))
    }

    @Test fun `bounded cap selection preserves fixed ordinary controls`() {
        val baseline = AdaptiveNgramModel(dictionary = TypoCorpus.dictionary)
        val controls = listOf("", "hello ", "I will ", "the", "for", "goo", "ma", "is", "in", "ai")
        val candidates = listOf(0, 2, 4, 8)
        val passing = candidates.filter { cap ->
            val candidate = model(cap)
            val pass = candidate.predict("gaz").first() == "gaza" && controls.all { candidate.predict(it) == baseline.predict(it) }
            println("Vocabulary cap=$cap gaz=${candidate.predict("gaz")} controls=${controls.count { candidate.predict(it) == baseline.predict(it) }}/${controls.size} pass=$pass")
            pass
        }
        assertEquals("smallest passing declared cap", CurrentVocabulary.NEWS_CAP, passing.firstOrNull())
        assertTrue(bundled.candidates("", null).isEmpty())
        assertTrue(bundled.candidates("ma", null).isEmpty())
    }

    @Test fun `editorial candidates do not teach or alter correction targets`() {
        val model = model()
        val before = model.tables()
        model.predict("mamd")
        assertEquals(before, model.tables())
        assertFalse(model.learnTypedWord("mamdani", false))
        assertFalse(model.knows("mamdani"))
        assertFalse("mamdani" in TypoCorpus.dictionary)
        val legacy = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })
        val current = Corrector({ TypoCorpus.dictionary }, isKnown = { it in bundled }, lexicon = { TypoCorpus.lexicon })
        for (word in TypoCorpus.guard + TypoCorpus.real.map { it.first }) assertEquals(word, legacy.propose(word), current.propose(word))
        assertNull(current.propose("Netanyahu"))
        assertNull(current.propose("Mamdani"))
        assertNull(current.propose("gmail"))
        model.accept("zohran", "mamdani", false)
        assertTrue(model.knows("mamdani"))
    }

    @Test fun `neural reservation respects personal priority and never injects news at field start`() {
        val neural = listOf("mammoth", "mammal", "mambo")
        fun rank(prefix: String, added: List<String> = emptyList(), learned: List<Pair<String, Int>> = emptyList()) =
            NeuralRanking.rank(neural, listOf("mamdani"), learned, prefix, emptySet(), added = added, shared = listOf("mamdani"))
        assertEquals(listOf("mammoth", "mammal", "mamdani"), rank("mam"))
        assertEquals(listOf("mammoth", "mammal", "mamzorp"), rank("mam", added = listOf("mamzorp")))
        assertEquals(listOf("mammoth", "mammal", "mamsmith"), rank("mam", learned = listOf("mamsmith" to 2)))
        assertEquals(neural, rank(""))
        assertEquals(emptyList<String>(), rank("unmatched"))
    }

    @Test fun `replay restores exact vocabulary and refuses mismatches`() {
        val header = bundled.withBoost(4).header()
        val replay = PredictionLogCorpus.build(header)
        assertEquals(model().predict("gaz"), replay.model(AdaptiveNgramModel.Tables(emptyMap(), emptyList()), TypoCorpus.dictionary).predict("gaz"))
        assertTrue(PredictionLogCodec.sameRanking(header, header))
        assertFalse(PredictionLogCodec.sameRanking(header, bundled.withBoost(2).header()))
        assertFalse(PredictionLogCodec.sameRanking(header, emptyMap()))
        assertEquals(CurrentVocabulary.EMPTY.header(), PredictionLogCorpus.build(emptyMap()).vocabulary.header())
        for (changed in listOf(header + ("vocabulary" to "ab".repeat(32)), header + ("vocabularyPolicy" to "unknown"), header + ("newsBoost" to "-1"), header - "newsBoost")) {
            assertThrows(IllegalArgumentException::class.java) { PredictionLogCorpus.build(changed) }
        }
        val generated = PredictionLogFixture.generateWithExpectations(bundled.withBoost(4))
        val parsed = PredictionLogCorpus.load(generated.lines)
        assertEquals(header, parsed.header.filterKeys { it in PredictionLogCodec.VOCABULARY_KEYS })
        for (word in listOf("gaza", "gmail", "mamdani")) {
            assertTrue("Shared completion $word was not logged as a ranker tap", parsed.instances.any { it.target == word && it.tap?.src == 'R' })
        }
        val evaluated = Scorer(seedPhrases = PredictionLogFixture.SEEDS, sentenceStarters = PredictionLogFixture.STARTERS)
            .evaluate(parsed, listOf(DeviceRanker(), BigramReplayRanker()), PredictionLogCorpus.Parity.ALL)
        val agreement = requireNotNull(evaluated.run("bigram-replay").agreement)
        assertTrue(agreement.pairs > 0)
        assertEquals(1.0, agreement.top3Rate, 0.0)
        assertEquals(0.0, agreement.meanScoreDiff, 0.0)
    }

    @Test fun `malformed supplement rejects whole file`() {
        val good = "# observed=2026-10-05\nmamdani\tMamdani\t44\tnews\t-\n"
        for (bad in listOf(good + good.substringAfter('\n'), good.replace("44", "99"), good.replace("Mamdani", "Different"), good.replace("news", "unsupported"), good.replace("mamdani\t", "two words\t"))) {
            assertThrows(Exception::class.java) { CurrentVocabulary.parse(bad) }
        }
    }
}
