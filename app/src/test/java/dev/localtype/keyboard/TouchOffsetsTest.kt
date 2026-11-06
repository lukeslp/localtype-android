package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchOffsetsTest {
    private val offsets = TouchOffsets()

    private fun touches(word: String, dx: Float, dy: Float): List<Touch?> =
        word.map { KeyGeometry.touchOn(it.lowercaseChar(), dx, dy) }

    private fun assertOffset(letter: Char, x: Float, y: Float) {
        val mean = offsets.offset(letter)
        assertEquals(x, mean.x, 0.00001f)
        assertEquals(y, mean.y, 0.00001f)
    }

    @Test
    fun `unseen keys have zero count and leave a lift alone`() {
        val touch = Touch(2.5f, 1.75f)
        assertEquals(0, offsets.count('a'))
        assertOffset('a', 0f, 0f)
        assertEquals(touch, offsets.correctedTouch('a', touch))
        assertTrue(offsets.entries().isEmpty())
        assertEquals(0L, offsets.revision)
    }

    @Test
    fun `each letter has a running mean and its own count`() {
        assertTrue(offsets.record("dad", touches("dad", 0.2f, 0.3f)))
        assertTrue(offsets.record("add", touches("add", -0.1f, 0f)))
        assertEquals(4, offsets.count('d'))
        assertEquals(2, offsets.count('a'))
        assertEquals(0, offsets.count('s'))
        assertOffset('d', 0.05f, 0.15f)
        assertOffset('a', 0.05f, 0.15f)
        assertEquals(2L, offsets.revision)
    }

    @Test
    fun `all 26 letter keys accept uppercase samples into the same means`() {
        val lower = "abcdefghijklmnopqrstuvwxyz"
        val upper = lower.uppercase()
        assertTrue(offsets.record(lower, touches(lower, 0.2f, 0.2f)))
        assertTrue(offsets.record(upper, touches(upper, 0.2f, 0.2f)))
        assertEquals(26, offsets.entries().size)
        for (letter in lower) {
            assertEquals(2, offsets.count(letter))
            assertEquals(2, offsets.count(letter.uppercaseChar()))
            assertOffset(letter, 0.2f, 0.2f)
        }
        assertEquals(0, offsets.count('K'))
        assertOffset('K', 0f, 0f)
    }

    @Test
    fun `personal mean is subtracted from absolute lifts without selecting a key`() {
        offsets.record("type", touches("type", 0.2f, 0.2f))
        val lifted = KeyGeometry.touchOn('t', 0.35f, 0.1f)!!
        val adjusted = offsets.correctedTouch('t', lifted)
        val expected = KeyGeometry.touchOn('t', 0.15f, -0.1f)!!
        assertEquals(expected.x, adjusted.x, 0.00001f)
        assertEquals(expected.y, adjusted.y, 0.00001f)
        assertEquals(lifted, offsets.correctedTouch('!', lifted))
        assertEquals(1, offsets.count('t'))
    }

    @Test
    fun `missing or misaligned letter touches reject the entire word`() {
        val complete = touches("type", 0.2f, 0.2f)
        assertFalse(offsets.record("", emptyList()))
        assertFalse(offsets.record("type", null))
        assertFalse(offsets.record("type", complete.dropLast(1)))
        assertFalse(offsets.record("type", complete.toMutableList().also { it[2] = null }))
        assertTrue(offsets.entries().isEmpty())
        assertEquals(0L, offsets.revision)
    }

    @Test
    fun `nonfinite touches reject the entire word before any letter is learned`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val complete = touches("type", 0.2f, 0.2f)
            assertFalse(offsets.record("type", complete.toMutableList().also { it[3] = Touch(value, 0f) }))
            assertFalse(offsets.record("type", complete.toMutableList().also { it[3] = Touch(0f, value) }))
        }
        assertTrue(offsets.entries().isEmpty())
        assertEquals(0L, offsets.revision)
    }

    @Test
    fun `apostrophe slots do not teach letter offsets`() {
        assertTrue(offsets.record("can't", touches("can't", 0.2f, -0.1f)))
        assertEquals(4, offsets.entries().size)
        assertEquals(0, offsets.count('\''))
        assertOffset('c', 0.2f, -0.1f)
        assertFalse(offsets.record("'", listOf(null)))
    }

    @Test
    fun `snapshot round trip restores all counts and means without retaining references`() {
        val first = TouchOffsets()
        first.record("type", touches("type", 0.2f, 0.1f))
        first.record("test", touches("test", 0f, 0.3f))
        val snapshot = first.entries().toMutableList()
        offsets.load(snapshot)
        assertEquals(first.entries(), offsets.entries())
        assertEquals(1L, offsets.revision)
        snapshot.clear()
        first.clear()
        assertEquals(5, offsets.entries().size)
        assertEquals(3, offsets.count('t'))
        assertOffset('t', 0.2f / 3, 0.7f / 3)
        val same = offsets.entries()
        offsets.load(same)
        assertEquals(1L, offsets.revision)
    }

    @Test
    fun `loading rejects malformed entries and replaces previous samples`() {
        offsets.record("type", touches("type", 0.2f, 0.2f))
        offsets.load(listOf(
            TouchOffsets.Entry('a', 2, 0.1f, -0.2f),
            TouchOffsets.Entry('b', 0, 0.1f, 0f),
            TouchOffsets.Entry('c', -1, 0.1f, 0f),
            TouchOffsets.Entry('d', 1, Float.NaN, 0f),
            TouchOffsets.Entry('e', 1, 0f, Float.POSITIVE_INFINITY),
            TouchOffsets.Entry('!', 1, 0f, 0f),
        ))
        assertEquals(listOf(TouchOffsets.Entry('a', 2, 0.1f, -0.2f)), offsets.entries())
        assertEquals(0, offsets.count('t'))
        assertEquals(2L, offsets.revision)
        offsets.load(emptyList())
        assertTrue(offsets.entries().isEmpty())
        assertEquals(3L, offsets.revision)
    }

    @Test
    fun `forgetting clears all offsets and changes the revision only once`() {
        offsets.record("type", touches("type", 0.2f, 0.2f))
        offsets.clear()
        assertTrue(offsets.entries().isEmpty())
        assertOffset('t', 0f, 0f)
        assertEquals(2L, offsets.revision)
        offsets.clear()
        assertEquals(2L, offsets.revision)
    }

    @Test
    fun `a saturated count cannot overflow or silently change its mean`() {
        offsets.load(listOf(TouchOffsets.Entry('a', Int.MAX_VALUE, 0.2f, 0.2f)))
        val before = offsets.entries()
        assertFalse(offsets.record("a", touches("a", -0.2f, -0.2f)))
        assertEquals(before, offsets.entries())
        assertEquals(1L, offsets.revision)
        assertTrue(offsets.record("at", touches("at", -0.2f, -0.2f)))
        assertEquals(Int.MAX_VALUE, offsets.count('a'))
        assertEquals(1, offsets.count('t'))
        assertEquals(2L, offsets.revision)
    }
}
