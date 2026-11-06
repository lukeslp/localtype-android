package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageChoiceTest {
    private val en = LanguageProfile.ENGLISH
    private val es = LanguageProfile.SPANISH
    private val pt = LanguageProfile.PORTUGUESE
    private val ipa = LanguageProfile.IPA

    @Test
    fun `English first, the IPA keyboard last`() {
        assertEquals(listOf(en), LanguageChoice.offered(english = true, spanish = false, portuguese = false, ipa = false))
        assertEquals(listOf(en, es, pt, ipa), LanguageChoice.offered(english = true, spanish = true, portuguese = true, ipa = true))
        assertEquals(listOf(es, ipa), LanguageChoice.offered(english = false, spanish = true, portuguese = false, ipa = true))
    }

    @Test
    fun `a language with words always stays`() {
        assertEquals(listOf(en), LanguageChoice.offered(english = false, spanish = false, portuguese = false, ipa = false))
        // The IPA keyboard alone has nothing to suggest or correct, so English comes back before it.
        assertEquals(listOf(en, ipa), LanguageChoice.offered(english = false, spanish = false, portuguese = false, ipa = true))
    }

    @Test
    fun `only the last language with words is locked on`() {
        val englishOnly = listOf(en, ipa)
        assertFalse(LanguageChoice.canTurnOff(en, englishOnly))
        assertTrue(LanguageChoice.canTurnOff(ipa, englishOnly))
        assertTrue(LanguageChoice.canTurnOff(es, englishOnly))
        val two = listOf(en, pt)
        assertTrue(LanguageChoice.canTurnOff(en, two))
        assertTrue(LanguageChoice.canTurnOff(pt, two))
        assertFalse(LanguageChoice.canTurnOff(pt, listOf(pt, ipa)))
    }
}
