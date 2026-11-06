package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Hebrew in the keyboard: the Israeli layout with no Shift, its words, and its place in the chooser. */
class HebrewKeyboardTest {
    private val hebrew = LanguageProfile.HEBREW

    private fun letters(numberRow: Boolean = false) =
        KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, language = hebrew, spaceLabel = "עברית")

    @Test
    fun `the israeli layout has delete on the top row and no shift`() {
        val keys = letters()
        assertEquals(4, keys.rows.size)
        val (top, home, bottom) = keys.rows
        assertEquals("קראטוןםפ".map { it.toString() }, top.filter { it.action is KeyAction.Text }.map { it.label })
        assertEquals(KeyAction.Backspace, top.last().action)
        assertEquals("שדגכעיחלךף".map { it.toString() }, home.filter { !it.isSpacer }.map { it.label })
        assertEquals("זסבהנמצתץ".map { it.toString() }, bottom.filter { !it.isSpacer }.map { it.label })
        assertFalse(keys.rows.flatten().any { it.action == KeyAction.Shift })
        for (row in keys.rows) assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
        assertEquals(hebrew.geometry, LetterGeometry.from(keys))
        assertEquals(hebrew.geometry, LetterGeometry.from(letters(numberRow = true)))
        assertEquals("Space, עברית", keys.rows.last().first { it.action == KeyAction.Space }.spokenLabel)
        // Every one of the 27 letters, finals included, has its own key.
        assertEquals(hebrew.letters.toSet(), keys.rows.flatten().mapNotNull { it.label.singleOrNull()?.takeIf(Char::isLetter) }.toSet())
    }

    @Test
    fun `english keeps its shift and bottom-row delete`() {
        val english = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false)
        assertEquals(KeyAction.Shift, english.rows[2].first().action)
        assertEquals(KeyAction.Backspace, english.rows[2].last().action)
    }

    @Test
    fun `its words, voice and place among the languages`() {
        assertTrue(hebrew.words)
        assertTrue(hebrew.shiftless && hebrew.rightToLeft)
        assertFalse(hebrew.english)
        assertTrue(hebrew.oneLetterWords.isEmpty())
        assertNull(hebrew.priorAsset)
        assertEquals("he-IL", hebrew.speechTag(listOf(Locale("en", "US"))))
        assertEquals(listOf("words_he.txt", "words_he_profanity.txt", "words_known_he.txt"),
            listOf(hebrew.wordsAsset, hebrew.profanityAsset, hebrew.lexiconAsset))
        assertEquals(hebrew, LanguageProfile.of("he"))
        assertEquals(listOf(LanguageProfile.ENGLISH, hebrew, LanguageProfile.IPA),
            LanguageChoice.offered(english = true, spanish = false, portuguese = false, ipa = true, hebrew = true))
    }

    @Test
    fun `the lists hold only hebrew letters and complete a prefix`() {
        val lines = File("src/main/assets/words_he.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(20_000, lines.size)
        val letters = hebrew.letters.toSet()
        assertTrue(lines.all { line -> line.substringBefore('\t').let { w -> w.length >= 2 && w.all { it in letters } } })
        val swears = File("src/main/assets/words_he_profanity.txt").readLines().filter { !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        assertTrue(lines.none { it.substringBefore('\t') in swears })
        val dictionary = WordDictionary.parse(File("src/main/assets/words_he.txt").readLines().asSequence(), hebrew)
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
            oneLetterWords = hebrew.oneLetterWords)
        assertTrue(model.predict("של", 3).isNotEmpty())
    }
}
