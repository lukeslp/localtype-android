package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLanguageTest {
    @Test
    fun `the page follows the phone where there is a translation`() {
        assertEquals("en", PageLanguage.shown("", listOf("en")))
        assertEquals("es", PageLanguage.shown("", listOf("es")))
        assertEquals("pt", PageLanguage.shown("", listOf("pt", "en")))
        // German then English: English, not the Spanish further down the list.
        assertEquals("en", PageLanguage.shown("", listOf("de", "en", "es")))
        assertEquals("es", PageLanguage.shown("", listOf("fr", "es")))
        assertEquals("en", PageLanguage.shown("", listOf("ja")))
        assertEquals("en", PageLanguage.shown("", emptyList()))
    }

    @Test
    fun `a chosen language wins, and an unknown choice is ignored`() {
        assertEquals("es", PageLanguage.shown("es", listOf("en")))
        assertEquals("en", PageLanguage.shown("en", listOf("pt")))
        assertEquals("pt", PageLanguage.shown("xx", listOf("pt")))
    }

    @Test
    fun `Settings shown in appears only when there is a choice`() {
        val english = listOf(LanguageProfile.ENGLISH)
        assertFalse(PageLanguage.rowShown("", listOf("en"), english))
        assertFalse(PageLanguage.rowShown("", listOf("en"), english + LanguageProfile.IPA))
        assertTrue(PageLanguage.rowShown("", listOf("en"), english + LanguageProfile.SPANISH))
        assertTrue(PageLanguage.rowShown("", listOf("pt"), english))
        assertTrue(PageLanguage.rowShown("en", listOf("en"), english))
    }

    @Test
    fun `Portuguese is Brazilian`() {
        assertEquals("pt-BR", PageLanguage.locale("pt").toLanguageTag())
        assertEquals("es", PageLanguage.locale("es").toLanguageTag())
    }
}
