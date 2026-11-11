package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Navajo: QWERTY with a glottal stop key, tone and nasal marks on the holds, a word mark, and nothing corrected unasked. */
class NavajoKeyboardTest {
    private val navajo = LanguageProfile.NAVAJO
    private val acute = "́"
    private val horse = "łį${acute}į${acute}ʼ"   // "łį́į́ʼ"

    private fun letters(numberRow: Boolean = false) =
        KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, language = navajo, spaceLabel = navajo.nativeName)

    private fun key(layout: KeyboardLayout, label: String) = layout.rows.flatten().first { it.label == label }

    @Test
    fun `qwerty with a glottal stop key after l, and the marks on the holds`() {
        val keys = letters()
        assertEquals("qwertyuiop".map { it.toString() }, keys.rows[0].map { it.label })
        assertEquals("asdfghjklʼ".map { it.toString() }, keys.rows[1].filter { !it.isSpacer }.map { it.label })
        assertEquals(KeyAction.Shift, keys.rows[2].first().action)
        assertEquals("Glottal stop", key(keys, "ʼ").spokenLabel)
        assertEquals(0x02BC, key(keys, "ʼ").label.single().code)
        assertTrue('ʼ'.isLetter())
        assertEquals(listOf("á", "ą", "ą$acute"), key(keys, "a").alternates.take(3))
        assertEquals(listOf("é", "ę", "ę$acute"), key(keys, "e").alternates.take(3))
        assertEquals(listOf("í", "į", "į$acute"), key(keys, "i").alternates.take(3))
        assertEquals(listOf("ó", "ǫ", "ǫ$acute"), key(keys, "o").alternates.take(3))
        assertEquals("ł", key(keys, "l").alternates.first())
        assertEquals("ń", key(keys, "n").alternates.first())
        for (row in keys.rows) assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
        assertEquals(navajo.geometry, LetterGeometry.from(keys))
        assertEquals(navajo.geometry, LetterGeometry.from(letters(numberRow = true)))
        assertFalse(navajo.shiftless || navajo.rightToLeft || navajo.english)
        // Shift still makes capitals, marks and all.
        assertEquals("Ą$acute", TypingRules.shifted("ą$acute"))
        assertEquals("Ł", TypingRules.shifted("ł"))
    }

    @Test
    fun `the combining acute stays inside the word, and every mark folds`() {
        assertEquals(acute, navajo.marks)
        assertEquals(acute, navajo.dropped)
        assertEquals(horse, navajo.wordRule.activePrefix("shí $horse"))
        // The shared rule would have ended the word at the first acute.
        assertEquals("", WordRule.DEFAULT.activePrefix("shí łį$acute"))
        assertEquals("lii'", navajo.fold(horse))
        assertEquals(4, navajo.bareLength(horse))
        assertEquals("doo", navajo.fold("dóó"))
        val words = WordDictionary.parse(sequenceOf("$horse\t500", "dóó\t900", "łeh\t400", "shí\t300"), navajo)
        assertEquals(horse, words.completions("lii", 3).first().first)
        assertEquals(horse, words.completions("łį", 3).first().first)
        // An apostrophe typed for the glottal stop is read as one.
        assertTrue(words.completesPrefix(horse, "lii'"))
        assertTrue(words.completesPrefix(horse, "łį${acute}į${acute}ʼ"))
        assertEquals("dóó", words.completions("do", 3).first().first)
        // A tone that was typed has to be there: "dó" is not the start of "doo".
        assertFalse(WordDictionary.parse(sequenceOf("doo\t500"), navajo).completesPrefix("doo", "dó"))
    }

    @Test
    fun `fixes are offered and never applied`() {
        assertFalse(navajo.autocorrect)
        for (language in LanguageProfile.ALL) if (language !== navajo) assertTrue(language.code, language.autocorrect)
        val words = WordDictionary.parse(sequenceOf("$horse\t500", "dóó\t900", "łeh\t400"), navajo)
        val corrector = Corrector({ words }, language = { navajo })
        // The corrector still finds the word; the keyboard only shows it (LanguageProfile.autocorrect).
        assertEquals(horse, corrector.propose("lii'")?.word)
        assertEquals("dóó", corrector.propose("doo")?.word)
        assertNull(corrector.propose(horse))
        // Six marks at the price of an accent each: ł, two nasal vowels, two acutes and the glottal stop.
        assertEquals(0.6f, Corrector.typingCost("lii'", horse, language = navajo), 0.0001f)
        assertEquals("t͡ʃ", TypingRules.shifted("t͡ʃ"))
        assertEquals(".com", TypingRules.shifted(".com"))
    }

    @Test
    fun `its voice, words and place among the languages`() {
        assertEquals("nv-US", navajo.speechTag(listOf(Locale("en", "US"))))
        assertTrue(navajo.oneLetterWords.isEmpty())
        assertNull(navajo.priorAsset)
        assertEquals(navajo, LanguageProfile.of("nv"))
        assertEquals("language_nv", KeyboardSettings.extraKey(navajo))
        assertEquals(listOf(LanguageProfile.ENGLISH, navajo), LanguageChoice.offered(english = true, spanish = false, portuguese = false, ipa = false, extra = listOf(navajo)))
        assertTrue(SuggestionWordRules.forLanguage(navajo).isEmpty())
    }

    @Test
    fun `the list holds only navajo letters and completes a bare prefix`() {
        val file = File("src/main/assets/${navajo.wordsAsset}")
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("${lines.size}", lines.size >= 2_500)
        val allowed = navajo.letters.toSet() + setOf('\'', '-')
        assertTrue(lines.all { line -> line.substringBefore('\t').let { w -> w.all { it in allowed } && w.first().isLetter() } })
        // The wiki writes the glottal stop as the letter, never an apostrophe.
        assertTrue(lines.none { '\'' in it.substringBefore('\t') })
        assertTrue(lines.count { 'ʼ' in it.substringBefore('\t') } > 500)
        assertTrue(lines.count { acute in it.substringBefore('\t') } > 100)
        assertTrue(File("src/main/assets/${navajo.lexiconAsset}").readLines().size > lines.size)
        val dictionary = WordDictionary.parse(file.readLines().asSequence(), navajo)
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
            oneLetterWords = navajo.oneLetterWords, wordRule = navajo.wordRule)
        for ((prefix, word) in listOf("doo" to "dóó", "aado" to "áádóó", "lizh" to "łizhin", "holo" to "hólǫ$acute"))
            assertTrue("$prefix ${model.predict(prefix, 10)}", word in model.predict(prefix, 10))
    }
}
