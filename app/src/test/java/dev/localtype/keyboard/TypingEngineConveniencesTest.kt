package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tidy spacing and small slips on the engine the iPhone keyboard calls: TypingEngine's conveniences functions around
 * SmallAccommodations, OwnedSpacing and ContextualContractions, whose own rules have their own tests. The typist
 * here calls the facade key by key, the way the Swift keyboard does.
 */
class TypingEngineConveniencesTest {
    private fun asset(name: String) = File("src/main/assets/$name")

    private fun open(language: LanguageProfile = LanguageProfile.ENGLISH, options: TypingEngine.Options = TypingEngine.Options.NONE) = TypingEngine(
        language = language,
        words = asset(language.wordsAsset).readText(),
        swears = asset(language.profanityAsset).readText(),
        lexicon = asset(language.lexiconAsset).readText(),
        modern = if (language.english) asset("words_en_modern.txt").readText() else null,
        prior = language.priorAsset?.let { asset(it).readBytes() },
        journal = null,
        suggestSwears = true,
        emoji = asset("emoji_en.txt").readText(),
        options = options,
    )

    /** The host's side: the text before the caret, a clock, and one facade call per key. */
    private class Typist(val engine: TypingEngine, var before: String = "") {
        var now = 1_000L
        var verified = true
        var hostProtected = false
        var allowed = true
        /** Every answer since the last [answers] read. */
        private val given = ArrayList<TypingEngine.Convenience>()

        /** Types [text] key by key, 100 ms apart, and returns what the last key answered. */
        fun type(text: String): TypingEngine.Convenience? {
            var last: TypingEngine.Convenience? = null
            for (char in text) {
                now += 100
                last = key(char.toString())
                last?.let { given += it }
            }
            return last
        }

        fun answers(): List<String> = given.map { it.kind }.also { given.clear() }

        private fun key(text: String): TypingEngine.Convenience? {
            val word = engine.activePrefix(before)
            val prior = before
            return when (text) {
                " " -> {
                    before += text
                    engine.conveniencesSpace(before, word, verified && word.isNotEmpty(), hostProtected, now, allowed)
                }
                ",", "!", "?", ":", ";", "." -> {
                    before += text
                    engine.conveniencesMark(before, word, text, verified && word.isNotEmpty(), hostProtected, now, allowed)
                }
                ")" -> {
                    val answer = engine.conveniencesClosing(prior, now, allowed)
                    before += text
                    answer
                }
                else -> {
                    before += text
                    engine.conveniencesAppended(prior, text, now, allowed)
                    null
                }
            }
        }

        /** Puts the fix in, as the host does after checking the text still ends with what it replaces. */
        fun apply(fix: TypingEngine.Convenience) {
            assertTrue("$before ends with ${fix.original}${fix.delimiter}", before.endsWith(fix.original + fix.delimiter))
            before = before.dropLast(fix.original.length + fix.delimiter.length) + fix.replacement + fix.delimiter
            engine.conveniencesApplied(fix, before, now)
        }
    }

    private fun typed(text: String, before: String = "", engine: TypingEngine = english, setup: Typist.() -> Unit = {}): TypingEngine.Convenience? {
        engine.conveniencesReset()
        return Typist(engine, before).apply(setup).type(text)
    }

    private fun assertFix(fix: TypingEngine.Convenience?, kind: String, original: String, replacement: String, delimiter: String, automatic: Boolean) {
        assertNotNull("$kind expected", fix)
        fix!!
        assertEquals(listOf(kind, original, replacement, delimiter, automatic), listOf(fix.kind, fix.original, fix.replacement, fix.delimiter, fix.automatic))
    }

    private val english by lazy { open() }

    @Test
    fun `a space before a comma or a closing mark is taken out, and a semicolon is left`() {
        assertFix(typed("hello ,"), "COMMA", "hello ", "hello", ",", true)
        assertFix(typed("hello !", before = "Well "), "PUNCTUATION", "hello ", "hello", "!", true)
        assertFix(typed("hello ?"), "PUNCTUATION", "hello ", "hello", "?", true)
        assertNull(typed("hello ;"))
        assertNull(typed("hello,"))
        // Two spaces are the person's own.
        assertNull(typed("hello  ,"))
    }

    @Test
    fun `parentheses lose the spaces typed inside them`() {
        assertFix(typed("(see below )"), "PARENTHESIS", "below ", "below", ")", true)
        assertNull(typed("(see below)"))
        // The opening one is tidied at the first word's space, and the closing one still is after that.
        val typist = Typist(english.also { it.conveniencesReset() })
        val opening = typist.type("( see ")
        assertFix(opening, "OPENING_PARENTHESIS", "( see", "(see", " ", true)
        typist.apply(opening!!)
        assertEquals("(see ", typist.before)
        assertFix(typist.type("below )"), "PARENTHESIS", "below ", "below", ")", true)
        // Short labels and anything that is not a word stay.
        assertNull(typed("( fig "))
        assertNull(typed("(see 2 )"))
    }

    @Test
    fun `a space before a colon or after a sentence end is fixed at the next word's space`() {
        assertFix(typed("note : bring "), "COLON_SEMICOLON", "note : bring", "note: bring", " ", true)
        assertNull(typed("note : Bring "))
        assertNull(typed("time : bring "))
        assertFix(typed("done!Then "), "SENTENCE_SPACE", "done!Then", "done! Then", " ", true)
        assertNull(typed("done!then "))
        assertNull(typed("yahoo!Then "))
    }

    @Test
    fun `word slips are offered and never applied`() {
        assertFix(typed("the the "), "REPEATED", "the the", "the", " ", false)
        assertFix(typed("The the "), "REPEATED", "The the", "The", " ", false)
        assertFix(typed("your not "), "YOUR", "your", "you're", " not ", false)
        assertFix(typed("Your not "), "YOUR", "Your", "You're", " not ", false)
        assertFix(typed("a apple ", before = "I ate "), "ARTICLE", "a", "an", " apple ", false)
        assertFix(typed("an book "), "ARTICLE", "an", "a", " book ", false)
        assertNull(typed("a apple ", before = "The letter "))
        assertFix(typed("Ok I ate a apple ", before = "I ate a apple \n"), "ARTICLE", "a", "an", " apple ", false)
        assertFix(typed("HEllo "), "CAPITALS", "HEllo", "Hello", " ", false)
        assertFix(typed("hello,friend "), "COMMA_SPACE", "hello,friend", "hello, friend", " ", false)
        assertFix(typed("of.the "), "DOT_SPACE", "of.the", "of the", " ", false)
        assertFix(typed("done.Then "), "DOT_SPACE", "done.Then", "done. Then", " ", false)
        assertNull(typed("example.com "))
        assertNull(typed("the cat "))
    }

    @Test
    fun `its is always offered, at a sentence start too`() {
        assertFix(typed("Its been "), "ITS", "Its", "It's", " been ", false)
        assertFix(typed("its been ", before = "I think "), "ITS", "its", "it's", " been ", false)
        assertFix(typed("its a "), "ITS", "its", "it's", " a ", false)
        assertFix(typed("its not ", before = "No, "), "ITS", "its", "it's", " not ", false)
        assertNull(typed("its own "))
    }

    @Test
    fun `nothing is answered for text that was not wholly typed here or is protected`() {
        // A word that was pasted, tapped in the strip or corrected.
        assertNull(typed("hello ,") { verified = false })
        assertNull(typed("your not ") { verified = false })
        assertNull(typed("(see below )") { allowed = false })
        // A saved-text shortcut or a word put back by Delete.
        assertNull(typed("hello ,") { hostProtected = true })
        // Quoted or identifier-like text before it.
        assertNull(typed("hello ,", before = "She said \"well "))
        assertNull(typed("the the ", before = "mail me @ "))
        assertNull(typed("(see below )", before = "x = "))
        // Ten seconds between the two keys.
        val slow = Typist(english.also { it.conveniencesReset() })
        slow.type("hello ")
        slow.now += 10_001
        assertNull(slow.type(","))
        // Anything else that edits the text in between.
        val edited = Typist(english.also { it.conveniencesReset() })
        edited.type("the ")
        english.conveniencesReset()
        assertNull(edited.type("the "))
        // The text the host reads starts somewhere else at the second key (iOS moved its window).
        val moved = Typist(english.also { it.conveniencesReset() }, "One two. ")
        moved.type("hello ")
        moved.before = "hello "
        assertNull(moved.type(","))
        val run = Typist(english.also { it.conveniencesReset() }, "One two. ")
        run.type("(see ")
        run.before = "(see "
        assertNull(run.type("below )"))
    }

    @Test
    fun `added, pack and blocked words are left alone`() {
        val added = open()
        added.syncAddedWords(listOf("hello"))
        assertNull(typed("hello ,", engine = added))
        assertNull(typed("hello,friend ", engine = added))
        added.syncAddedWords(listOf("its"))
        assertNull(typed("its been ", engine = added))

        val packs = open(options = TypingEngine.Options(packs = listOf(asset("pack_medical_en.txt").readText())))
        val drug = asset("pack_medical_en.txt").readLines().filterNot { it.startsWith("#") }.map { it.substringBefore('\t') }
            .first { word -> word.length >= 5 && word.all { it in 'a'..'z' } }
        assertNull(typed("$drug ,", engine = packs))
        assertFix(typed("hello ,", engine = packs), "COMMA", "hello ", "hello", ",", true)

        val blocked = open(options = TypingEngine.Options(blocked = listOf("you're", "it's")))
        assertNull(typed("your not ", engine = blocked))
        assertNull(typed("its been ", engine = blocked))
        assertFix(typed("the the ", engine = blocked), "REPEATED", "the the", "the", " ", false)
    }

    @Test
    fun `another language gets the spacing fixes and not the English word fixes`() {
        val spanish = open(LanguageProfile.SPANISH)
        assertFix(typed("( hola ", engine = spanish), "OPENING_PARENTHESIS", "( hola", "(hola", " ", true)
        assertFix(typed("hola,amigo ", engine = spanish), "COMMA_SPACE", "hola,amigo", "hola, amigo", " ", false)
        assertNull(typed("a a ", engine = spanish))
        assertNull(typed("hola ,", engine = spanish))
        assertFix(typed("a a "), "REPEATED", "a a", "a", " ", false)
    }

    @Test
    fun `only the last key of a fix answers, once`() {
        val typist = Typist(english.also { it.conveniencesReset() })
        typist.type("I saw the the cat and its been here , ok")
        assertEquals(listOf("REPEATED", "ITS", "COMMA"), typist.answers())
    }

    @Test
    fun `a mark right after an offer left alone is still tidied`() {
        for ((text, kind) in listOf("the the ," to "REPEATED", "your not !" to "YOUR", "its been ?" to "ITS")) {
            val typist = Typist(english.also { it.conveniencesReset() })
            val mark = text.takeLast(1)
            val word = text.dropLast(2).substringAfterLast(' ')
            val tidy = if (mark == ",") "COMMA" else "PUNCTUATION"
            assertFix(typist.type(text), tidy, "$word ", word, mark, true)
            assertEquals(text, listOf(kind, tidy), typist.answers())
        }
        // The same when the offer was dropped because its word is blocked.
        val blocked = open(options = TypingEngine.Options(blocked = listOf("you're", "it's")))
        assertFix(typed("your not ,", engine = blocked), "COMMA", "not ", "not", ",", true)
        assertFix(typed("its been ,", engine = blocked), "COMMA", "been ", "been", ",", true)
        // An offer that was applied ends everything, as before.
        val applied = Typist(english.also { it.conveniencesReset() })
        applied.apply(applied.type("the the ")!!)
        assertNull(applied.type(","))
    }

    @Test
    fun `the conveniences change no other answer`() {
        val plain = open()
        val used = open()
        val typist = Typist(used)
        val text = "I think its been a apple , teh"
        typist.type("I think its been a apple , teh")
        used.conveniencesReset()
        for (engine in listOf(plain, used)) "teh".forEach { engine.typed(it.toString(), 0.0, 0.0) }
        assertEquals(plain.suggestions(text), used.suggestions(text))
        assertEquals(plain.correction(text), used.correction(text))
        assertEquals(plain.suggestions("I think "), used.suggestions("I think "))
        assertEquals(plain.learn("I think its"), used.learn("I think its"))
        assertFalse(used.personalEnabled)
        assertEquals(plain.journal().toList(), used.journal().toList())
    }
}
