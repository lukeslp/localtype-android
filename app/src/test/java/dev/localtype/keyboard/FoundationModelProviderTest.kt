package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class FoundationModelProviderTest {
    @Test
    fun `phrases keep up to two words and allow a and i`() {
        val raw = "a coffee\na snack\n2. lunch together soon\nI think\n- x\ntomorrow!"
        assertEquals(listOf("a coffee", "a snack", "lunch together", "i think", "tomorrow"), parsePhraseCandidates(raw, 8))
    }

    @Test
    fun `phrase words without letters are dropped`() {
        assertEquals(listOf("sure why", "maybe", "ok"), parsePhraseCandidates("sure - why\n'' maybe\nok '", 8))
    }

    @Test
    fun `candidate parsing honors requested limit`() {
        val candidates = parsePhraseCandidates("alpha\nbeta\ngamma", 2)

        assertEquals(listOf("alpha", "beta"), candidates)
    }
}
