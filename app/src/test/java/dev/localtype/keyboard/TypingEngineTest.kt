package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The engine the iPhone keyboard calls (TypingEngine), on the bundled assets. */
class TypingEngineTest {
    private fun asset(name: String) = File("src/main/assets/$name")

    private fun open(language: LanguageProfile, journal: ByteArray? = null, swears: Boolean = true, offsets: String? = null) = TypingEngine(
        language = language,
        words = asset(language.wordsAsset).readText(),
        swears = asset(language.profanityAsset).readText(),
        lexicon = asset(language.lexiconAsset).readText(),
        modern = if (language.english) asset("words_en_modern.txt").readText() else null,
        prior = language.priorAsset?.let { asset(it).readBytes() },
        journal = journal,
        suggestSwears = swears,
        emoji = asset("emoji_en.txt").readText(),
        offsets = offsets,
    )

    @Test
    fun `a missed space is offered as two words, never for a real word`() {
        val engine = open(LanguageProfile.ENGLISH)
        assertEquals("of the", engine.split("one ofbthe"))
        assertEquals("Of the", engine.split("Ofbthe"))
        assertNull(engine.split("the"))
        assertNull(engine.split("iPhone"))
    }

    @Test
    fun `added words are offered, kept from correction, synced to a list and saved`() {
        val engine = open(LanguageProfile.ENGLISH)
        assertTrue(engine.syncAddedWords(listOf("Zorblax", "not a word", "zorblax")))
        assertEquals(listOf("zorblax"), engine.addedWords)
        assertTrue("zorblax" in engine.suggestions("I met zorb").map { it.lowercase() })
        assertNull(engine.correction("I met zorblax"))
        assertFalse(engine.syncAddedWords(listOf("zorblax")))

        val reopened = open(LanguageProfile.ENGLISH, journal = engine.journal())
        assertEquals(listOf("zorblax"), reopened.addedWords)
        assertTrue(reopened.syncAddedWords(emptyList()))
        assertEquals(emptyList<String>(), reopened.addedWords)
        assertFalse("zorblax" in reopened.suggestions("I met zorb").map { it.lowercase() })
    }

    @Test
    fun `tap offsets learn only from whole tapped words left as typed, and save, reopen and forget`() {
        val engine = open(LanguageProfile.ENGLISH)
        fun tap(word: String) = word.forEach { engine.typed(it.toString(), 0.2, 0.1) }
        tap("Hello")
        assertNull(engine.correction("Hello"))
        assertTrue(engine.learn("Hello"))
        engine.typed(" ", Double.NaN, Double.NaN)
        assertEquals(5, engine.tapSamples)

        // A corrected word teaches nothing, and neither does one with a letter that wasn't tapped.
        tap("teh")
        assertEquals("the", engine.correction("Hello I saw teh"))
        engine.learn("Hello I saw the")
        engine.resetTrace()
        tap("c"); engine.typed("a", Double.NaN, Double.NaN); tap("t")
        assertNull(engine.correction("Hello the cat"))
        engine.learn("Hello the cat")
        assertEquals(5, engine.tapSamples)

        // Delete takes the last letter back out of the trace; a caret that moved elsewhere ends it.
        engine.resetTrace()
        tap("worlx"); engine.deleted(); tap("d")
        assertNull(engine.correction("Hello world"))
        assertTrue(engine.learn("Hello world"))
        assertEquals(10, engine.tapSamples)
        tap("ab")
        engine.keepTrace("Hello world s")
        tap("o")
        assertNull(engine.correction("Hello world so"))
        assertTrue(engine.learn("Hello world so"))
        assertEquals(10, engine.tapSamples)

        val reopened = open(LanguageProfile.ENGLISH, offsets = engine.offsets())
        assertEquals(10, reopened.tapSamples)
        assertEquals(engine.offsets(), reopened.offsets())
        reopened.forget()
        assertEquals(0, reopened.tapSamples)
        assertEquals("", reopened.offsets())
    }

    @Test
    fun `english corrects, suggests and cases as the keyboard does`() {
        val engine = open(LanguageProfile.ENGLISH)
        assertEquals("the", engine.correction("I saw teh"))
        assertEquals("Because", engine.correction("Becuase"))
        assertEquals("don't", engine.correction("I dont"))
        assertEquals("I", engine.correction("so i"))
        assertNull(engine.correction("so i", capitalizeI = false))
        assertNull(engine.correction("hello"))
        assertNull(engine.correction("hello "))
        assertEquals("you", engine.suggestions("thank ").first())
        assertTrue(engine.suggestions("").all { it.first().isUpperCase() })
        assertTrue(engine.suggestions("Hello. ").all { it.first().isUpperCase() })
        assertEquals("Wor", engine.activePrefix("Hello Wor"))
        assertTrue(engine.isSentenceStart("Done. "))
        assertEquals(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), engine.keyRows)
    }

    @Test
    fun `spanish uses its own lists, prior, accents and opening marks`() {
        val engine = open(LanguageProfile.SPANISH)
        assertEquals("también", engine.correction("yo tambien"))
        assertEquals("Mañana", engine.correction("Manana"))
        assertNull(engine.correction("dont"))
        assertEquals("veces", engine.suggestions("muchas ").first())  // the Spanish prior
        assertTrue(engine.isSentenceStart("Hola. ¿"))
        assertEquals("é", engine.holds("e").first())
        assertEquals(listOf("ú", "ü", "ù"), engine.holds("u").take(3))
        assertEquals(KeyboardLayout.ACCENTS.getValue('u'), open(LanguageProfile.ENGLISH).holds("u"))
        assertEquals("Español", engine.nativeName)
        assertEquals("asdfghjklñ", engine.keyRows[1])
    }

    @Test
    fun `learning survives the journal and forget clears it`() {
        val engine = open(LanguageProfile.ENGLISH)
        assertFalse("elephant" in engine.suggestions("purple "))
        // A learned pair adds about 46 points a use; the prior gives "is" after "purple" about 200 more than "elephant" has.
        repeat(8) { assertTrue(engine.learn("purple elephant")) }
        assertTrue("elephant" in engine.suggestions("purple "))
        assertFalse(engine.learn("purple zzkq"))  // not a word the keyboard knows
        val reopened = open(LanguageProfile.ENGLISH, engine.journal())
        assertEquals(engine.learnedPairs, reopened.learnedPairs)
        assertTrue("elephant" in reopened.suggestions("purple "))
        reopened.forget()
        assertFalse("elephant" in reopened.suggestions("purple "))
        val before = engine.learnedPairs
        engine.accept("purple ", "giraffe")
        assertEquals(before + 1, engine.learnedPairs)
    }

    @Test
    fun `swear words stay out of the strip when they are off`() {
        val off = open(LanguageProfile.ENGLISH, swears = false)
        val swears = asset("words_en_profanity.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        for (context in listOf("", "what the ", "oh ", "you ")) assertFalse(off.suggestions(context, 6).any { it.lowercase() in swears })
        assertNull(off.correction("shit"))
    }

    @Test
    fun `chime leads the strip and a word typed twice keeps a slot`() {
        assertEquals(NeuralAsset.SHA256, ChimePredictor.SHA256)
        val chime = ChimePredictor.verified(asset(ChimePredictor.FILE_NAME).readBytes())
        val engine = open(LanguageProfile.ENGLISH)
        val neural = chime.predict("I would like to ")
        assertEquals(ChimePredictor.WORDS, neural.size)
        val shown = engine.suggestions("I would like to ", 3, neural)
        assertEquals(neural.first(), shown.first().lowercase())
        // Completions follow the typed letters and its case.
        val typed = "Thank y"
        assertTrue(engine.suggestions(typed, 3, chime.predict(typed)).all { it.startsWith("y") })
        assertTrue(engine.suggestions("", 3, chime.predict("")).all { it.first().isUpperCase() })
        // Without Chime's words the strip is the n-gram strip.
        assertEquals(engine.suggestions("thank "), engine.suggestions("thank ", 3, null))
        repeat(2) { engine.learn("purple elephant") }
        assertTrue("elephant" in engine.suggestions("purple ", 3, chime.predict("purple ")))
    }

    @Test
    fun `chime refuses a file that is not the bundled export`() {
        val bytes = asset(ChimePredictor.FILE_NAME).readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        assertTrue(runCatching { ChimePredictor.verified(bytes) }.isFailure)
    }

    @Test
    fun `emoji follow a finished word or its correction, in English only`() {
        val engine = open(LanguageProfile.ENGLISH)
        val coffee = engine.emoji("I need coffee ")!!
        assertEquals("☕", coffee.emoji)
        assertEquals("coffee".length, coffee.replaceLength)
        assertEquals(" ", coffee.trailing)
        assertEquals("☕", engine.emoji("I need cofee", lookupAs = "coffee")?.emoji)
        assertNull(engine.emoji("coffee is "))
        assertNull(open(LanguageProfile.SPANISH).emoji("café "))
    }
}
