package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each rule of [GestureClassifier] on both sides of its threshold, in key widths and milliseconds. */
class TwoFingerGesturesTest {
    /** A straight track from ([x], [y]) by ([dx], [dy]) in [steps] equal moves. */
    private fun track(x: Float, y: Float, dx: Float, dy: Float, steps: Int = 10) = GestureClassifier.Track(x, y).apply {
        for (i in 1..steps) add(x + dx * i / steps, y + dy * i / steps)
    }

    private fun classify(dx: Float, dy: Float, duration: Long = 300, other: Pair<Float, Float> = dx to dy) =
        GestureClassifier.classify(track(2f, 1f, dx, dy), track(5f, 1.5f, other.first, other.second), duration)

    @Test
    fun `each direction is told from two fingers moving together`() {
        assertEquals(TwoFingerSwipe.LEFT, classify(-3f, 0.2f))
        assertEquals(TwoFingerSwipe.RIGHT, classify(3f, -0.2f))
        assertEquals(TwoFingerSwipe.UP, classify(0.3f, -2.5f))
        assertEquals(TwoFingerSwipe.DOWN, classify(-0.3f, 2.5f))
    }

    @Test
    fun `travel along the axis must reach two key widths for each finger`() {
        assertEquals(TwoFingerSwipe.LEFT, classify(-2f, 0f))
        assertNull(classify(-1.99f, 0f))
        assertNull("one short finger is enough to refuse", classify(-3f, 0f, other = -1.9f to 0f))
    }

    @Test
    fun `a diagonal is neither axis`() {
        // Along-axis travel must be at least twice the cross-axis travel.
        assertEquals(TwoFingerSwipe.RIGHT, classify(3f, 1.5f))
        assertNull(classify(3f, 1.52f))
        assertEquals(TwoFingerSwipe.DOWN, classify(1.25f, 2.5f))
        assertNull(classify(1.27f, 2.5f))
    }

    @Test
    fun `the fingers must go the same way`() {
        assertNull("spreading apart", classify(-3f, 0f, other = 3f to 0f))
        assertNull("one left, one up", classify(-3f, 0f, other = 0f to -3f))
        assertNull("one finger still", classify(-3f, 0f, other = 0f to 0f))
    }

    @Test
    fun `the shorter finger travels at least half as far as the longer`() {
        assertEquals(TwoFingerSwipe.LEFT, classify(-2f, 0f, other = -4f to 0f))
        assertNull(classify(-2f, 0f, other = -4.1f to 0f))
    }

    @Test
    fun `each finger runs nearly straight`() {
        // Out 3 widths, then back: net 2, path 4, straightness 0.5.
        val hook = GestureClassifier.Track(2f, 1f).apply { add(-1f, 1f); add(0f, 1f) }
        assertNull(GestureClassifier.classify(hook, track(5f, 1.5f, -2f, 0f), 300))
        // A path of 2.5 for a net of 2 is 0.8, just enough; a little more detour is not.
        val bend = { lift: Float -> GestureClassifier.Track(4f, 1f).apply { add(3f, 1f - lift); add(2f, 1f) } }
        assertEquals(TwoFingerSwipe.LEFT, GestureClassifier.classify(bend(0.74f), track(7f, 1f, -2f, 0f), 300))
        assertNull(GestureClassifier.classify(bend(0.76f), track(7f, 1f, -2f, 0f), 300))
    }

    @Test
    fun `jitter between samples does not make a straight swipe crooked`() {
        // 0.05 key widths a sample with 0.02 of zigzag across it: counted sample by sample the path would be about
        // a third longer than the swipe; counted in steps it stays straight.
        val zigzag = { x: Float -> GestureClassifier.Track(x, 1f).apply {
            for (i in 1..60) add(x - 0.05f * i, 1f + if (i % 2 == 0) 0.02f else -0.02f)
        } }
        assertEquals(TwoFingerSwipe.LEFT, GestureClassifier.classify(zigzag(5f), zigzag(9f), 300))
    }

    @Test
    fun `slow or long gestures are not swipes`() {
        assertEquals(TwoFingerSwipe.LEFT, classify(-3f, 0f, duration = 800))
        assertNull(classify(-3f, 0f, duration = 801))
        // Two widths need at least three widths a second: 666 ms is fast enough, 667 is not.
        assertEquals(TwoFingerSwipe.LEFT, classify(-2f, 0f, duration = 666))
        assertNull(classify(-2f, 0f, duration = 667))
        assertNull(classify(-3f, 0f, duration = 0))
        assertNull(classify(-3f, 0f, duration = -5))
    }

    @Test
    fun `a pair starts only for a second finger soon after and apart from the first`() {
        assertTrue(GestureClassifier.pairs(0, 2f, 0f))
        assertTrue(GestureClassifier.pairs(250, 0f, 0.6f))
        assertFalse("too late", GestureClassifier.pairs(251, 2f, 0f))
        assertFalse("clock went backwards", GestureClassifier.pairs(-1, 2f, 0f))
        assertFalse("one finger seen twice", GestureClassifier.pairs(10, 0.59f, 0f))
        assertFalse(GestureClassifier.pairs(10, Float.NaN, 0f))
    }

    @Test
    fun `fingers activate only together, in time and within 45 degrees`() {
        fun active(ax: Float, ay: Float, bx: Float, by: Float, elapsed: Long = 100) =
            // Both start at x = 0 so 0.8 lands exactly on the threshold in floating point.
            GestureClassifier.activates(track(0f, 1f, ax, ay), track(0f, 3f, bx, by), elapsed)
        assertTrue(active(-0.8f, 0f, -0.8f, 0f))
        assertFalse(active(-0.79f, 0f, -0.8f, 0f))
        assertFalse("one finger still", active(-2f, 0f, 0f, 0f))
        // 44 and 46 degrees apart.
        val r = 1f
        assertTrue(active(-r, 0f, -r * kotlin.math.cos(Math.toRadians(44.0)).toFloat(), r * kotlin.math.sin(Math.toRadians(44.0)).toFloat()))
        assertFalse(active(-r, 0f, -r * kotlin.math.cos(Math.toRadians(46.0)).toFloat(), r * kotlin.math.sin(Math.toRadians(46.0)).toFloat()))
        assertFalse("too late to start a swipe", active(-1f, 0f, -1f, 0f, elapsed = 801))
    }

    @Test
    fun `a bad coordinate spoils the track`() {
        val broken = track(2f, 1f, -3f, 0f).apply { add(Float.NaN, 1f) }
        assertFalse(broken.valid)
        assertNull(GestureClassifier.classify(broken, track(5f, 1f, -3f, 0f), 300))
        assertFalse(GestureClassifier.activates(broken, track(5f, 1f, -3f, 0f), 100))
        assertFalse(GestureClassifier.Track(Float.POSITIVE_INFINITY, 0f).valid)
    }

    @Test
    fun `a pair turns active once and is read at the first lift`() {
        val pair = TwoFingerPair(0, 2f, 1f, 1, 5f, 1f, start = 1_000)
        assertFalse(pair.add(0, 1.5f, 1f, 1_050))
        assertFalse("a finger that is not in the pair", pair.add(7, -9f, 1f, 1_050))
        assertFalse(pair.add(1, 4.1f, 1f, 1_060))
        assertNull("not a swipe while undecided", pair.swipe(1_100))
        assertTrue(pair.add(0, 1.1f, 1f, 1_080))
        assertTrue(pair.active)
        assertFalse("active only once", pair.add(1, 3f, 1f, 1_100))
        pair.add(0, -0.5f, 1f, 1_200)
        pair.add(1, 2.5f, 1f, 1_200)
        assertEquals(TwoFingerSwipe.LEFT, pair.swipe(1_250))
        assertTrue(pair.has(0) && pair.has(1) && !pair.has(2))
    }
}
