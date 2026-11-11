package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Arabic and Persian: Android's eleven-key layouts with no Shift, their marks and punctuation, their words, and the chooser. */
class ArabicScriptKeyboardTest {
    private val arabic = LanguageProfile.ARABIC
    private val persian = LanguageProfile.PERSIAN
    private val languages = listOf(arabic, persian)

    private fun letters(language: LanguageProfile, numberRow: Boolean = false, inputType: Int = 0) =
        KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, inputType = inputType,
            language = language, spaceLabel = language.nativeName)

    private fun key(layout: KeyboardLayout, label: String) = layout.rows.flatten().first { it.label == label }

    @Test
    fun `eleven letters on two rows, ten and delete below, and no shift`() {
        val rows = mapOf(
            arabic to listOf("ضصثقفغعهخحج", "شسيبلاتنمكط", "ذءؤرىةوزظد"),
            persian to listOf("ضصثقفغعهخحج", "شسیبلاتنمکگ", "ظطژزرذدپوچ"),
        )
        for (language in languages) {
            val keys = letters(language)
            assertEquals(language.code, 4, keys.rows.size)
            val (top, home, bottom) = keys.rows
            assertEquals(language.code, rows.getValue(language)[0].map { it.toString() }, top.map { it.label })
            assertEquals(language.code, rows.getValue(language)[1].map { it.toString() }, home.map { it.label })
            assertEquals(language.code, rows.getValue(language)[2].map { it.toString() }, bottom.dropLast(1).map { it.label })
            assertEquals(language.code, KeyAction.Backspace, bottom.last().action)
            assertFalse(language.code, keys.rows.flatten().any { it.action == KeyAction.Shift || it.isSpacer })
            for (row in keys.rows) assertEquals(language.code, KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
            // Each letter key is a tenth narrower, and the corrector's geometry is exactly what is drawn.
            assertEquals(language.code, 10f / 11f, top.first().width, 0.0001f)
            assertEquals(language.code, language.geometry, LetterGeometry.from(keys))
            assertEquals(language.code, language.geometry, LetterGeometry.from(letters(language, numberRow = true)))
            val first = language.geometry.center(rows.getValue(language)[0][0])!!
            val second = language.geometry.center(rows.getValue(language)[0][1])!!
            assertEquals(language.code, 10f / 11f, second.x - first.x, 0.0001f)
            assertTrue(language.code, language.shiftless && language.rightToLeft && language.deleteEndsBottomRow && language.columns == 11)
            assertEquals("Space, ${language.nativeName}", keys.rows.last().first { it.action == KeyAction.Space }.spokenLabel)
            // Every letter the corrector may put in a word can be typed from a key or a hold.
            val reachable = keys.rows.flatten().flatMap { listOfNotNull((it.action as? KeyAction.Text)?.value) + it.alternates }.joinToString("").toSet()
            assertTrue(language.code + " " + language.letters.filter { it !in reachable }.map { it.code.toString(16) }, language.letters.all { it in reachable })
        }
    }

    @Test
    fun `holds, punctuation and the script's own digits`() {
        val keys = letters(arabic)
        assertEquals(listOf("أ", "إ", "آ", "ء"), key(keys, "ا").alternates.take(4))
        assertEquals("ئ", key(keys, "ي").alternates.first())
        assertEquals(listOf("لا", "لأ", "لإ", "لآ"), key(keys, "ل").alternates.take(4))
        // The first ten top-row keys hold a digit, Western then Arabic; the eleventh has none.
        assertEquals(listOf("1", "١"), key(keys, "ض").alternates)
        assertEquals(listOf("0", "٠"), key(keys, "ح").alternates)
        assertTrue(key(keys, "ج").alternates.none { it.single().isDigit() })
        assertEquals("١", key(letters(arabic, numberRow = true), "1").alternates.first())
        assertEquals("۱", key(letters(persian, numberRow = true), "1").alternates.first())
        assertEquals("!", key(letters(LanguageProfile.ENGLISH, numberRow = true), "1").alternates.first())
        for (language in languages) {
            val bottom = letters(language).rows.last()
            val comma = bottom.first { it.label == "،" }
            assertEquals("Arabic comma", comma.spokenLabel)
            assertEquals(",", comma.alternates.first())
            val period = bottom.first { it.label == "." }
            assertEquals(listOf("؟", "!", "؛"), period.alternates.take(3))
            assertTrue(period.alternates.containsAll(listOf("ً", "َ", "ّ")))
            assertEquals("Arabic question mark", KeyboardLayout.spokenName("؟"))
            assertEquals("Shadda", KeyboardLayout.spokenName("ّ"))
            // A mail field still gets its @ in place of the comma.
            val mail = letters(language, inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            assertTrue(mail.rows.last().any { it.label == "@" } && mail.rows.last().none { it.label == "،" })
        }
        assertTrue(TextRules.attachesToPreviousWord('،') && TextRules.attachesToPreviousWord('؟') && TextRules.endsSentence('؟'))
        assertEquals(KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false).rows.last().map { it.label to it.alternates },
            letters(LanguageProfile.ENGLISH).rows.last().map { (if (it.action == KeyAction.Space) "" else it.label) to it.alternates })
    }

    @Test
    fun `persian has a non-joiner key beside the space bar`() {
        val bottom = letters(persian).rows.last()
        val space = bottom.indexOfFirst { it.action == KeyAction.Space }
        val joiner = bottom[space + 1]
        assertEquals(KeyAction.Text("‌"), joiner.action)
        assertEquals("Zero-width non-joiner", joiner.spokenLabel)
        assertEquals(listOf("‍"), joiner.alternates)
        assertEquals(KeyboardLayout.JOINER_LABEL, joiner.label)
        assertEquals(letters(arabic).rows.last()[space].width - 1f, bottom[space].width, 0.0001f)
        assertTrue(letters(arabic).rows.flatten().none { it.action == KeyAction.Text("‌") })
        assertTrue('‌' in persian.marks && '‌' in persian.letters && '‌' in persian.dropped)
        assertTrue('ً' in arabic.dropped && 'ّ' in arabic.marks && 'ّ' !in arabic.dropped)
    }

    @Test
    fun `their words, voices and place among the languages`() {
        assertEquals(listOf("ar-SA", "fa-IR"), languages.map { it.speechTag(listOf(Locale("en", "US"))) })
        assertEquals("ar-EG", arabic.speechTag(listOf(Locale("ar", "EG"))))
        for (language in languages) {
            assertTrue(language.words && !language.english)
            assertNull(language.priorAsset)
            assertEquals(setOf("و"), language.oneLetterWords)
            assertEquals(language, LanguageProfile.of(language.code))
            assertEquals("language_${language.code}", KeyboardSettings.extraKey(language))
            assertTrue(language in LanguageProfile.EXTRA && language in LanguageProfile.WITH_WORDS)
        }
        assertEquals(LanguageProfile.EXTRA.map { "language_${it.code}" }.toSet(), KeyboardSettings.EXTRA_KEYS)
        // Offered after the earlier languages, in the list's own order whatever order they were switched on in.
        assertEquals(listOf(LanguageProfile.ENGLISH, LanguageProfile.HEBREW, arabic, persian, LanguageProfile.IPA),
            LanguageChoice.offered(english = true, spanish = false, portuguese = false, ipa = true, hebrew = true, extra = listOf(persian, arabic)))
        assertEquals(listOf(persian), LanguageChoice.offered(english = false, spanish = false, portuguese = false, ipa = false, extra = listOf(persian)))
        assertEquals(listOf(LanguageProfile.ENGLISH), LanguageChoice.offered(english = false, spanish = false, portuguese = false, ipa = false))
    }

    @Test
    fun `the lists hold only the language's letters and complete a prefix`() {
        val probes = mapOf(
            arabic to listOf("ان" to "أنا", "شكر" to "شكراً", "الل" to "الله"),
            // Typed without the non-joiner or the tanwin, the full spelling is still offered.
            persian to listOf("میش" to "می\u200Cشود", "سل" to "سلام", "مثل" to "مثلاً"),
        )
        for ((language, expected) in probes) {
            val file = File("src/main/assets/${language.wordsAsset}")
            val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            assertEquals(language.code, 20_000, lines.size)
            val allowed = language.letters.toSet()
            assertTrue(language.code, lines.all { line -> line.substringBefore('\t').let { w -> (w.length >= 2 || w in language.oneLetterWords) && w.all { it in allowed } } })
            // No stray marks: the non-joiner never starts, ends or doubles, and the tanwin only ends a word.
            assertTrue(language.code, lines.none { line -> line.substringBefore('\t').let { w ->
                w.startsWith('‌') || w.endsWith('‌') || "‌‌" in w || w.dropLast(1).contains('ً') || w.contains('ـ') } })
            val swears = File("src/main/assets/${language.profanityAsset}").readLines().filter { !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
            assertTrue(language.code, swears.size >= 10 && lines.none { it.substringBefore('\t') in swears })
            assertTrue(language.code, File("src/main/assets/${language.lexiconAsset}").readLines().size > 100_000)
            val dictionary = WordDictionary.parse(file.readLines().asSequence(), language)
            val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
                oneLetterWords = language.oneLetterWords, wordRule = language.wordRule)
            for ((prefix, word) in expected) assertTrue("${language.code} $prefix ${model.predict(prefix, 10)}", word in model.predict(prefix, 10))
        }
        // Persian is written with its own yeh and kaf, never the Arabic ones the subtitle text mixes in.
        val persianWords = File("src/main/assets/words_fa.txt").readLines().filter { !it.startsWith("#") }.map { it.substringBefore('\t') }
        assertTrue(persianWords.none { 'ي' in it || 'ك' in it || 'ى' in it })
        assertTrue(persianWords.count { '‌' in it } > 500)
    }
}
