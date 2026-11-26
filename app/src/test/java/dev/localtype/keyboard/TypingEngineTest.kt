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

    private fun open(language: LanguageProfile, journal: ByteArray? = null, swears: Boolean = true, offsets: String? = null,
                     options: TypingEngine.Options = TypingEngine.Options.NONE) = TypingEngine(
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
        options = options,
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
    fun `its is offered it's in its own case and never changed on space`() {
        val engine = open(LanguageProfile.ENGLISH)
        assertNull(engine.correction("I think its"))
        assertEquals("it's", engine.offer("I think its", completionsAvailable = true))
        assertEquals("It's", engine.offer("Its", completionsAvailable = true))
        assertNull(engine.correction("Its"))
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
    fun `tap offsets are readable for aiming, and follow learning and forgetting`() {
        val engine = open(LanguageProfile.ENGLISH)
        fun tap(word: String) = word.forEach { engine.typed(it.toString(), 0.2, 0.1) }
        assertEquals(emptyList<String>(), engine.tapOffsets().map { it.letter })
        val fresh = engine.tapRevision

        tap("Hello")
        assertNull(engine.correction("Hello"))
        assertTrue(engine.learn("Hello"))
        val learned = engine.tapOffsets()
        assertEquals(listOf("e", "h", "l", "o"), learned.map { it.letter })
        assertEquals(listOf(1, 1, 2, 1), learned.map { it.count })
        for (offset in learned) {
            assertEquals(0.2f, offset.dx, 1e-4f)
            assertEquals(0.1f, offset.dy, 1e-4f)
        }
        val afterHello = engine.tapRevision
        assertTrue(afterHello != fresh)

        // A corrected word and a word with a letter that wasn't tapped change nothing a host would read.
        engine.typed(" ", Double.NaN, Double.NaN)
        tap("teh")
        assertEquals("the", engine.correction("Hello teh"))
        engine.learn("Hello the")
        engine.resetTrace()
        tap("c"); engine.typed("a", Double.NaN, Double.NaN); tap("t")
        assertNull(engine.correction("Hello the cat"))
        engine.learn("Hello the cat")
        assertEquals(afterHello, engine.tapRevision)
        assertEquals(listOf(1, 1, 2, 1), engine.tapOffsets().map { it.count })

        val reopened = open(LanguageProfile.ENGLISH, offsets = engine.offsets())
        assertEquals(learned.map { listOf(it.letter, it.count, it.dx, it.dy) }, reopened.tapOffsets().map { listOf(it.letter, it.count, it.dx, it.dy) })
        val beforeForget = reopened.tapRevision
        reopened.forget()
        assertEquals(emptyList<String>(), reopened.tapOffsets().map { it.letter })
        assertTrue(reopened.tapRevision != beforeForget)

        // A key outside a to z comes back as its own one-character string.
        val spanish = open(LanguageProfile.SPANISH)
        "niño".forEach { spanish.typed(it.toString(), -0.1, 0.3) }
        assertNull(spanish.correction("niño"))
        assertTrue(spanish.learn("niño"))
        val enye = spanish.tapOffsets().single { it.letter == "ñ" }
        assertEquals(1, enye.count)
        assertEquals(-0.1f, enye.dx, 1e-4f)
        assertEquals(0.3f, enye.dy, 1e-4f)
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
    fun `chime's compounds of swear words stay out of the strip when they are off`() {
        val chime = ChimePredictor.verified(asset(ChimePredictor.FILE_NAME).readBytes())
        val on = open(LanguageProfile.ENGLISH)
        val off = open(LanguageProfile.ENGLISH, swears = false)
        val typed = listOf("this is some shi", "I wrote it in brain")
        val shown = HashSet<String>()
        for (text in typed) {
            val neural = chime.predict(text)
            val compounds = neural.filter { it in NeuralRanking.SWEAR_COMPOUNDS }
            assertEquals("$text: $neural", 1, compounds.size)
            // Neither is in a word list, so only the ranking's blocked words can stop them.
            assertTrue(compounds.single() in on.suggestions(text, 3, neural).map { it.lowercase() })
            assertTrue(off.suggestions(text, 3, neural).none { it.lowercase() in NeuralRanking.SWEAR_COMPOUNDS })
            shown += compounds
        }
        assertEquals(NeuralRanking.SWEAR_COMPOUNDS, shown)
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

    @Test
    fun `no options leaves every answer as it was`() {
        val plain = open(LanguageProfile.ENGLISH)
        val empty = open(LanguageProfile.ENGLISH, options = TypingEngine.Options())
        for (text in listOf("", "I like the col", "i saw teh", "thank ", "We went to the hosp", "my favorite")) {
            assertEquals(text, plain.suggestions(text), empty.suggestions(text))
            assertEquals(text, plain.correction(text), empty.correction(text))
        }
    }

    @Test
    fun `the current vocabulary keeps the words and their order, and other languages never read it`() {
        val vocabulary = TypingEngine.Options(vocabulary = asset(CurrentVocabulary.ASSET).readText(), epochDay = 20_770)
        val plain = open(LanguageProfile.ENGLISH)
        val english = open(LanguageProfile.ENGLISH, options = vocabulary)
        // The same words in the same order; the vocabulary only gives a listed name its capital ("Tehran").
        for (text in listOf("", "I like the col", "i saw teh", "thank ", "my favorite", "Hello wor")) {
            assertEquals(text, plain.suggestions(text).map { it.lowercase() }, english.suggestions(text).map { it.lowercase() })
            assertEquals(text, plain.correction(text), english.correction(text))
        }
        assertTrue("Tehran" in english.suggestions("i saw teh"))
        for (language in listOf(LanguageProfile.SPANISH, LanguageProfile.PORTUGUESE)) {
            val before = open(language)
            val after = open(language, options = vocabulary)
            for (text in listOf("", "muchas ", "yo tamb", "Esp", "nao ")) assertEquals(text, before.suggestions(text), after.suggestions(text))
        }
    }

    @Test
    fun `british spelling changes what is shown, never an added word`() {
        val options = TypingEngine.Options(british = asset(BritishSpelling.ASSET).readText())
        val engine = open(LanguageProfile.ENGLISH, options = options)
        assertTrue("colour" in engine.suggestions("I like the colo"))
        assertFalse("color" in engine.suggestions("I like the colo"))
        assertEquals("favourite", engine.correction("my favorit"))
        // Each word shows once, however many American forms spell it.
        assertEquals(engine.suggestions("the col").size, engine.suggestions("the col").map { it.lowercase() }.distinct().size)
        // An added word keeps the spelling the person chose.
        assertTrue(engine.syncAddedWords(listOf("color")))
        assertTrue("color" in engine.suggestions("I like the colo"))
        // Spanish never reads the list.
        assertEquals(open(LanguageProfile.SPANISH).suggestions("muchas "), open(LanguageProfile.SPANISH, options = options).suggestions("muchas "))
    }

    @Test
    fun `a blocked word can be typed but is never suggested, a fix or learned`() {
        val engine = open(LanguageProfile.ENGLISH, options = TypingEngine.Options(blocked = listOf("Because", "elephant")))
        assertFalse("because" in engine.suggestions("I left becau").map { it.lowercase() })
        // The corrector may still find another word; it never lands on the blocked one.
        assertFalse(engine.correction("I left becuase").equals("because", ignoreCase = true))
        assertFalse(engine.offer("I left becuase", completionsAvailable = false).equals("because", ignoreCase = true))
        assertFalse(engine.learn("purple elephant"))
        engine.accept("purple ", "elephant")
        assertEquals(0, engine.learnedPairs)
        assertTrue(engine.blocks("Elephant"))
        assertFalse(engine.blocks("purple"))
    }

    @Test
    fun `a word pack completes its words from enough letters, knows them and never fixes to them by itself`() {
        val medical = asset("pack_medical_en.txt").readText()
        val plain = open(LanguageProfile.ENGLISH)
        val engine = open(LanguageProfile.ENGLISH, options = TypingEngine.Options(packs = listOf(medical), epochDay = 20_770))
        val drug = WordPack.strict(medical).let { pack -> pack.words.first { pack.entry(it)!!.kind.id == "drug" && it.length >= 8 && it.all(Char::isLetter) } }
        // Known: typed whole, it is left alone; without the pack the same letters may be "fixed".
        assertNull(engine.correction("she takes $drug"))
        // Completed from five letters of a drug name, with the pack's own capitals.
        val shown = engine.suggestions("she takes ${drug.take(6)}").map { it.lowercase() }
        assertTrue("$drug in $shown", drug in shown)
        assertFalse(drug in plain.suggestions("she takes ${drug.take(6)}").map { it.lowercase() })
        // Never an empty-prefix suggestion, and everyday typing is unchanged.
        assertEquals(plain.suggestions("thank "), engine.suggestions("thank "))
        assertEquals(plain.correction("i saw teh"), engine.correction("i saw teh"))
        // A pack for another language is left out; a damaged one doesn't stop the keyboard.
        assertEquals(open(LanguageProfile.SPANISH).suggestions("muchas "),
            open(LanguageProfile.SPANISH, options = TypingEngine.Options(packs = listOf(medical, "not a pack"))).suggestions("muchas "))
    }

    @Test
    fun `capitals a person types are learned, shown, saved and forgotten in lowercase`() {
        val engine = open(LanguageProfile.ENGLISH)
        assertTrue(engine.syncAddedWords(listOf("zorblax")))
        assertTrue(engine.learn("I use ZorbLax"))
        assertTrue("ZorbLax" in engine.suggestions("I use zorb"))
        // A common word's capital is emphasis, not a spelling; a sentence start explains a capital away.
        assertTrue(engine.learn("we Will"))
        assertTrue(engine.learn("Hello"))
        assertEquals(1, engine.learnedSpellingCount)
        // Saved and reopened.
        val reopened = open(LanguageProfile.ENGLISH, journal = engine.journal(), options = TypingEngine.Options(spellings = engine.spellings()))
        assertTrue("ZorbLax" in reopened.suggestions("I use zorb"))
        // Caps lock teaches nothing; lowercase mid-sentence forgets.
        assertTrue(reopened.learn("I use ZORBLAX", capsLock = true))
        assertTrue("ZorbLax" in reopened.suggestions("I use zorb"))
        // An added word typed in lowercase keeps its spelling; forgetting everything clears it.
        reopened.forget()
        assertEquals(0, reopened.learnedSpellingCount)
    }

    @Test
    fun `capitals typed on the keys for an added word show in the strip, beside chime too`() {
        val engine = open(LanguageProfile.ENGLISH, options = TypingEngine.Options(british = asset(BritishSpelling.ASSET).readText()))
        assertTrue(engine.syncAddedWords(listOf("zorblax")))
        "ZorbLax".forEach { engine.typed(it.toString(), 0.0, 0.0) }
        val text = "I left because I use ZorbLax"
        assertNull(engine.correction(text))
        assertTrue(engine.learn(text))
        engine.resetTrace()
        "zorb".forEach { engine.typed(it.toString(), 0.0, 0.0) }
        assertEquals(1, engine.learnedSpellingCount)
        assertTrue("ZorbLax" in engine.suggestions("$text and zorb"))
        assertTrue("ZorbLax" in engine.suggestions("$text and zorb", 3, listOf("the", "and", "zoo"), true))
        assertTrue("ZorbLax" in engine.suggestions("$text and zorb", 3, emptyList(), true))
    }

    @Test
    fun `a fix the keyboard made never changes saved capitals, and british spelling never brings a blocked word back`() {
        val engine = open(LanguageProfile.ENGLISH)
        assertTrue(engine.syncAddedWords(listOf("zorblax")))
        assertTrue(engine.learn("I got a ZorbLax"))
        assertEquals(1, engine.learnedSpellingCount)
        // The keyboard fixed a typo to "zorblax" in lowercase: that isn't the person spelling it in lowercase.
        assertTrue(engine.learn("my zorblax", corrected = true))
        assertTrue("ZorbLax" in engine.suggestions("my zorb"))

        val british = open(LanguageProfile.ENGLISH, options = TypingEngine.Options(british = asset(BritishSpelling.ASSET).readText(), blocked = listOf("colour")))
        assertFalse(british.suggestions("I like the colo").any { it.equals("colour", ignoreCase = true) })
    }

    @Test
    fun `a word added before capitals were kept gets them once, and typing can change them after`() {
        val old = open(LanguageProfile.ENGLISH)
        assertTrue(old.syncAddedWords(listOf("localtype")))
        // The journal has "localtype"; the app's list says "LocalType"; no spelling has been saved yet.
        val upgraded = open(LanguageProfile.ENGLISH, journal = old.journal())
        assertTrue(upgraded.syncAddedWords(listOf("LocalType")))
        assertTrue("LocalType" in upgraded.suggestions("I use localt"))
        assertFalse(upgraded.syncAddedWords(listOf("LocalType")))
        // Typed another way, it shows that way, and the next sync leaves it.
        assertTrue(upgraded.learn("I use LOCALtype"))
        assertFalse(upgraded.syncAddedWords(listOf("LocalType")))
        assertTrue("LOCALtype" in upgraded.suggestions("I use localt"))
    }

    @Test
    fun `an added word keeps the capitals typed into settings`() {
        val engine = open(LanguageProfile.ENGLISH)
        assertTrue(engine.syncAddedWords(listOf("LocalType")))
        assertTrue("LocalType" in engine.suggestions("I use localt"))
        assertTrue(engine.syncAddedWords(emptyList()))
        assertEquals(0, engine.learnedSpellingCount)
    }

    @Test
    fun `an uncertain fix is offered, and navajo never fixes by itself`() {
        val english = open(LanguageProfile.ENGLISH)
        // A missing possessive apostrophe is never applied, only offered.
        assertNull(english.correction("my friends"))
        val navajo = open(LanguageProfile.NAVAJO)
        assertFalse(navajo.autocorrects)
        val word = asset("words_nv.txt").readLines().map { it.substringBefore('\t') }.first { it.length >= 6 && it.all(Char::isLetter) }
        val typo = word.dropLast(1) + (if (word.last() == 'x') 'q' else 'x')
        assertNull(navajo.correction(typo))
    }

    @Test
    fun `every language with words opens, completes and keeps its marks in the trace`() {
        for (language in LanguageProfile.WITH_WORDS) {
            val engine = open(language)
            val word = asset(language.wordsAsset).readLines().map { it.substringBefore('\t') }
                .first { it.length >= 5 && !it.startsWith("#") && it.all(Char::isLetter) }
            val shown = engine.suggestions(word.dropLast(2))
            assertTrue("${language.code}: $word in $shown", shown.isNotEmpty())
            assertEquals(language.shiftless, engine.shiftless)
            // Every letter key and every hold joins the word's trace, marks and two-character holds included.
            for (row in language.keyRows) for (key in row) {
                engine.resetTrace()
                engine.typed(key.toString(), 0.0, 0.0)
                assertTrue("${language.code} $key", engine.typedHere(key.toString()))
                for (hold in engine.holds(key.toString())) {
                    engine.resetTrace()
                    engine.typed(hold, Double.NaN, Double.NaN)
                    assertTrue("${language.code} hold $hold", engine.typedHere(hold))
                }
            }
        }
    }
}
