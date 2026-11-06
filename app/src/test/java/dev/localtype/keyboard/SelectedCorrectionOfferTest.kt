package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class SelectedCorrectionOfferTest {
    private val corrector = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })

    @Test fun `Tehran completion cannot hide the repair of selected teh`() {
        val completions = TypoCorpus.dictionary.completions("teh", 1)
        assertTrue(completions.isNotEmpty())
        assertNull(corrector.suggestForStrip("teh", completionsAvailable = true))
        assertEquals("the", corrector.suggestForStrip("teh", explicitSelection = true, completionsAvailable = true))
        assertEquals("the", corrector.correct("teh"))
    }

    @Test fun `selection uses the existing spelling engine and preserves valid words`() {
        assertEquals("receive", corrector.suggestForStrip("recieve", explicitSelection = true))
        for (word in listOf("hello", "tehran", "friends")) {
            assertNull(word, corrector.suggestForStrip(word, explicitSelection = true, completionsAvailable = true))
        }
        assertNull(corrector.suggestForStrip("hel", completionsAvailable = true))
    }

    @Test fun `selection cannot bypass deliberate kept or learned spellings`() {
        for (kept in listOf(false, true)) {
            val guarded = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon },
                isKnown = { !kept && it == "teh" }, isKept = { kept && it == "teh" })
            assertNull(guarded.suggestForStrip("teh", explicitSelection = true, completionsAvailable = true))
        }
    }

    @Test fun `contractions and possessive offers remain visible before selection`() {
        assertEquals("don't", corrector.suggestForStrip("dont", completionsAvailable = true))
        assertEquals("luke's", corrector.suggestForStrip("lukes", completionsAvailable = true))
        assertNull(corrector.correct("lukes"))
    }
}
