package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SymbolCuesTest {
    private fun offered(run: String, word: String = "") = SymbolCues.find(run, word)?.symbol

    @Test fun signsInParenthesesInAnyCase() {
        assertEquals("©", offered("(c)"))
        assertEquals("©", offered("(C)"))
        assertEquals("™", offered("Acme(tm)"))
        assertEquals("™", offered("(TM)"))
        assertEquals("®", offered("(r)"))
        assertEquals(SymbolCues.Found(4, "™"), SymbolCues.find("Acme(tm)", ""))
    }

    @Test fun arrowsComparisonsAndPlusMinus() {
        assertEquals("→", offered("->"))
        assertEquals("↔", offered("<->"))
        assertEquals("≤", offered("x<="))
        assertEquals("±", offered("+/-"))
        assertEquals("±", offered("+-"))
    }

    @Test fun aFractionOnlyStandsAlone() {
        assertEquals("½", offered("1/2"))
        assertEquals("¾", offered("3/4"))
        assertNull(offered("11/2"))
        assertNull(offered("2026/1/2"))
    }

    @Test fun theWholeWordOffersItsSign() {
        assertEquals("©", offered("copyright", "copyright"))
        assertEquals("™", offered("Trademark", "Trademark"))
        assertNull(offered("copy", "copy"))
        assertNull(offered("copyrights", "copyrights"))
        assertNull(offered("hello", "hello"))
    }

    @Test fun signsGoInPlain() {
        // No variation selector is inserted. Rendering and app character limits are separate concerns.
        for ((_, symbol) in SymbolCues.CUES + SymbolCues.WORDS.toList()) assertEquals(1, symbol.codePointCount(0, symbol.length))
    }
}
