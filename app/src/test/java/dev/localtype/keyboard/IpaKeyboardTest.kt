package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The IPA keyboard: a symbols keyboard in the hold-Space chooser, with no words behind it. */
class IpaKeyboardTest {
    private val ipa = LanguageProfile.IPA

    private fun letters(language: LanguageProfile = ipa, numberRow: Boolean = false) =
        KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, language = language, spaceLabel = "IPA")

    private fun KeyboardLayout.key(label: String) = rows.flatten().first { it.label == label }

    @Test
    fun `a row of the commonest symbols sits above the letters, every row full`() {
        val keys = letters()
        assertEquals(5, keys.rows.size)
        assertEquals("əɪʊɛæɑɔʃθŋ".map { it.toString() }, keys.rows[0].map { it.label })
        assertEquals("qwertyuiop".map { it.toString() }, keys.rows[1].map { it.label })
        keys.rows.forEach { row -> assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.0001f) }
        // With the number row on, it goes above everything.
        assertEquals("1234567890".map { it.toString() }, letters(numberRow = true).rows[0].map { it.label })
    }

    @Test
    fun `a hold offers the symbols related to a key and no accented letters`() {
        val keys = letters()
        assertEquals(listOf("ʃ", "ʂ", "ɕ"), keys.key("s").alternates)
        assertEquals("ŋ", keys.key("n").alternates.first())
        assertEquals("ə", keys.key("e").alternates.first())
        assertEquals("ð", keys.key("θ").alternates.single())
        assertEquals("ʒ", keys.key("ʃ").alternates.first())
        // The top letter row keeps its digit, after the symbols.
        assertEquals(listOf("ʔ", "ʕ", "ɢ", "1"), keys.key("q").alternates)
        assertFalse("é" in keys.key("e").alternates)
        assertFalse("á" in keys.key("a").alternates)
        keys.rows.flatten().forEach { assertEquals(it.alternates.distinct(), it.alternates) }
    }

    @Test
    fun `the comma key types the stress mark and the period key holds length and brackets`() {
        val bottom = letters().rows.last()
        val stress = bottom.first { it.label == "ˈ" }
        assertEquals("primary stress", stress.spokenLabel)
        assertTrue(stress.alternates.containsAll(listOf("ˌ", "ʰ", "ʲ", "̃", "̥", "̩")))
        assertEquals("ː", bottom.first { it.label == "." }.alternates.first())
        assertTrue(bottom.first { it.label == "." }.alternates.containsAll(listOf("[", "]", "/")))
        assertFalse(bottom.any { it.label == "," })
        assertEquals("Space, IPA", bottom.first { it.action == KeyAction.Space }.spokenLabel)
    }

    @Test
    fun `talkback says each symbol's name`() {
        assertEquals("schwa", KeyboardLayout.spokenName("ə"))
        assertEquals("eng", KeyboardLayout.spokenName("ŋ"))
        assertEquals("theta", KeyboardLayout.spokenName("θ"))
        assertEquals("t esh", KeyboardLayout.spokenName("t͡ʃ"))
        assertTrue(KeyboardLayout.spokenName("̃").startsWith("nasalized"))
        // Every symbol on the keyboard has a name, so none is read out as an unknown glyph.
        val unnamed = letters().rows.flatten().filter { it.action is KeyAction.Text }.flatMap { listOf(it.label) + it.alternates }
            .filter { s -> s.isNotEmpty() && s.any { it.code > 0x7F } && KeyboardLayout.spokenName(s) == s }
        assertEquals(emptyList<String>(), unnamed.distinct())
    }

    @Test
    fun `the second symbols page has the chart's diacritics and tone`() {
        fun page(mode: KeyboardLayout.Mode, language: LanguageProfile) =
            KeyboardLayout.build(mode, showSwitchKey = false, language = language, spaceLabel = "IPA")
        val toPage = page(KeyboardLayout.Mode.SYMBOLS, ipa).rows.flatten().single { it.action == KeyAction.ToMoreSymbols }
        assertEquals("Diacritics", toPage.spokenLabel)
        assertEquals("More symbols", page(KeyboardLayout.Mode.SYMBOLS, LanguageProfile.ENGLISH).rows.flatten()
            .single { it.action == KeyAction.ToMoreSymbols }.spokenLabel)
        val marks = page(KeyboardLayout.Mode.MORE_SYMBOLS, ipa)
        marks.rows.forEach { row -> assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.0001f) }
        val typed = marks.rows.dropLast(1).flatten().mapNotNull { (it.action as? KeyAction.Text)?.value }
        // A combining mark types alone and is drawn on a dotted circle; the spacing ones type themselves.
        val raised = marks.rows.flatten().single { it.action == KeyAction.Text("\u031D") }
        assertEquals("◌\u031D", raised.label)
        assertEquals(listOf("˔"), raised.alternates)
        assertTrue(typed.containsAll(listOf("\u0325", "\u032C", "\u0324", "\u0330", "\u0303", "\u032B", "\u0361", "˥", "\u0301")))
        assertEquals(listOf("˦", "˧", "˨", "˩"), marks.rows.flatten().single { it.label == "˥" }.alternates)
        assertEquals(typed.distinct(), typed)
        // Every mark and every hold has its name for TalkBack.
        val unnamed = marks.rows.flatten().filter { it.action is KeyAction.Text }.flatMap { (it.action as KeyAction.Text).value.let(::listOf) + it.alternates }
            .filter { s -> s.isNotEmpty() && s.any { it.code > 0x7F } && KeyboardLayout.spokenName(s) == s }
        assertEquals(emptyList<String>(), unnamed.distinct())
        // Other languages keep the =\< page as it was.
        assertTrue(page(KeyboardLayout.Mode.MORE_SYMBOLS, LanguageProfile.ENGLISH).rows.flatten().any { it.label == "π" })
    }

    @Test
    fun `english keys are as they were`() {
        val english = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false)
        assertEquals(4, english.rows.size)
        assertEquals(listOf("'", "\"", "-", "@", "&"), english.key(",").alternates)
        assertEquals(listOf("?", "!", ",", ";", ":", "…", "'", "\""), english.key(".").alternates)
        assertEquals(listOf("3") + KeyboardLayout.ACCENTS.getValue('e'), english.key("e").alternates)
    }

    @Test
    fun `the ipa keyboard has no words and is offered after the languages`() {
        assertFalse(ipa.words)
        assertTrue(LanguageProfile.ENGLISH.words && LanguageProfile.SPANISH.words)
        assertEquals(LanguageProfile.WITH_WORDS + ipa, LanguageProfile.ALL)
        assertFalse(ipa in LanguageProfile.WITH_WORDS)
        assertEquals(ipa, LanguageProfile.of("ipa"))
        // Dictation can't hear IPA; it asks for English.
        assertEquals("en-US", ipa.speechTag(listOf(Locale("es", "MX"))))
        // The corrector's keys still exist for its letters, though nothing is corrected.
        for (c in 'a'..'z') assertTrue("$c", ipa.geometry.center(c) != null)
    }
}
