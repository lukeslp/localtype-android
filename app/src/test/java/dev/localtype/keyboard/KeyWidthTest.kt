package dev.localtype.keyboard

import dev.localtype.keyboard.KeyWidthRules.Placement
import dev.localtype.keyboard.KeyWidthRules.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyWidthTest {
    private fun place(widthDp: Int, heightDp: Int, oneHanded: OneHanded = OneHanded.OFF, wide: WideKeys = WideKeys.CENTERED) =
        KeyWidthRules.place(oneHanded, wide, widthDp, heightDp)

    @Test
    fun `a saved value outside the choices is centered`() {
        assertEquals(WideKeys.CENTERED, WideKeys.of(null))
        assertEquals(WideKeys.CENTERED, WideKeys.of("SPLIT"))
        assertEquals(WideKeys.FULL_WIDTH, WideKeys.of("FULL_WIDTH"))
    }

    @Test
    fun `phones in portrait keep the full width`() {
        assertEquals(Placement(360, Side.FULL, rail = false), place(360, 640))
        assertEquals(Placement(411, Side.FULL, rail = false), place(411, 914))
        // A Fold8 cover and a Flip cover stay phones.
        assertEquals(Placement(475, Side.FULL, rail = false), place(475, 751))
    }

    @Test
    fun `wide windows center the keys at the cap`() {
        val capped = Placement(KeyWidthRules.MAX_KEYS_DP, Side.CENTER, rail = false)
        // Fold7 inner at 420 dpi, Pixel Fold inner, Fold8 inner as held, a tablet in both orientations, a desktop window.
        for ((w, h) in listOf(750 to 832, 841 to 701, 933 to 704, 1224 to 924, 1280 to 800, 800 to 1280, 1920 to 1200)) {
            assertEquals("$w x $h", capped, place(w, h))
        }
    }

    @Test
    fun `the 600 dp edge never makes the keys jump`() {
        assertEquals(Placement(599, Side.FULL, rail = false), place(599, 900))
        assertTrue(KeyWidthRules.wideWindow(KeyWidthRules.WIDE_WINDOW_DP, 900))
        assertFalse(KeyWidthRules.wideWindow(KeyWidthRules.WIDE_WINDOW_DP - 1, 900))
        // At the edge the cap and the window are the same width, so the keys fill it either way.
        assertEquals(Placement(600, Side.FULL, rail = false), place(600, 900))
        assertEquals(Placement(600, Side.CENTER, rail = false), place(601, 900))
    }

    @Test
    fun `a phone on its side keeps the full width, as fullscreen does`() {
        assertEquals(Placement(914, Side.FULL, rail = false), place(914, 411))
        assertFalse(KeyWidthRules.wideWindow(914, TypingRules.COMPACT_HEIGHT_DP - 1))
        assertTrue(KeyWidthRules.wideWindow(914, TypingRules.COMPACT_HEIGHT_DP))
        // The two rules share the edge: in landscape the keyboard either takes the screen or caps its keys.
        assertTrue(TypingRules.fullscreen(landscapeDefault = true, screenHeightDp = 411))
        assertFalse(TypingRules.fullscreen(landscapeDefault = true, screenHeightDp = 800))
        // An unknown height changes nothing.
        assertEquals(Placement(1280, Side.FULL, rail = false), place(1280, 0))
    }

    @Test
    fun `full width keeps the keys across a wide window`() {
        assertEquals(Placement(1280, Side.FULL, rail = false), place(1280, 800, wide = WideKeys.FULL_WIDTH))
        assertEquals(Placement(411, Side.FULL, rail = false), place(411, 914, wide = WideKeys.FULL_WIDTH))
    }

    @Test
    fun `one-handed wins over the cap`() {
        assertEquals(Placement(OneHandedRules.MAX_KEYS_DP, Side.RIGHT, rail = true), place(1280, 800, OneHanded.RIGHT))
        assertEquals(Placement(OneHandedRules.MAX_KEYS_DP, Side.LEFT, rail = true), place(933, 704, OneHanded.LEFT, WideKeys.FULL_WIDTH))
        assertEquals(Placement(304, Side.RIGHT, rail = true), place(360, 640, OneHanded.RIGHT))
        // Too narrow for one-handed: the full width, as before.
        assertEquals(Placement(300, Side.FULL, rail = false), place(300, 640, OneHanded.LEFT))
    }

    @Test
    fun `the cap is about the width a thumb hits well`() {
        // Ten letter cells less the 6 dp of side padding: about 59 dp, or 9.4 mm at 160 dp to the inch.
        val cell = (KeyWidthRules.MAX_KEYS_DP - 6) / KeyboardLayout.ROW_UNITS
        assertEquals(59.4f, cell, 0.01f)
        assertEquals(9.4, cell / 160.0 * 25.4, 0.05)
        // Wider than one-handed's keys, which stay a phone's width for one thumb.
        assertTrue(KeyWidthRules.MAX_KEYS_DP > OneHandedRules.MAX_KEYS_DP)
    }
}
