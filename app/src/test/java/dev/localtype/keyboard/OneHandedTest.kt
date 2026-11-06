package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneHandedTest {
    @Test
    fun `a saved value outside the choices is off`() {
        assertEquals(OneHanded.OFF, OneHanded.of(null))
        assertEquals(OneHanded.OFF, OneHanded.of("MIDDLE"))
        assertEquals(OneHanded.LEFT, OneHanded.of("LEFT"))
    }

    @Test
    fun `the keys leave room for the rail and stop growing past a thumb's reach`() {
        assertEquals(304, OneHandedRules.keysDp(OneHanded.RIGHT, 360))
        assertEquals(355, OneHandedRules.keysDp(OneHanded.LEFT, 411))
        assertEquals(OneHandedRules.MAX_KEYS_DP, OneHandedRules.keysDp(OneHanded.RIGHT, 800))
        assertEquals(360, OneHandedRules.keysDp(OneHanded.OFF, 360))
    }

    @Test
    fun `too narrow a window keeps full-width keys`() {
        assertFalse(OneHandedRules.applies(OneHanded.RIGHT, 300))
        assertEquals(300, OneHandedRules.keysDp(OneHanded.RIGHT, 300))
        assertTrue(OneHandedRules.applies(OneHanded.RIGHT, OneHandedRules.MIN_WIDTH_DP))
        assertFalse(OneHandedRules.applies(OneHanded.OFF, 411))
    }

    @Test
    fun `the tool turns it on to the right and off again, and the rail swaps sides`() {
        assertEquals(OneHanded.RIGHT, OneHandedRules.toggled(OneHanded.OFF))
        assertEquals(OneHanded.OFF, OneHandedRules.toggled(OneHanded.LEFT))
        assertEquals(OneHanded.LEFT, OneHandedRules.swapped(OneHanded.RIGHT))
        assertEquals(OneHanded.RIGHT, OneHandedRules.swapped(OneHanded.LEFT))
        assertEquals(OneHanded.OFF, OneHandedRules.swapped(OneHanded.OFF))
    }

    @Test
    fun `one-handed sits in the keyboard section and runs anywhere`() {
        val sections = StripTools.panel(StripTools.Field(voice = true, proofread = true)).toMap()
        assertTrue(StripTool.ONE_HANDED in sections.getValue(StripTools.Group.KEYBOARD))
        assertTrue(StripTools.allowed(StripTool.ONE_HANDED, GestureRules.State(enabled = false, sensitive = true)))
    }
}
