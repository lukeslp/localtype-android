package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectorTouchOffsetsTest {
    private val touches = listOf(KeyGeometry.center('t'), KeyGeometry.center('s'))

    private fun offsets(dx: Float = 0.2f, dy: Float = 0.2f) = TouchOffsets().also {
        assertTrue(it.record("s", listOf(KeyGeometry.touchOn('s', dx, dy))))
    }

    // "ta" is just frequent enough to be offered with the adjusted s lift. A high-frequency word
    // outside the length budget sets its score without adding another correction candidate.
    private val dictionary = WordDictionary.parse(sequenceOf("zzzzzzzzzz\t1000000000000", "ta\t1"))

    @Test
    fun `the typed key mean is subtracted before substitution distance`() {
        val plain = Corrector.typingCost("ts", "ta", touches)
        val calibrated = Corrector.typingCost("ts", "ta", touches, touchOffsets = offsets())
        assertEquals(0.7f, plain, 0.00001f)
        // s is one key to the right of a; removing (+0.2,+0.2) gives squared distance 0.68.
        assertEquals(0.3f + 0.4f * 0.68f, calibrated, 0.00001f)
        assertTrue(calibrated < plain)
    }

    @Test
    fun `missing or misaligned taps keep the typed key centers`() {
        val learned = offsets()
        val centered = Corrector.typingCost("ts", "ta")
        assertEquals(centered, Corrector.typingCost("ts", "ta", touchOffsets = learned), 0f)
        assertEquals(centered, Corrector.typingCost("ts", "ta", listOf(null, null), touchOffsets = learned), 0f)
        assertEquals(centered, Corrector.typingCost("ts", "ta", listOf(touches[0]), touchOffsets = learned), 0f)
    }

    @Test
    fun `an unseen typed key does not borrow the candidate key offset`() {
        val learned = TouchOffsets()
        assertTrue(learned.record("a", listOf(KeyGeometry.touchOn('a', 0.2f, 0.2f))))
        assertEquals(
            Corrector.typingCost("ts", "ta", touches),
            Corrector.typingCost("ts", "ta", touches, touchOffsets = learned),
            0f,
        )
    }

    @Test
    fun `learning a new revision invalidates the same-word memo`() {
        val learned = TouchOffsets()
        val corrector = Corrector({ dictionary }, touchOffsets = { learned })
        assertNull(corrector.suggest("ts", touches))
        assertTrue(learned.record("s", listOf(KeyGeometry.touchOn('s', 0.2f, 0.2f))))
        assertEquals("ta", corrector.suggest("ts", touches))
    }

    @Test
    fun `forgetting offsets invalidates the same-word memo`() {
        val learned = offsets()
        val corrector = Corrector({ dictionary }, touchOffsets = { learned })
        assertEquals("ta", corrector.suggest("ts", touches))
        learned.clear()
        assertNull(corrector.suggest("ts", touches))
    }

    @Test
    fun `replacing offsets with another object at the same revision invalidates the memo`() {
        val rightAndDown = offsets()
        val leftAndUp = offsets(-0.2f, -0.2f)
        assertEquals(rightAndDown.revision, leftAndUp.revision)
        var current = rightAndDown
        val corrector = Corrector({ dictionary }, touchOffsets = { current })
        assertEquals("ta", corrector.suggest("ts", touches))
        current = leftAndUp
        assertNull(corrector.suggest("ts", touches))
    }

    @Test
    fun `calibration reaches both the candidate pruning bound and its full cost`() {
        val plain = Corrector({ dictionary })
        val learned = offsets()
        val calibrated = Corrector({ dictionary }, touchOffsets = { learned })
        // Without calibration, s's least substitution cost exceeds what this candidate can afford.
        // After calibration, both the bound and the full cost fit, allowing a strip-only proposal.
        assertNull(plain.propose("ts", touches))
        assertEquals(Corrector.Correction("ta", automatic = false), calibrated.propose("ts", touches))
    }
}
