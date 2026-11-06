package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class CorrectionPolicyTest {
    private val engine = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })

    @Test fun `capturing pools preserves every legacy real and simulated answer and guard`() {
        val samples = TypoCorpus.real.map { TypoCorpus.Sample(it.first, it.second, emptyList()) } + TypoCorpus.simulated(400)
        for (sample in samples) {
            val taps = sample.touches.takeIf { it.isNotEmpty() }
            val legacy = engine.propose(sample.typed, taps)
            val snapshot = engine.candidates(sample.typed, taps)
            assertEquals(sample.typed, legacy, snapshot.legacy)
            assertTrue(snapshot.candidates.size <= 16)
            assertTrue(snapshot.candidates.all { it.cost.isFinite() && it.frequency.isFinite() })
            assertEquals(sample.typed, snapshot.unchanged.word)
            val decision = CorrectionPolicy(priorCap = 8f, neuralCap = 8f).decide(snapshot)
            assertEquals(sample.typed, legacy?.takeIf { it.automatic }?.word,
                decision.automatic.takeIf { it.changed }?.word)
            assertFalse(decision.lateAutomaticEligible)
        }
        for (word in TypoCorpus.guard + listOf("NASA", "iPhone", "John's", "parents'", "cant", "ill", "Cacthig")) {
            val snapshot = engine.candidates(word)
            assertEquals(word, engine.propose(word), snapshot.legacy)
            val decision = CorrectionPolicy(priorCap = 8f, neuralCap = 8f, extraBudget = .5f, contextualAutomatic = true).decide(snapshot)
            if (!snapshot.contextualEligible) assertEquals(word, engine.correct(word), decision.automatic.takeIf { it.changed }?.word)
        }
    }

    @Test fun `kept learned excluded capitalization and possessive guards cannot acquire contextual candidates`() {
        val guarded = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon },
            isKept = { it == "cant" }, isKnown = { it == "zyxt" }, blocked = { it == "excluded" || it == "the" })
        for (word in listOf("cant", "zyxt", "excluded", "NASA", "iPhone", "friend's", "Steuber")) {
            assertTrue(word, guarded.candidates(word).candidates.isEmpty())
        }
        assertFalse(guarded.candidates("teh").candidates.any { it.word == "the" })
    }

    private fun snapshot(cost: Float = 1f, length: String = "typo", score: Float = 10f, rare: Boolean = false): CorrectionSnapshot =
        CorrectionSnapshot(length, length, listOf(
            CorrectionCandidate("hello", cost, cost + .2f, 4, 50f, 0f, score, true, rare),
            CorrectionCandidate("help", 1f, 1f, 0, 40f, 0f, 3f, true, false)), null, 1f, true, null)

    @Test fun `captured scores are copied capped and invalid or unrelated evidence is dropped`() {
        val s = snapshot()
        val neural = mutableListOf(CifgModel.Candidate("hello", 1, 100f), CifgModel.Candidate("help", 2, 0f),
            CifgModel.Candidate("foreign", 3, -1000f))
        val prior = CorpusPrior.of(mapOf("say" to listOf("hello" to -1f, "help" to -10f)))
        val priorBefore = prior.followers("say")
        val context = CorrectionContext.capture(s, prior, "say", neural)
        neural.clear()
        assertEquals(2f, context.contribution("hello", 2f, false), 0f)
        assertEquals(4f, context.contribution("hello", 4f, true), 0f)
        assertEquals(0f, context.contribution("foreign", 8f, true), 0f)
        assertEquals(0f, context.contribution("help", 8f, true), 0f)
        assertEquals(0f, context.contribution("hello", 0f, true), 0f)
        assertNull(CorrectionContext.capture(s, neuralScores = listOf(CifgModel.Candidate("hello", 1, Float.NaN))).neuralScore("hello"))
        assertEquals(priorBefore, prior.followers("say"))
        assertThrows(UnsupportedOperationException::class.java) { (s.candidates as MutableList).clear() }
    }

    @Test fun `cost expansions stay off short and rare words and unchanged participates in margin`() {
        val policy = CorrectionPolicy(extraBudget = .5f, contextualAutomatic = true)
        assertTrue(policy.decide(snapshot(cost = 1.4f)).automatic.changed)
        assertFalse(policy.decide(snapshot(cost = 1.4f, length = "tyo")).automatic.changed)
        assertFalse(policy.decide(snapshot(cost = 1.4f, rare = true)).automatic.changed)
        val negative = policy.decide(snapshot(score = -1f))
        assertFalse(negative.automatic.changed)
        assertTrue(negative.reasons.contains("winner-margin"))
        assertEquals("typo", negative.unchanged.word)
    }

    @Test fun `late gate cannot pass count alone or precision alone`() {
        assertFalse(CorrectionPolicy(contextualAutomatic = true, reservedApplications = 499, reservedCorrect = 499).lateAutomaticEligible)
        assertFalse(CorrectionPolicy(contextualAutomatic = true, reservedApplications = 500, reservedCorrect = 499).lateAutomaticEligible)
        assertTrue(CorrectionPolicy(contextualAutomatic = true, reservedApplications = 500, reservedCorrect = 500).lateAutomaticEligible)
        assertFalse(CorrectionPolicy(reservedApplications = 500, reservedCorrect = 500).lateAutomaticEligible)
        assertEquals(0.0, CorrectionPolicy.wilsonLower(0, 0), 0.0)
        assertNotEquals(CorrectionPolicy().version, CorrectionPolicy(priorCap = 2f).version)
    }

    @Test fun `manual phrase policy cannot append to a previous correction log`() {
        val current = PredictionLogCodec.correctionHeader(CorrectionPolicy().version, true, true)
        val previous = current + ("correctionPolicy" to CorrectionPolicy().version.replace("-v5:", "-v4:"))
        assertFalse(PredictionLogCodec.sameRanking(previous, current))
        val parsed = PredictionLogCodec.encodeHeader(previous).trimEnd().lines().mapNotNull(PredictionLogCodec::parseHeader).toMap()
        assertEquals(previous["correctionPolicy"], parsed["correctionPolicy"])
    }

    @Test fun `mutable touch callers cannot rewrite a memo key and invalid touches fall back`() {
        val touches = "woklr".map { KeyGeometry.center(it) }.toMutableList()
        val first = engine.propose("woklr", touches)
        touches[2] = KeyGeometry.touchOn('k', -.45f, -.5f)
        touches[4] = KeyGeometry.touchOn('r', -.2f, .5f)
        engine.propose("woklr", touches)
        assertEquals(first, engine.propose("woklr", "woklr".map { KeyGeometry.center(it) }))
        assertEquals(engine.candidates("teh").legacy,
            engine.candidates("teh", List(3) { Touch(Float.NaN, 0f) }).legacy)
    }
}
