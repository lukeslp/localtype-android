package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Galician beside Spanish and Portuguese, Scottish Gaelic beside Irish: their keys, accents, words and place in the chooser. */
class RelatedLanguagesKeyboardTest {
    private val galician = LanguageProfile.GALICIAN
    private val gaelic = LanguageProfile.SCOTTISH_GAELIC
    private val languages = listOf(galician, gaelic)

    private fun letters(language: LanguageProfile, numberRow: Boolean = false) =
        KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, language = language, spaceLabel = language.nativeName)

    private fun key(layout: KeyboardLayout, label: String) = layout.rows.flatten().first { it.label == label }

    @Test
    fun `each keeps qwerty, shift and the geometry the corrector reads`() {
        for (language in languages) {
            val keys = letters(language)
            assertEquals(language.code, "qwertyuiop".map { it.toString() }, keys.rows[0].map { it.label })
            assertEquals(language.code, KeyAction.Shift, keys.rows[2].first().action)
            for (row in keys.rows) assertEquals(language.code, KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
            assertEquals(language.code, language.geometry, LetterGeometry.from(keys))
            assertEquals(language.code, language.geometry, LetterGeometry.from(letters(language, numberRow = true)))
            assertFalse(language.english || language.shiftless || language.rightToLeft)
            // Both keep the shared word rule: they need nothing the Arabic-script languages brought.
            assertTrue(language.code, language.wordRule === WordRule.DEFAULT && language.columns == 10)
            assertNull(language.priorAsset)
            assertEquals(language, LanguageProfile.of(language.code))
            assertTrue(language in LanguageProfile.EXTRA)
        }
        // Galician is typed on the Spanish layout.
        assertEquals(LanguageProfile.SPANISH.keyRows, galician.keyRows)
        assertEquals(LanguageProfile.SPANISH.geometry, galician.geometry)
        assertEquals(LanguageProfile.IRISH.keyRows, gaelic.keyRows)
    }

    @Test
    fun `the accents come first on the holds and fold`() {
        val gl = letters(galician)
        for ((vowel, accent) in listOf("a" to "á", "e" to "é", "i" to "í", "o" to "ó", "u" to "ú")) assertEquals(accent, key(gl, vowel).alternates.first())
        assertEquals(listOf("í", "ï"), key(gl, "i").alternates.take(2))
        assertEquals(listOf("ú", "ü"), key(gl, "u").alternates.take(2))
        assertEquals("tamen", galician.fold("tamén"))
        assertEquals("espana", galician.fold("españa"))
        val gd = letters(gaelic)
        for ((vowel, grave) in listOf("a" to "à", "e" to "è", "i" to "ì", "o" to "ò", "u" to "ù")) assertEquals(grave, key(gd, vowel).alternates.first())
        assertEquals("gaidhlig", gaelic.fold("gàidhlig"))
        // Gaelic's apostrophes and hyphens are part of its words under the shared rule.
        assertEquals("a-mach", WordRule.DEFAULT.activePrefix("chaidh e a-mach"))
        assertEquals("dh'fhalbh", WordRule.DEFAULT.activePrefix("an dh'fhalbh"))
    }

    @Test
    fun `voices, slur lists and their place in the chooser`() {
        assertEquals(listOf("gl-ES", "gd-GB"), languages.map { it.speechTag(listOf(Locale("en", "US"))) })
        assertEquals(listOf(LanguageProfile.ENGLISH, LanguageProfile.SPANISH, galician, gaelic),
            LanguageChoice.offered(english = true, spanish = true, portuguese = false, ipa = false, extra = listOf(gaelic, galician)))
        // Galician takes its neighbours' slur lists; Gaelic has only the shared one.
        assertEquals(SuggestionWordRules.EXCLUDED_HASHES_ES + SuggestionWordRules.EXCLUDED_HASHES_PT, SuggestionWordRules.forLanguage(galician))
        assertTrue(SuggestionWordRules.forLanguage(gaelic).isEmpty())
    }

    @Test
    fun `the lists hold only the language's letters and complete a bare prefix`() {
        val expected = mapOf(
            galician to listOf("tam" to "tamén", "cidad" to "cidade", "galeg" to "galego"),
            // Names such as Gàidhlig are recognized, not suggested: the lists are lowercase.
            gaelic to listOf("ait" to "àite", "agu" to "agus", "mor" to "mòr"),
        )
        for ((language, probes) in expected) {
            val file = File("src/main/assets/${language.wordsAsset}")
            val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            assertTrue("${language.code} ${lines.size}", lines.size >= 10_000)
            val allowed = language.letters.toSet() + setOf('\'', '-')
            assertTrue(language.code, lines.all { line -> line.substringBefore('\t').all { it in allowed } })
            val swears = File("src/main/assets/${language.profanityAsset}").readLines().filter { !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
            assertTrue(language.code, lines.none { it.substringBefore('\t') in swears })
            assertTrue(language.code, File("src/main/assets/${language.lexiconAsset}").readLines().size > lines.size)
            val dictionary = WordDictionary.parse(file.readLines().asSequence(), language)
            val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
                oneLetterWords = language.oneLetterWords)
            for ((prefix, word) in probes) assertTrue("${language.code} $prefix ${model.predict(prefix, 10)}", word in model.predict(prefix, 10))
            val corrector = Corrector({ dictionary }, language = { language })
            assertNull(language.code, corrector.correct(probes.first().second))
        }
        assertEquals("tamén", Corrector({ WordDictionary.parse(File("src/main/assets/words_gl.txt").readLines().asSequence(), galician) }, language = { galician }).correct("tamen"))
    }
}
