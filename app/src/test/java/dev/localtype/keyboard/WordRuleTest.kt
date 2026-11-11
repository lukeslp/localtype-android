package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-language word rule: marks that sit inside a language's words, and everything English that must not move. */
class WordRuleTest {
    private val persian = LanguageProfile.PERSIAN
    private val arabic = LanguageProfile.ARABIC
    private val zwnj = "‌"
    private val goes = "می${zwnj}رود"        // "می‌رود"
    private val thanks = "شكراً"         // "شكراً"

    @Test
    fun `english and every earlier language keep the shared rule`() {
        assertEquals(AdaptiveNgramModel.WORD_REGEX.pattern, WordRule.DEFAULT.word.pattern)
        for (language in listOf(LanguageProfile.ENGLISH, LanguageProfile.SPANISH, LanguageProfile.PORTUGUESE, LanguageProfile.HEBREW,
            LanguageProfile.WELSH, LanguageProfile.IRISH, LanguageProfile.MAORI, LanguageProfile.HAWAIIAN, LanguageProfile.IPA)) {
            assertSame(language.code, WordRule.DEFAULT, language.wordRule)
            assertEquals(language.code, "", language.marks)
            assertEquals(language.code, 10, language.columns)
            assertFalse(language.code, language.deleteEndsBottomRow || language.joinerKey)
        }
        assertEquals("don't", WordRule.DEFAULT.activePrefix("we don't"))
        // The default rule ends a word at a mark or a joiner, as it always has.
        assertEquals("رود", WordRule.DEFAULT.activePrefix("من $goes"))
        assertEquals("", WordRule.DEFAULT.activePrefix("من $thanks"))
    }

    @Test
    fun `a mark continues a word and may end it, but never starts one`() {
        val rule = persian.wordRule
        assertEquals(goes, rule.activePrefix("من $goes"))
        assertEquals("می$zwnj", rule.activePrefix("من می$zwnj"))
        assertEquals("", rule.activePrefix("من $zwnj"))
        assertEquals(listOf("من", goes), rule.word.findAll("من $goes.").map { it.value }.toList())
        assertEquals(thanks, arabic.wordRule.activePrefix("قال $thanks"))
        assertEquals("رود", rule.continuation.find("رود است")?.value)
        assertEquals("${zwnj}رود", rule.continuation.find("${zwnj}رود است")?.value)
        assertTrue(rule.ends('ً') && rule.ends('د') && !rule.ends('-') && rule.continues('-'))
        // Only combining marks and invisible joiners can be marks: nothing that means something to a regex.
        for (bad in listOf("-", "]", "a", " ")) assertTrue(bad, runCatching { WordRule.of(bad) }.isFailure)
    }

    @Test
    fun `the model reads its language's words`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = WordDictionary.EMPTY, sentenceStarters = emptyList(),
            oneLetterWords = persian.oneLetterWords, wordRule = persian.wordRule)
        assertEquals("می${zwnj}ر", model.activePrefix("او می${zwnj}ر"))
        assertEquals(goes, model.lastCompletedWord("او $goes "))
        assertEquals(thanks.replace('ك', 'ک'), AdaptiveNgramModel.lastCompletedWord("${thanks.replace('ك', 'ک')} ", persian.wordRule))
        assertNull(AdaptiveNgramModel.lastCompletedWord("$thanks "))
        assertTrue(model.isSentenceStart("سلام. می$zwnj"))
    }

    @Test
    fun `a stored word may hold marks, and still has to be one word`() {
        for (word in listOf("the", "don't", goes, thanks, "ą́ą́ʼ", "kamaʻāina")) assertTrue(word, WordRule.STORED.matches(word))
        for (word in listOf("", "a b", "${zwnj}می", "́a", "a.b", "a1")) assertFalse(word, WordRule.STORED.matches(word))
        assertEquals(goes, AddedWords.normalize(goes, persian))
        assertEquals(goes, BlockedWords.normalize(goes))
        assertNull(AddedWords.normalize("می $zwnj", persian))
    }

    @Test
    fun `an edit takes the whole word, marks included`() {
        val before = "او می${zwnj}ر"
        val edit = WordEdits.suggestion(before, "", selected = false, word = goes, revisited = false, readLimit = 280, rule = persian.wordRule)
        assertNotNull(edit)
        assertEquals("می${zwnj}ر".length, edit!!.replacement.before)
        // Under the shared rule the same tap would have replaced only the letter after the non-joiner.
        assertEquals(1, WordEdits.suggestion(before, "", selected = false, word = goes, revisited = false, readLimit = 280)!!.replacement.before)
        // The caret inside a word: what follows the non-joiner is still that word.
        val inside = WordEdits.suggestion("او می", "${zwnj}رود است", selected = false, word = goes, revisited = false, readLimit = 280, rule = persian.wordRule)
        // The rest of the word and the space after it, which the suggestion reuses.
        assertEquals("${zwnj}رود ".length, inside!!.replacement.after)
        var asked: String? = null
        val separator = WordEdits.separator("او میرود", "", selected = false, separator = " ", revisited = false, suggestions = true,
            automatic = true, readLimit = 280, capitalizeI = false, rule = persian.wordRule) { asked = it; goes }
        assertEquals("میرود", asked)
        assertTrue(separator.corrected)
        assertEquals("او $goes", separator.beforeSeparator)
        assertEquals("میرود".length, separator.replacement.before)
        // A word that ends in its tanwin is still a word end.
        var tanwin: String? = null
        WordEdits.separator("قال $thanks", "", selected = false, separator = " ", revisited = false, suggestions = true,
            automatic = true, readLimit = 280, capitalizeI = false, rule = arabic.wordRule) { tanwin = it; null }
        assertEquals(thanks, tanwin)
    }

    @Test
    fun `a word typed without its mark completes to the spelling that has it`() {
        val persianWords = WordDictionary.parse(sequenceOf("$goes\t900", "میز\t500", "مثلاً\t400", "آب\t300"), persian)
        assertEquals(goes, persianWords.completions("میر", 3).first().first)
        assertEquals(goes, persianWords.completions("می${zwnj}ر", 3).first().first)
        assertTrue(persianWords.completesPrefix(goes, "میرود"))
        assertTrue(persianWords.completesPrefix("مثلاً", "مثلا"))
        assertTrue(persianWords.completesPrefix("آب", "اب"))
        // A mark that was typed has to be in the word: "میز" has no non-joiner.
        assertFalse(persianWords.completesPrefix("میز", "می$zwnj"))
        assertEquals(listOf("میز"), persianWords.completions("میز", 3).map { it.first })
        assertEquals("میرود", persian.fold(goes))
        assertEquals(5, persian.bareLength(goes))
        val arabicWords = WordDictionary.parse(sequenceOf("أنا\t900", "$thanks\t800", "مدرسة\t500", "إلى\t400"), arabic)
        assertEquals("أنا", arabicWords.completions("ان", 3).first().first)
        assertEquals(thanks, arabicWords.completions("شكرا", 3).first().first)
        assertEquals("مدرسة", arabicWords.completions("مدرسه", 3).first().first)
        assertEquals("إلى", arabicWords.completions("الي", 3).first().first)
    }

    @Test
    fun `the corrector repairs a missing mark for the price of an accent`() {
        val words = WordDictionary.parse(sequenceOf("$goes\t900", "میز\t500", "کتاب\t800", "مثلاً\t700"), persian)
        val corrector = Corrector({ words }, language = { persian })
        assertEquals(goes, corrector.correct("میرود"))
        assertEquals("مثلاً", corrector.correct("مثلا"))
        // A neighbouring key and a missing non-joiner together.
        assertEquals(goes, corrector.propose("میرود".replace('د', 'ذ'))?.word)
        assertNull(corrector.correct(goes))
        assertEquals(0.1f, Corrector.typingCost("میرود", goes, language = persian), 0.0001f)
        assertEquals(0.1f, Corrector.typingCost("کتا${zwnj}ب", "کتاب", language = persian), 0.0001f)
        val arabicWords = WordDictionary.parse(sequenceOf("أنا\t900", "$thanks\t800", "مدرسة\t500"), arabic)
        val arabicCorrector = Corrector({ arabicWords }, language = { arabic })
        assertEquals("أنا", arabicCorrector.correct("انا"))
        assertEquals(thanks, arabicCorrector.correct("شكرا"))
        assertEquals("مدرسة", arabicCorrector.correct("مدرسه"))
        // A word typed with a vowel mark the lists never spell is left exactly as typed.
        assertNull(arabicCorrector.propose("مدرسّة"))
        // English is untouched: a combining mark still makes a word the corrector won't look at.
        assertNull(Corrector({ WordDictionary.parse(sequenceOf("cafe\t500")) }).propose("café"))
    }

    @Test
    fun `the context before a word reads through its marks`() {
        assertEquals(goes, CorrectionTextContext.capture("او $goes کت", persian.marks).previous)
        assertEquals("رود", CorrectionTextContext.capture("او $goes کت").previous)
        assertEquals("the", CorrectionTextContext.capture("the ca").previous)
    }
}
