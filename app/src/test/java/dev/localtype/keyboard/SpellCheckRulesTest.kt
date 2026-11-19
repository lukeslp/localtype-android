package dev.localtype.keyboard

import dev.localtype.keyboard.SpellCheckRules.Position
import dev.localtype.keyboard.SpellCheckRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** The spell checker's decisions on the bundled lists: what it underlines, what it leaves alone and what it offers. */
class SpellCheckRulesTest {
    private fun lines(name: String) = File("src/main/assets/$name").readLines()

    /** The service's own loader, streaming the bundled lists. */
    private fun rules(language: LanguageProfile) = SpellCheckLists.build(language) { File("src/main/assets/$it").reader() }.rules

    private val english by lazy { rules(LanguageProfile.ENGLISH) }
    private val spanish by lazy { rules(LanguageProfile.SPANISH) }
    private val portuguese by lazy { rules(LanguageProfile.PORTUGUESE) }

    private fun swears(language: LanguageProfile) = lines(language.profanityAsset)
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }

    private fun ranked(language: LanguageProfile) = lines(language.wordsAsset)
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }

    private fun SpellCheckRules.flagged(text: String): Map<String, List<String>> =
        check(text).filter { it.result.verdict == Verdict.MISSPELLED }
            .associate { text.substring(it.start, it.start + it.length) to it.result.suggestions }

    @Test
    fun `a typo is underlined and the corrector's pick comes first`() {
        val result = english.checkWord("teh", Position.MID_SENTENCE)
        assertEquals(Verdict.MISSPELLED, result.verdict)
        assertEquals("the", result.suggestions.first())
        assertTrue(result.suggestions.size <= SpellCheckRules.MAX_SUGGESTIONS)
        assertEquals(result.suggestions, result.suggestions.distinct())
        assertEquals(listOf("recieve"), english.flagged("Did you recieve it?").keys.toList())
        assertEquals("receive", english.checkWord("recieve", Position.MID_SENTENCE).suggestions.first())
    }

    @Test
    fun `known words are never underlined`() {
        for (word in listOf("the", "hello", "labyrinth", "gonna", "screenshot", "friend's", "parents'", "it's", "don't", "I", "a")) {
            assertFalse(word, english.checkWord(word, Position.MID_SENTENCE).verdict == Verdict.MISSPELLED)
        }
        assertEquals(Verdict.KNOWN, english.checkWord("hello").verdict)
        assertEquals(Verdict.KNOWN, english.checkWord("Hello", Position.MID_SENTENCE).verdict)
        assertEquals(emptyMap<String, List<String>>(), english.flagged("The quick brown fox jumps over the lazy dog. It's my friend's dog, isn't it?"))
    }

    @Test
    fun `no listed word is underlined in any language`() {
        for ((language, rules) in listOf(LanguageProfile.ENGLISH to english, LanguageProfile.SPANISH to spanish, LanguageProfile.PORTUGUESE to portuguese)) {
            // English's missing-apostrophe forms are listed words that the keyboard always replaces.
            val wrong = ranked(language).take(10_000)
                .filter { rules.checkWord(it, Position.MID_SENTENCE).verdict == Verdict.MISSPELLED }
                .filterNot { language.english && Contractions.propose(it, null, false)?.automatic == true }
            assertEquals(language.code, emptyList<String>(), wrong)
        }
    }

    @Test
    fun `the keyboard's own apostrophe fixes are underlined in English only`() {
        assertEquals(listOf("don't"), english.checkWord("dont", Position.MID_SENTENCE).suggestions)
        assertEquals(listOf("Don't"), english.checkWord("Dont", Position.SENTENCE_START).suggestions)
        assertEquals(listOf("I'm"), english.checkWord("im", Position.MID_SENTENCE).suggestions)
        // "ill", "id" and "lets" are words in their own right; the keyboard only offers those.
        for (word in listOf("ill", "id", "lets", "its", "were", "well")) {
            assertEquals(word, Verdict.KNOWN, english.checkWord(word, Position.MID_SENTENCE).verdict)
        }
        assertFalse(spanish.checkWord("dont", Position.MID_SENTENCE).suggestions.contains("don't"))
    }

    @Test
    fun `digits, links, addresses, handles, hashtags and identifiers are left alone`() {
        for (token in listOf("h264", "v2", "3rd", "abc123", "example.com", "www.exmaple.com", "luke@exmaple.com", "@somehandel",
            "#somehashtagg", "snake_csae", "camelCsae", "iPhonee", "path/to/thnig", "C:\\thnig")) {
            assertEquals(token, Verdict.UNSURE, english.checkWord(token, Position.MID_SENTENCE).verdict)
        }
        assertEquals(emptyMap<String, List<String>>(),
            english.flagged("see https://exmaple.com/thnig or mail luke@exmaple.com about #somehashtagg by @somehandel in h264 and snake_csae"))
    }

    @Test
    fun `all caps, inner capitals, one letter, hyphens and foreign letters are left alone`() {
        for (token in listOf("NASAA", "XKCD", "McCoyy", "x", "q", "wel-known", "cafée", "naïvve", "straße", "привет", "日本語")) {
            assertEquals(token, Verdict.UNSURE, english.checkWord(token, Position.MID_SENTENCE).verdict)
        }
        assertEquals(emptyMap<String, List<String>>(), english.flagged("The XKCDD comic by q about a wel-knownn straßee"))
        assertEquals(Verdict.UNSURE, english.checkWord("").verdict)
        assertEquals(Verdict.UNSURE, english.checkWord("   ").verdict)
        assertEquals(Verdict.UNSURE, english.checkWord("a".repeat(SpellCheckRules.MAX_WORD_LENGTH + 1)).verdict)
    }

    @Test
    fun `a capital mid sentence is a name`() {
        assertEquals(Verdict.UNSURE, english.checkWord("Teh", Position.MID_SENTENCE).verdict)
        assertEquals(Verdict.UNSURE, english.checkWord("Kowalczykk", Position.MID_SENTENCE).verdict)
        assertEquals(emptyMap<String, List<String>>(), english.flagged("I met Zxqvor and Teh at the Recieve office"))
    }

    @Test
    fun `a capital at a sentence start is underlined only with a fix`() {
        assertEquals("The", english.checkWord("Teh", Position.SENTENCE_START).suggestions.first())
        assertEquals("The", english.checkWord("Teh", Position.UNKNOWN).suggestions.first())
        assertEquals(Verdict.UNSURE, english.checkWord("Zxqvorgh", Position.SENTENCE_START).verdict)
        assertEquals(Verdict.UNSURE, english.checkWord("Zxqvorgh", Position.UNKNOWN).verdict)
        val flagged = english.flagged("Teh dog ran. Teh cat sat! Zxqvorgh left.\nRecieve it")
        assertEquals(setOf("Teh", "Recieve"), flagged.keys)
        assertEquals("The", flagged.getValue("Teh").first())
        assertEquals("Receive", flagged.getValue("Recieve").first())
    }

    @Test
    fun `offsets name the word in the text`() {
        val text = "  I will (recieve) the 'pakage', ok"
        val words = english.check(text)
        assertEquals(listOf("I", "will", "recieve", "the", "pakage", "ok"), words.map { text.substring(it.start, it.start + it.length) })
        assertEquals(listOf("recieve", "pakage"), words.filter { it.result.verdict == Verdict.MISSPELLED }.map { text.substring(it.start, it.start + it.length) })
        assertEquals(emptyList<SpellCheckRules.Word>(), english.check(""))
        assertEquals(emptyList<SpellCheckRules.Word>(), english.check(" \n\t "))
    }

    @Test
    fun `text past the limit is not checked`() {
        val text = "teh ".repeat(SpellCheckRules.MAX_TEXT_LENGTH / 4 + 50)
        val words = english.check(text)
        assertTrue(words.isNotEmpty())
        assertTrue(words.all { it.start + it.length <= SpellCheckRules.MAX_TEXT_LENGTH })
    }

    @Test
    fun `a swear word is known and never suggested`() {
        for ((language, rules) in listOf(LanguageProfile.ENGLISH to english, LanguageProfile.SPANISH to spanish, LanguageProfile.PORTUGUESE to portuguese)) {
            val listed = swears(language).toSet()
            assertTrue(listed.isNotEmpty())
            for (swear in listed) {
                if (swear.length < 2 || swear.any { it !in language.letters }) continue
                assertEquals(Verdict.KNOWN, rules.checkWord(swear, Position.MID_SENTENCE).verdict)
                // Every one-letter slip of it: dropped, doubled and swapped letters.
                val slips = swear.indices.map { swear.removeRange(it, it + 1) } +
                    swear.indices.map { swear.substring(0, it) + swear[it] + swear.substring(it) } +
                    (0 until swear.length - 1).map { swear.substring(0, it) + swear[it + 1] + swear[it] + swear.substring(it + 2) }
                for (slip in slips) {
                    for (position in Position.values()) {
                        val offered = rules.checkWord(slip, position).suggestions.map { it.lowercase() }
                        assertTrue("${language.code}: a slip offered a swear word", offered.none { it in listed })
                        assertTrue(offered.none { SuggestionWordRules.isExcluded(it, language) })
                    }
                }
            }
        }
    }

    @Test
    fun `limit bounds the suggestions`() {
        assertEquals(Verdict.MISSPELLED, english.checkWord("recieve", Position.MID_SENTENCE, limit = 0).verdict)
        assertEquals(emptyList<String>(), english.checkWord("recieve", Position.MID_SENTENCE, limit = 0).suggestions)
        assertEquals(1, english.checkWord("recieve", Position.MID_SENTENCE, limit = 1).suggestions.size)
        assertTrue(english.checkWord("recieve", Position.MID_SENTENCE, limit = 99).suggestions.size <= SpellCheckRules.MAX_SUGGESTIONS)
        assertEquals(emptyList<String>(), english.checkWord("dont", Position.MID_SENTENCE, limit = 0).suggestions)
    }

    @Test
    fun `Spanish missing accents and slips`() {
        assertEquals("también", spanish.checkWord("tambien", Position.MID_SENTENCE).suggestions.first())
        assertEquals("También", spanish.checkWord("Tambien", Position.SENTENCE_START).suggestions.first())
        assertEquals(Verdict.KNOWN, spanish.checkWord("mañana", Position.MID_SENTENCE).verdict)
        assertEquals(Verdict.KNOWN, spanish.checkWord("qué", Position.MID_SENTENCE).verdict)
        val flagged = spanish.flagged("Hola. ¿Tambien vienes mañana? Si, tambien voy con Zxqvorgh")
        assertEquals(setOf("Tambien", "tambien"), flagged.keys)
        assertEquals("También", flagged.getValue("Tambien").first())
        // English letters only: ñ and the accents are Spanish's, w is in both.
        assertEquals(Verdict.UNSURE, spanish.checkWord("coração", Position.MID_SENTENCE).verdict)
    }

    @Test
    fun `Portuguese missing accents and slips`() {
        assertEquals("também", portuguese.checkWord("tambem", Position.MID_SENTENCE).suggestions.first())
        assertEquals("Também", portuguese.checkWord("Tambem", Position.UNKNOWN).suggestions.first())
        assertEquals(Verdict.KNOWN, portuguese.checkWord("coração", Position.MID_SENTENCE).verdict)
        assertEquals(Verdict.KNOWN, portuguese.checkWord("não", Position.MID_SENTENCE).verdict)
        assertEquals(Verdict.UNSURE, portuguese.checkWord("mañana", Position.MID_SENTENCE).verdict)
    }

    @Test
    fun `only English, Spanish and Portuguese locales get a checker`() {
        assertSame(LanguageProfile.ENGLISH, SpellCheckRules.languageFor("en"))
        assertSame(LanguageProfile.ENGLISH, SpellCheckRules.languageFor("en_US"))
        assertSame(LanguageProfile.ENGLISH, SpellCheckRules.languageFor("en-GB"))
        assertSame(LanguageProfile.SPANISH, SpellCheckRules.languageFor("es_MX"))
        assertSame(LanguageProfile.SPANISH, SpellCheckRules.languageFor(" ES "))
        assertSame(LanguageProfile.PORTUGUESE, SpellCheckRules.languageFor("pt_BR"))
        for (locale in listOf(null, "", "fr", "fr_FR", "de", "eng", "e", "he", "cy", "ipa", "zz_ZZ", "_US")) {
            assertNull(locale, SpellCheckRules.languageFor(locale))
        }
    }

    @Test
    fun `one instance answers the same from many threads`() {
        val text = "Teh quick brwon fox recieve teh pakage. Dont worry about h264 or Zxqvorgh"
        val expected = english.check(text).map { Triple(it.start, it.result.verdict, it.result.suggestions) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val answers = pool.invokeAll(List(64) { Callable { english.check(text).map { Triple(it.start, it.result.verdict, it.result.suggestions) } } })
            answers.forEach { assertEquals(expected, it.get()) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the manifest declares the service and the subtypes are the checked languages`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".LocalTypeSpellCheckerService\"").substringBefore("</service>")
        assertTrue(service.contains("android:permission=\"android.permission.BIND_TEXT_SERVICE\""))
        assertTrue(service.contains("<action android:name=\"android.service.textservice.SpellCheckerService\" />"))
        assertTrue(service.contains("android:name=\"android.view.textservice.scs\""))
        assertFalse(service.contains("android:process"))
        val subtypes = Regex("android:subtypeLocale=\"([^\"]+)\"").findAll(File("src/main/res/xml/spellchecker.xml").readText()).map { it.groupValues[1] }.toList()
        assertEquals(SpellCheckRules.LANGUAGES.map { it.code }, subtypes)
        subtypes.forEach { assertSame(LanguageProfile.of(it), SpellCheckRules.languageFor(it)) }
    }

    /** Other apps' text: the service and its rules may not reach a log, a store, a preference or the network. */
    @Test
    fun `the service and its rules touch no log, store or network`() {
        val sources = listOf(
            File("src/main/java/dev/localtype/keyboard/LocalTypeSpellCheckerService.kt"),
            File("../engine/src/commonMain/kotlin/dev/localtype/keyboard/SpellCheckRules.kt"),
            File("src/main/java/dev/localtype/keyboard/SpellCheckLists.kt"),
            File("src/main/java/dev/localtype/keyboard/AcceptedWords.kt"),
            File("src/main/java/dev/localtype/keyboard/SpellCheckLanguage.kt"),
        )
        val forbidden = Regex("""\bLog\b|println|PredictionLog|TypingLog|LearningStore|LearnedPair|PersonalLearning|AdaptiveNgramModel\(|SharedPreferences|KeyboardSettings|""" +
            """getSharedPreferences|filesDir|cacheDir|openFileOutput|FileOutputStream|\bFile\(|java\.net|HttpURLConnection|\bURL\(|Socket|ModelKitBridge|RemoteModel""")
        for (file in sources) {
            val code = file.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            assertTrue(file.name, code.size > 10)
            assertEquals(file.name, emptyList<String>(), code.filter(forbidden::containsMatchIn))
        }
    }
}
