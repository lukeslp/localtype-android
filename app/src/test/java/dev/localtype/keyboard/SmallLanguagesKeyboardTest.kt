package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Welsh, Irish, Māori and Hawaiian: QWERTY with each language's marks on the holds, their words, and the chooser. */
class SmallLanguagesKeyboardTest {
    private val languages = listOf(LanguageProfile.WELSH, LanguageProfile.IRISH, LanguageProfile.MAORI, LanguageProfile.HAWAIIAN)

    private fun letters(language: LanguageProfile, numberRow: Boolean = false) =
        KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, language = language,
            spaceLabel = language.nativeName)

    private fun key(layout: KeyboardLayout, label: String) = layout.rows.flatten().first { it.label == label }

    @Test
    fun `each keeps qwerty, its shift and the geometry the corrector reads`() {
        for (language in languages) {
            val keys = letters(language)
            assertEquals(language.code, "qwertyuiop".map { it.toString() }, keys.rows[0].map { it.label })
            assertEquals(language.code, KeyAction.Shift, keys.rows[2].first().action)
            for (row in keys.rows) assertEquals(language.code, KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
            assertEquals(language.code, language.geometry, LetterGeometry.from(keys))
            assertEquals(language.code, language.geometry, LetterGeometry.from(letters(language, numberRow = true)))
            assertEquals("Space, ${language.nativeName}", keys.rows.last().first { it.action == KeyAction.Space }.spokenLabel)
            assertFalse(language.english || language.shiftless || language.rightToLeft)
            assertNull(language.priorAsset)
            assertEquals(language, LanguageProfile.of(language.code))
        }
    }

    @Test
    fun `the marks come first on the holds`() {
        val welsh = letters(LanguageProfile.WELSH)
        assertEquals(listOf("â", "á", "à", "ä"), key(welsh, "a").alternates.take(4))
        assertEquals("ŵ", key(welsh, "w").alternates.first())
        assertEquals("ŷ", key(welsh, "y").alternates.first())
        assertEquals("w", LanguageProfile.WELSH.fold("ŵ"))
        val irish = letters(LanguageProfile.IRISH)
        for ((vowel, fada) in listOf("a" to "á", "e" to "é", "i" to "í", "o" to "ó", "u" to "ú")) assertEquals(fada, key(irish, vowel).alternates.first())
        assertEquals("failte", LanguageProfile.IRISH.fold("fáilte"))
        for (language in listOf(LanguageProfile.MAORI, LanguageProfile.HAWAIIAN)) {
            val keys = letters(language)
            for ((vowel, macron) in listOf("a" to "ā", "e" to "ē", "i" to "ī", "o" to "ō", "u" to "ū")) {
                assertEquals(language.code, macron, key(keys, vowel).alternates.first())
            }
            assertEquals("maori", language.fold("māori"))
        }
    }

    @Test
    fun `hawaiian has an okina key after l, and it is a letter that never folds`() {
        val keys = letters(LanguageProfile.HAWAIIAN)
        assertEquals("asdfghjklʻ".map { it.toString() }, keys.rows[1].filter { !it.isSpacer }.map { it.label })
        assertEquals(KeyAction.Text("ʻ"), key(keys, "ʻ").action)
        assertEquals("ʻOkina", key(keys, "ʻ").spokenLabel)
        assertTrue('ʻ'.isLetter())
        assertEquals("hawaiʻi", LanguageProfile.HAWAIIAN.fold("hawaiʻi"))
        assertTrue(AdaptiveNgramModel.WORD_REGEX.matches("ʻaʻole"))
    }

    @Test
    fun `voices and their place in the chooser`() {
        val english = listOf(Locale("en", "US"))
        assertEquals(listOf("cy-GB", "ga-IE", "mi-NZ", "haw-US"), languages.map { it.speechTag(english) })
        assertEquals(listOf(LanguageProfile.ENGLISH) + languages + LanguageProfile.IPA,
            LanguageChoice.offered(english = true, spanish = false, portuguese = false, ipa = true,
                welsh = true, irish = true, maori = true, hawaiian = true))
        assertEquals(listOf(LanguageProfile.HAWAIIAN),
            LanguageChoice.offered(english = false, spanish = false, portuguese = false, ipa = false, hawaiian = true))
    }

    @Test
    fun `the lists hold only the language's letters and complete a prefix`() {
        val expected = mapOf(
            LanguageProfile.WELSH to listOf("yn" to "yn"),
            LanguageProfile.IRISH to listOf("fail" to "fáilte"),
            LanguageProfile.MAORI to listOf("kor" to "kōrero"),
            LanguageProfile.HAWAIIAN to listOf("lak" to "lākou", "hawai" to "hawaiʻi"),
        )
        for ((language, probes) in expected) {
            val file = File("src/main/assets/${language.wordsAsset}")
            val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            // Māori Wikipedia is small: about 1,700 words survive the three-article and lowercase rules.
            val floor = if (language == LanguageProfile.MAORI) 1_500 else 5_000
            assertTrue("${language.code} ${lines.size}", lines.size >= floor)
            val allowed = language.letters.toSet() + setOf('\'', '-')
            assertTrue(language.code, lines.all { line -> line.substringBefore('\t').all { it in allowed } })
            val swears = File("src/main/assets/${language.profanityAsset}").readLines().filter { !it.startsWith("#") }
                .map { it.substringBefore('\t') }.toSet()
            assertTrue(language.code, lines.none { it.substringBefore('\t') in swears })
            assertTrue(language.code, File("src/main/assets/${language.lexiconAsset}").readLines().size > lines.size / 2)
            val dictionary = WordDictionary.parse(file.readLines().asSequence(), language)
            val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
                oneLetterWords = language.oneLetterWords)
            for ((prefix, word) in probes) assertTrue("${language.code} $prefix", word in model.predict(prefix, 10))
        }
    }

    @Test
    fun `a bare prefix reaches accented words in the ranker and a typed mark is kept`() {
        val dictionary = WordDictionary.parse(sequenceOf("fáilte\t90", "fail\t40"),
            LanguageProfile.IRISH)
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList())
        assertTrue("fáilte" in model.predict("fai", 5))
        assertTrue(dictionary.completesPrefix("fáilte", "fail"))
        assertFalse(dictionary.completesPrefix("fail", "fái"))
        val english = WordDictionary.parse(sequenceOf("cafe\t50"))
        assertFalse(english.completesPrefix("café", "cafe"))
    }
}
