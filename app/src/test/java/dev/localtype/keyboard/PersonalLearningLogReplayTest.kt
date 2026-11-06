package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class PersonalLearningLogReplayTest {
    @Test fun `policy metadata preserves codec session and legacy replay agreement`() {
        val legacy = PredictionLogFixture.generateWithExpectations()
        val current = PredictionLogFixture.generateWithExpectations(personalLearning = WordPersistence.LOG_POLICY_ID)
        val plain = PredictionLogCorpus.load(legacy.lines)
        val marked = PredictionLogCorpus.load(current.lines)
        assertNull(plain.header["personalLearning"])
        assertEquals(WordPersistence.LOG_POLICY_ID, marked.header["personalLearning"])
        assertFalse(PredictionLogCodec.sameRanking(plain.header, marked.header))
        assertEquals(legacy.lines, current.lines.filterNot { it.startsWith("#personalLearning=") })
        val scorer = Scorer(seedPhrases = PredictionLogFixture.SEEDS, sentenceStarters = PredictionLogFixture.STARTERS)
        for (log in listOf(plain, marked)) {
            val result = scorer.evaluate(log, listOf(DeviceRanker(), BigramReplayRanker()), PredictionLogCorpus.Parity.ALL)
            assertEquals(1.0, result.run("bigram-replay").agreement!!.top3Rate, 0.0)
            assertEquals(1.0, result.run("bigram-replay").agreement!!.top6Rate, 0.0)
        }
    }
}
