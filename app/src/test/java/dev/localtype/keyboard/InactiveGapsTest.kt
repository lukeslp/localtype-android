package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class InactiveGapsTest {
    @Test fun `both row edges reject touches while the center stays usable`() {
        for (top in listOf(2.5f, 56.5f, 110.5f)) {
            assertFalse(InactiveGaps.accepts(top, top, top + 54, 2.5f))
            assertFalse(InactiveGaps.accepts(top + 53, top, top + 54, 2.5f))
            assertTrue(InactiveGaps.accepts(top + 27, top, top + 54, 2.5f))
            assertTrue(InactiveGaps.accepts(top + 2.5f, top, top + 54, 2.5f))
            assertFalse(InactiveGaps.accepts(top + 51.5f, top, top + 54, 2.5f))
        }
    }
    @Test fun `compressed and number rows retain at least eighty percent of cell height`() {
        for (height in listOf(1f, 10f, 43.2f, 54f)) {
            assertTrue(InactiveGaps.inset(height, 2.5f) * 2 <= height * .2f + .0001f)
            assertTrue(InactiveGaps.accepts(height / 2, 0f, height, 2.5f))
        }
    }
    @Test fun `outside and nonfinite coordinates never acquire a key`() {
        for (y in listOf(-1f, 55f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(InactiveGaps.accepts(y, 0f, 54f, 2.5f))
        }
    }
}
