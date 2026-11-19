package dev.localtype.keyboard

import dev.localtype.keyboard.SpellCheckRules.Position
import dev.localtype.keyboard.SpellCheckRules.Result
import dev.localtype.keyboard.SpellCheckRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the spell checker adds around [SpellCheckRules]: the keyboard's current words, Android's user dictionary
 * (Add to dictionary), the list loader and the session's language on Android 8.0 to 9.
 */
class SpellCheckerExtrasTest {
    private fun asset(name: String): Reader = File("src/main/assets/$name").reader()
    private val english by lazy { SpellCheckLists.build(LanguageProfile.ENGLISH, ::asset) }
    private val spanish by lazy { SpellCheckLists.build(LanguageProfile.SPANISH, ::asset) }

    private fun SpellCheckLists.Lists.answer(word: String, user: AcceptedWords = AcceptedWords.EMPTY, position: Position = Position.MID_SENTENCE): Result =
        user.apply(word, current.apply(word, rules.checkWord(word, position)))

    // Current words

    @Test
    fun `the keyboard's current words are not underlined`() {
        val named = listOf("gmail", "hotmail", "icloud", "chatgpt", "gpu", "gpus", "llm", "llms", "parasocial", "delulu", "netanyahu", "mamdani", "zohran")
        // Without the current words these were underlined in other apps although the keyboard accepts them.
        assertEquals(named, named.filter { english.rules.checkWord(it, Position.MID_SENTENCE).verdict == Verdict.MISSPELLED })
        for (word in named) assertEquals(word, Verdict.KNOWN, english.answer(word).verdict)
        for (word in listOf("Gmail", "Netanyahu", "Mamdani's", "GPUs", "iCloud", "ChatGPT", "LLMs")) {
            assertEquals(word, Verdict.KNOWN, english.answer(word, position = Position.SENTENCE_START).verdict)
        }
        // Every key in the file, in the case it is listed.
        val keys = File("src/main/assets/${CurrentVocabulary.ASSET}").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }
        assertTrue(keys.size >= 20)
        assertEquals(emptyList<String>(), keys.filter { english.answer(it).verdict == Verdict.MISSPELLED })
        assertEquals(keys.toSet(), english.current.let { current -> keys.filter { current.knows(it) }.toSet() })
        // A real typo is still a typo, and the current words are never offered by themselves.
        assertEquals("receive", english.answer("recieve").suggestions.first())
        assertEquals(Verdict.MISSPELLED, english.answer("gmial").verdict)
    }

    @Test
    fun `current words are English only`() {
        assertEquals(0, spanish.current.size)
        assertSame(AcceptedWords.EMPTY, spanish.current)
    }

    @Test
    fun `a current-words file that does not parse leaves the rest loading`() {
        val lists = SpellCheckLists.build(LanguageProfile.ENGLISH) { name ->
            if (name == CurrentVocabulary.ASSET) StringReader("not a vocabulary") else asset(name)
        }
        assertEquals(0, lists.current.size)
        assertEquals("receive", lists.rules.checkWord("recieve", Position.MID_SENTENCE).suggestions.first())
    }

    @Test
    fun `the streamed loader builds what fromLines builds`() {
        fun lines(name: String) = File("src/main/assets/$name").readLines()
        for (language in SpellCheckRules.LANGUAGES) {
            val reference = SpellCheckRules.fromLines(
                language,
                words = lines(language.wordsAsset),
                modern = if (language.english) lines("words_en_modern.txt") else emptyList(),
                known = lines(language.lexiconAsset),
                swears = lines(language.profanityAsset),
            )
            val streamed = SpellCheckLists.build(language, ::asset).rules
            val text = "Teh quick brwon fox recieve teh pakage. Dont worry, vámos alguêm amanhã trabalhar hoy Zxqvorgh mañana"
            assertEquals(language.code, reference.check(text).map { Triple(it.start, it.result.verdict, it.result.suggestions) },
                streamed.check(text).map { Triple(it.start, it.result.verdict, it.result.suggestions) })
            val sample = File("src/main/assets/${language.lexiconAsset}").readLines().filterIndexed { i, _ -> i % 997 == 0 }
            assertEquals(language.code, sample.map { reference.checkWord(it).verdict }, sample.map { streamed.checkWord(it).verdict })
        }
    }

    // Android's user dictionary

    @Test
    fun `a word in the user dictionary is no longer underlined`() {
        val word = "blorptastic"
        assertEquals(Verdict.MISSPELLED, english.answer(word).verdict)
        // What Add to dictionary stores: the word as typed and the spell checker's locale ("en"), or no locale.
        val added = AcceptedWords.forLanguage(listOf(UserWord(word, "en")), LanguageProfile.ENGLISH)
        assertEquals(Verdict.KNOWN, english.answer(word, added).verdict)
        assertEquals(Verdict.KNOWN, english.answer("Blorptastic", added, Position.SENTENCE_START).verdict)
        assertEquals(Verdict.KNOWN, english.answer("blorptastic's", added).verdict)
        assertEquals(Verdict.KNOWN, english.answer("BLORPTASTIC", added).verdict)
        // Another language's entry does not count; an entry for all languages does.
        assertEquals(0, AcceptedWords.forLanguage(listOf(UserWord("vámosx", "es_ES")), LanguageProfile.ENGLISH).size)
        val everywhere = listOf(UserWord("blorptastic", null), UserWord("hoyx", ""))
        assertEquals(Verdict.KNOWN, spanish.answer("hoyx", AcceptedWords.forLanguage(everywhere, LanguageProfile.SPANISH)).verdict)
    }

    @Test
    fun `an entry with capitals matches its own spelling and offers it to lowercase`() {
        val words = AcceptedWords.of(listOf("LocalType", "iPhone"))
        for (typed in listOf("LocalType", "LOCALTYPE", "iPhone", "IPhone", "IPHONE", "iPhone's", "iPhone’s", "LocalType'")) assertTrue(typed, words.knows(typed))
        for (typed in listOf("localtype", "Localtype", "iphone", "Iphone", "iPhones", "iPhon")) assertFalse(typed, words.knows(typed))
        assertEquals("LocalType", words.spelling("localtype"))
        assertEquals("iPhone's", words.spelling("iphone's"))
        assertNull(words.spelling("Localtype"))
        // Typed in lowercase it is underlined, with the entry first.
        val answer = english.answer("localtype", words)
        assertEquals(Verdict.MISSPELLED, answer.verdict)
        assertEquals("LocalType", answer.suggestions.first())
        assertTrue(answer.suggestions.size <= SpellCheckRules.MAX_SUGGESTIONS)
        // Inner capitals are left alone by the lists; the entry makes them known, so an old underline can clear.
        assertEquals(Verdict.UNSURE, english.rules.checkWord("LocalType", Position.MID_SENTENCE).verdict)
        assertEquals(Verdict.KNOWN, english.answer("LocalType", words).verdict)
    }

    @Test
    fun `a lowercase entry accepts any capitalization`() {
        val words = AcceptedWords.of(listOf("blorptastic", "wibble’s"))
        for (typed in listOf("blorptastic", "Blorptastic", "BLORPTASTIC", "bLoRpTaStIc", "wibble's", "Wibble’s")) assertTrue(typed, words.knows(typed))
        assertNull(words.spelling("blorptastic"))
    }

    @Test
    fun `apply keeps known words, lifts unsure ones and honors the limit`() {
        val words = AcceptedWords.of(listOf("iPhone", "zorp"))
        val known = Result(Verdict.KNOWN)
        assertSame(known, words.apply("anything", known))
        assertEquals(Verdict.KNOWN, words.apply("zorp", Result(Verdict.UNSURE)).verdict)
        val unsure = Result(Verdict.UNSURE)
        assertSame(unsure, words.apply("iphone", unsure))
        val flagged = Result(Verdict.MISSPELLED, listOf("phone", "iPhone", "ohone"))
        assertEquals(listOf("iPhone", "phone", "ohone"), words.apply("iphone", flagged).suggestions)
        assertEquals(listOf("iPhone"), words.apply("iphone", flagged, limit = 1).suggestions)
        assertEquals(emptyList<String>(), words.apply("iphone", flagged, limit = 0).suggestions)
        val other = Result(Verdict.MISSPELLED, listOf("the"))
        assertSame(other, words.apply("teh", other))
    }

    @Test
    fun `phrases, blanks and over-long entries are left out`() {
        val words = AcceptedWords.of(listOf("", "  ", "two words", "tab\tword", "x".repeat(AcceptedWords.MAX_LENGTH + 1), "ok"))
        assertEquals(1, words.size)
        assertFalse(words.knows("two"))
        assertEquals(AcceptedWords.MAX_WORDS, AcceptedWords.of((0 until AcceptedWords.MAX_WORDS + 50).map { "w$it" }).size)
    }

    @Test
    fun `entry locales name the language or all languages`() {
        val en = LanguageProfile.ENGLISH
        for (locale in listOf(null, "", " ", "en", "en_US", "en-GB", "EN_us")) assertTrue("$locale", AcceptedWords.appliesTo(locale, en))
        for (locale in listOf("es", "es_ES", "pt_BR", "fr", "eng")) assertFalse(locale, AcceptedWords.appliesTo(locale, en))
        assertTrue(AcceptedWords.appliesTo("pt_BR", LanguageProfile.PORTUGUESE))
    }

    @Test
    fun `the user dictionary is read, read again on a change, and kept only while bound`() {
        val rows = mutableListOf(UserWord("blorptastic", "en"))
        val reads = AtomicInteger()
        val words = UserDictionaryWords { reads.incrementAndGet(); rows.toList() }
        assertEquals(0, words.words(LanguageProfile.ENGLISH).size)
        words.start()
        words.start()
        assertTrue(words.words(LanguageProfile.ENGLISH, 2_000).knows("blorptastic"))
        assertEquals(1, reads.get())
        assertFalse(words.words(LanguageProfile.SPANISH).knows("blorptastic"))
        rows += UserWord("zorp", null)
        words.refresh()
        assertTrue(waitFor { words.words(LanguageProfile.SPANISH).knows("zorp") })
        words.release()
        assertSame(AcceptedWords.EMPTY, words.words(LanguageProfile.ENGLISH, 2_000))
        words.refresh()
        Thread.sleep(50)
        assertEquals(2, reads.get())
    }

    @Test
    fun `a dictionary that can't be read leaves the checker as it was`() {
        var fail = false
        val words = UserDictionaryWords { if (fail) throw SecurityException("no") else listOf(UserWord("zorp", "en")) }
        words.start()
        assertTrue(words.words(LanguageProfile.ENGLISH, 2_000).knows("zorp"))
        fail = true
        words.refresh()
        Thread.sleep(100)
        assertTrue(words.words(LanguageProfile.ENGLISH).knows("zorp"))
        // Refused from the start (null rows, or a throw): nothing, and no wait after the first.
        val refused = UserDictionaryWords { null }
        refused.start()
        assertEquals(0, refused.words(LanguageProfile.ENGLISH, 2_000).size)
        val throwing = UserDictionaryWords { throw IllegalStateException("gone") }
        throwing.start()
        assertEquals(0, throwing.words(LanguageProfile.ENGLISH, 2_000).size)
    }

    // The loader

    /** Assets from disk, with [blocked] held until [gate] opens and [broken] failing to open. */
    private class Assets(val blocked: String? = null, val broken: String? = null) {
        val gate = CountDownLatch(1)
        val opened = AtomicInteger()
        fun open(name: String): Reader {
            opened.incrementAndGet()
            if (name == broken) throw IOException("unreadable")
            if (name == blocked) gate.await(30, TimeUnit.SECONDS)
            return File("src/main/assets/$name").reader()
        }
    }

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `a request waits for a read in progress`() {
        val assets = Assets(blocked = "words_es.txt")
        val lists = SpellCheckLists(assets::open)
        assertNull(lists.get(LanguageProfile.SPANISH))
        Thread { Thread.sleep(150); assets.gate.countDown() }.start()
        val loaded = lists.get(LanguageProfile.SPANISH, 10_000)
        assertNotNull(loaded)
        assertSame(loaded, lists.get(LanguageProfile.SPANISH, 10_000))
        assertEquals("vamos", loaded!!.rules.checkWord("vámos", Position.MID_SENTENCE).suggestions.first())
    }

    @Test
    fun `after one wait runs out, later requests for that read don't wait again`() {
        val assets = Assets(blocked = "words_es.txt")
        val lists = SpellCheckLists(assets::open)
        assertTrue(millis { assertNull(lists.get(LanguageProfile.SPANISH, 300)) } >= 250)
        assertTrue(millis { assertNull(lists.get(LanguageProfile.SPANISH, 5_000)) } < 200)
        assets.gate.countDown()
        assertTrue(waitFor { lists.get(LanguageProfile.SPANISH) != null })
        // Another language's read has its own wait.
        assertNotNull(lists.get(LanguageProfile.PORTUGUESE, 10_000))
    }

    @Test
    fun `a list that can't be read answers nothing, without waiting or reading again`() {
        val assets = Assets(broken = "words_known_pt.txt")
        val lists = SpellCheckLists(assets::open)
        assertNull(lists.get(LanguageProfile.PORTUGUESE, 10_000))
        val opened = assets.opened.get()
        assertTrue(millis { assertNull(lists.get(LanguageProfile.PORTUGUESE, 10_000)) } < 200)
        assertEquals(opened, assets.opened.get())
    }

    @Test
    fun `after release nothing is read or held`() {
        val assets = Assets()
        val lists = SpellCheckLists(assets::open)
        assertNotNull(lists.get(LanguageProfile.ENGLISH, 10_000))
        lists.release()
        val opened = assets.opened.get()
        assertNull(lists.get(LanguageProfile.ENGLISH, 10_000))
        assertNull(lists.get(LanguageProfile.SPANISH, 10_000))
        Thread.sleep(50)
        assertEquals(opened, assets.opened.get())
    }

    // The language on Android 8.0 to 9

    @Test
    fun `on Android 8 and 9 with LocalType's keyboard the phone's language is checked`() {
        // Android 8.0 to 9, automatic, LocalType's en_US keyboard: Android hands "en" whatever the phone's language.
        for (sdk in 26..28) {
            assertSame(LanguageProfile.SPANISH, SpellCheckLanguage.choose("en", sdk, keyboardIsOurs = true, automatic = true, systemLocale = "es_ES"))
            assertSame(LanguageProfile.PORTUGUESE, SpellCheckLanguage.choose("en", sdk, keyboardIsOurs = true, automatic = true, systemLocale = "pt_BR"))
            assertSame(LanguageProfile.ENGLISH, SpellCheckLanguage.choose("en", sdk, keyboardIsOurs = true, automatic = true, systemLocale = "en_GB"))
            // A phone language the checker doesn't have gets no answer, never English, as on Android 10 and later.
            assertNull(SpellCheckLanguage.choose("en", sdk, keyboardIsOurs = true, automatic = true, systemLocale = "fr_FR"))
            // A language chosen for the spell checker, another keyboard, or a session in another language: as handed.
            assertSame(LanguageProfile.ENGLISH, SpellCheckLanguage.choose("en", sdk, keyboardIsOurs = true, automatic = false, systemLocale = "es_ES"))
            assertSame(LanguageProfile.ENGLISH, SpellCheckLanguage.choose("en", sdk, keyboardIsOurs = false, automatic = true, systemLocale = "es_ES"))
            assertSame(LanguageProfile.SPANISH, SpellCheckLanguage.choose("es", sdk, keyboardIsOurs = true, automatic = true, systemLocale = "pt_BR"))
            assertNull(SpellCheckLanguage.choose("fr", sdk, keyboardIsOurs = true, automatic = true, systemLocale = "es_ES"))
        }
        // Android 10 and later already follow the phone's language: the handed locale is used as it is.
        for (sdk in listOf(29, 30, 34, 36, 37)) {
            assertSame(LanguageProfile.ENGLISH, SpellCheckLanguage.choose("en", sdk, keyboardIsOurs = true, automatic = true, systemLocale = "es_ES"))
        }
        assertFalse(SpellCheckLanguage.keyboardDecides(25))
        assertFalse(SpellCheckLanguage.keyboardDecides(29))
    }

    @Test
    fun `the current keyboard is recognized by its package`() {
        assertTrue(SpellCheckLanguage.keyboardIsOurs("dev.localtype.keyboard/.LocalTypeInputMethodService", "dev.localtype.keyboard"))
        assertTrue(SpellCheckLanguage.keyboardIsOurs("dev.localtype.keyboard.fdroid/dev.localtype.keyboard.LocalTypeInputMethodService", "dev.localtype.keyboard.fdroid"))
        assertFalse(SpellCheckLanguage.keyboardIsOurs("dev.localtype.keyboard.fdroid/dev.localtype.keyboard.LocalTypeInputMethodService", "dev.localtype.keyboard"))
        assertFalse(SpellCheckLanguage.keyboardIsOurs("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME", "dev.localtype.keyboard"))
        assertFalse(SpellCheckLanguage.keyboardIsOurs(null, "dev.localtype.keyboard"))
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
