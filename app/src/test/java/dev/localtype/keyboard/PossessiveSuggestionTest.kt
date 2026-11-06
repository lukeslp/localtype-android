package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class PossessiveSuggestionTest {
    private val corrector = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })

    @Test fun `missing apostrophe is an explicit offer with unchanged automatic behavior`() {
        assertEquals("luke's", corrector.suggest("lukes"))
        assertEquals("luke's", corrector.suggest("Lukes"))
        assertNull(corrector.correct("lukes"))
        assertNull(corrector.correct("Lukes"))
        assertEquals("don't", corrector.suggest("dont"))
        assertEquals("don't", corrector.correct("dont"))
    }

    @Test fun `valid plurals possessives acronyms identifiers and pronouns stay protected`() {
        for (word in listOf("friends", "parents", "dogs", "its", "hers", "ours", "theirs", "Luke's", "parents'", "NASA", "iPhones", "LukeS", "lukes2", "lukes.com", "lukes_keyboard", "zyxts")) {
            assertNull(word, corrector.possessiveSuggestion(word))
        }
    }

    @Test fun `saved kept learned and excluded spellings cannot acquire apostrophe offers`() {
        for (guard in listOf("kept", "known", "blocked")) {
            val guarded = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon },
                isKept = { guard == "kept" && it == "lukes" },
                isKnown = { guard == "known" && it == "lukes" },
                blocked = { guard == "blocked" && it in setOf("luke", "luke's") })
            assertNull(guard, guarded.possessiveSuggestion("lukes"))
        }
    }

    @Test fun `manual offer does not change contextual automatic outcome`() {
        val snapshot = corrector.candidates("lukes")
        val decision = CorrectionPolicy(priorCap = 8f, neuralCap = 8f).decide(snapshot)
        assertFalse(decision.automatic.changed)
        assertFalse(decision.lateAutomaticEligible)
    }
}
