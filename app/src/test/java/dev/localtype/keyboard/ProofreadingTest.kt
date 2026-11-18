package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProofreadingTest {
    @Test
    fun `targets the sentence being written, or the one just finished`() {
        assertEquals(Proofreading.Target(0, "i think its fine", ""), Proofreading.target("i think its fine"))
        val t = Proofreading.target("Sounds good. what time is it ")!!
        assertEquals("what time is it", t.sentence)
        assertEquals(13, t.start)
        assertEquals(" ", t.trailing)
        assertEquals("see you at 5.", Proofreading.target("hi\nsee you at 5.")!!.sentence)
        assertEquals("Done.", Proofreading.target("Sure. Done. ")!!.sentence)
        assertNull(Proofreading.target("  "))
        assertNull(Proofreading.target("ok. a "))
    }

    @Test
    fun `diff marks changed and inserted words`() {
        val segments = Proofreading.diff("i think its fine", "I think it's fine.")
        assertEquals(listOf("I", "think", "it's", "fine."), segments.map { it.text })
        assertEquals(listOf(true, false, true, true), segments.map { it.changed })
        assertEquals(0, Proofreading.changedCount("All good.", "All good."))
    }

    @Test
    fun `proofread replies are cleaned and implausible ones rejected`() {
        assertEquals("What time is it?", cleanProofread("what time is it", "Corrected text: What time is it?"))
        assertEquals("What time is it?", cleanProofread("what time is it", "\"What time is it?\"\n\nI capitalized the first word."))
        assertNull(cleanProofread("what time is it", "This sentence asks for the current time and is grammatically a question that should end with a question mark."))
        assertNull(cleanProofread("what time is it", "   "))
    }

    @Test
    fun `a selection with line breaks keeps every line or is refused`() {
        val original = "Please reveiw the attached report before friday.\nThanks"
        val corrected = "Please review the attached report before Friday.\nThanks"
        assertEquals(corrected, cleanProofread(original, corrected))
        assertEquals(corrected, cleanProofread(original, "Corrected text: $corrected\n"))
        // The first line alone would delete "Thanks" on Apply.
        assertNull(cleanProofread(original, "Please review the attached report before Friday."))
        assertNull(cleanProofread(original, "$corrected\n\nI fixed two words."))
        // One line that only ends in a line break is still one line: the reply's first line is taken.
        assertEquals("See you Friday.", cleanProofread("see you friday\n", "See you Friday.\n\nI fixed the capital."))
    }
}
