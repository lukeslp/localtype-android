package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

/** Reported spellings are a diagnosis set, separate from the frozen quality evaluation. */
class ReportedTypingTest {
    private val corrector = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })

    @Test fun consecutiveRepairsKeepTheFollowingWordEligible() {
        val trace = WordTrace()
        var text = ""
        for (raw in listOf("teh", "doesnt", "adn", "isnt")) {
            for (letter in raw) {
                trace.letter(AdaptiveNgramModel.activePrefix(text), null)
                text += letter
            }
            val edit = WordEdits.separator(text, "", false, " ", trace.revisited,
                true, true, 280, typed = trace.hasTouchesFor(raw)) { corrector.correct(it) }
            assertTrue(raw, edit.corrected)
            text = text.dropLast(edit.replacement.before) + edit.replacement.text
            trace.finish()
        }
        assertEquals("the doesn't and isn't ", text)
    }

    @Test fun rawReportKeepsClearContractionsAndRecordsUncertainSpellings() {
        assertEquals("doesn't", corrector.correct("doesnt"))
        for ((raw, expected) in listOf("xwtch" to "catch", "adter" to "after", "woed" to "word",
            "xorrextion" to "correction", "jusy" to "just", "doesnf" to "doesn't", "corrsct" to "correct", "tjem" to "them")) {
            assertEquals(raw, expected, corrector.correct(raw))
        }
        // Joined words require a separately evaluated multiword policy, not an enlarged typo budget.
        assertNull(corrector.correct("itndossnt"))
    }
}
