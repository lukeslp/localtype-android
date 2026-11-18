package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Learn my phrases on the engine the iPhone keyboard calls (TypingEngine with Options.personalLearning): the facade
 * around PersonalLearningState, PersonalLearningCoordinator and NeuralRanking, whose own rules have their own tests.
 */
class TypingEnginePhrasesTest {
    private fun asset(name: String) = File("src/main/assets/$name")

    private fun open(language: LanguageProfile = LanguageProfile.ENGLISH, on: Boolean = true, swears: Boolean = true,
                     blocked: List<String> = emptyList(), options: TypingEngine.Options? = null) = TypingEngine(
        language = language,
        words = asset(language.wordsAsset).readText(),
        swears = asset(language.profanityAsset).readText(),
        lexicon = asset(language.lexiconAsset).readText(),
        modern = if (language.english) asset("words_en_modern.txt").readText() else null,
        prior = language.priorAsset?.let { asset(it).readBytes() },
        journal = null,
        suggestSwears = swears,
        emoji = asset("emoji_en.txt").readText(),
        options = options ?: TypingEngine.Options(blocked = blocked, personalLearning = on),
    )

    /** Types [words] one after another with a space between, as the host does: each is learned at its space. Returns the text. */
    private fun TypingEngine.say(words: String, before: String = ""): String {
        var text = before
        for (word in words.split(" ")) {
            text += word
            learn(text)
            text += " "
        }
        return text
    }

    /** Stand-ins for Chime's words: common words the ranking keeps in front. */
    private val neural = listOf("the", "and", "to", "of", "in")

    private fun TypingEngine.after(text: String, limit: Int = 3) = suggestions(text, limit, neural).map { it.lowercase() }

    private fun TypingEngine.rows() = PersonalLearningCodec.decode(personal(), language.oneLetterWords)

    @Test
    fun `off, every answer is as it was and nothing is kept`() {
        val plain = open(options = TypingEngine.Options.NONE)
        val off = open(on = false)
        val fresh = open(on = true)
        val texts = listOf("", "I like the col", "thank ", "my purple ", "my purple el", "see you ")
        for (text in texts) {
            assertEquals(text, plain.suggestions(text), fresh.suggestions(text))
            assertEquals(text, plain.suggestions(text, 3, neural), fresh.suggestions(text, 3, neural))
            assertEquals(text, null, fresh.phraseWord)
        }
        // The off engine gets every call the host makes with the switch on.
        off.say("my purple elephant")
        off.accept("my purple elephant ", "dinosaur", owned = true)
        off.repaired("my purple elephant dinosaur the")
        off.takeBack()
        off.resetContext()
        off.say("my purple")
        // What the engine answered before Learn my phrases existed, after "my purple elephant" typed, "dinosaur" tapped
        // and "my purple" typed: the lists the commit before it gave (without Chime, three with it, two with it).
        // They move only when the word lists, the prior or the ranking do.
        val before = listOf(
            "" to Triple(listOf("I", "Hey", "Just"), listOf("The", "And", "To"), listOf("The", "And")),
            "I like the col" to Triple(listOf("cold", "college", "colonel"), listOf("cold", "college", "colonel"), listOf("cold", "college")),
            "thank " to Triple(listOf("you", "me", "goodness"), listOf("the", "and", "to"), listOf("the", "and")),
            "my purple " to Triple(listOf("is", "and", "one"), listOf("the", "and", "to"), listOf("the", "and")),
            "my purple el" to Triple(listOf("elephant", "else", "elevator"), listOf("elephant", "else", "elevator"), listOf("elephant", "else")),
            "my purple elephant " to Triple(listOf("in", "is", "and"), listOf("the", "and", "to"), listOf("the", "and")),
            "see you " to Triple(listOf("so", "later", "have"), listOf("the", "and", "to"), listOf("the", "and")),
        )
        for ((text, lists) in before) {
            assertEquals(text, lists.first, off.suggestions(text))
            assertEquals(text, lists.second, off.suggestions(text, 3, neural))
            assertEquals(text, lists.third, off.suggestions(text, 2, neural))
            assertEquals(text, null, off.phraseWord)
        }
        // And the same as an engine that only ever got the calls there were before.
        plain.say("my purple elephant")
        plain.accept("my purple elephant ", "dinosaur")
        plain.say("my purple")
        for ((text, _) in before) {
            assertEquals(text, plain.suggestions(text), off.suggestions(text))
            assertEquals(text, plain.suggestions(text, 3, neural), off.suggestions(text, 3, neural))
        }
        assertTrue(plain.journal().contentEquals(off.journal()))
        assertFalse(off.personalEnabled)
        assertFalse(off.personalChanged)
        assertEquals(0, off.personalEntries)
        assertEquals(emptyList<PersonalLearningState.Entry>(), off.rows())
        assertFalse(off.restorePersonal(open().apply { say("my purple elephant") }.personal()))
        assertEquals(0, off.personalEntries)
        // The learned pairs are the same with the option on: only the phrases are new.
        val on = open().apply { say("my purple elephant") }
        assertTrue(on.journal().contentEquals(open(on = false).apply { say("my purple elephant") }.journal()))
    }

    @Test
    fun `a phrase typed once is offered after its two words, in the last slot`() {
        val on = open()
        val off = open(on = false)
        for (engine in listOf(on, off)) {
            engine.say("my purple elephant")
            engine.resetContext()
            engine.say("my purple")
        }
        assertEquals(listOf("the", "and", "to"), off.after("my purple "))
        assertEquals(listOf("the", "and", "elephant"), on.after("my purple "))
        // With a word slot given to something else, it is the second of two.
        assertEquals(listOf("the", "elephant"), on.after("my purple ", limit = 2))
        // Without Chime's words too, and cased as the strip cases words.
        assertEquals("elephant", on.suggestions("my purple ").last())
        // Once its letters are typed it is a completion like any other.
        assertTrue("elephant" in on.after("my purple el"))
        // Three words back as well.
        val three = open()
        three.say("feed my purple elephant")
        three.resetContext()
        assertEquals("elephant", three.after(three.say("feed my purple")).last())
    }

    @Test
    fun `one use behind a single word is not enough, two are`() {
        val on = open()
        on.say("purple elephant")
        on.resetContext()
        assertFalse("elephant" in on.after(on.say("purple")))
        on.learn("purple elephant")
        on.resetContext()
        assertEquals("elephant", on.after(on.say("purple")).last())
    }

    @Test
    fun `a tapped word is offered after one use, unless what stood before the tap was not typed here`() {
        val on = open()
        on.accept(on.say("purple"), "elephant", owned = true)
        on.resetContext()
        assertEquals("elephant", on.after(on.say("purple")).last())
        // The tapped word is an own word: what is typed after it follows two own words.
        val chain = open()
        chain.accept(chain.say("purple"), "Elephant", owned = true)
        chain.learn("purple Elephant dinosaur")
        chain.resetContext()
        assertEquals("dinosaur", chain.after(chain.say("purple elephant")).last())

        val foreign = open()
        foreign.accept(foreign.say("purple"), "elephant", owned = false)
        assertTrue(foreign.rows().none { it.word == "elephant" })
        foreign.learn("purple elephant dinosaur")
        foreign.resetContext()
        assertFalse("elephant" in foreign.after(foreign.say("purple")))
        assertFalse("dinosaur" in foreign.after(foreign.say("purple elephant")))
    }

    @Test
    fun `text the keyboard did not type never becomes context`() {
        // Words already in the field are not own words.
        val pasted = open()
        pasted.learn("feed my purple")
        pasted.learn("feed my purple elephant")
        assertTrue(pasted.rows().all { it.context == listOf("purple") })
        pasted.resetContext()
        assertFalse("elephant" in pasted.after(pasted.say("my purple")))

        // A comma, a period, ! or ? between two words ends the phrase.
        for (mark in listOf(",", ".", "!", "?")) {
            val engine = open()
            engine.say("my purple")
            engine.learn("my purple$mark elephant")
            assertTrue(mark, engine.rows().none { it.word == "elephant" })
        }

        // So does a new line, even when the host forgot to say so, and a word that is not a word.
        val line = open()
        line.say("my purple")
        line.learn("my purple\nelephant")
        assertTrue(line.rows().none { it.word == "elephant" })
        val typo = open()
        typo.say("my purple")
        assertFalse(typo.learn("my purple elefnt"))
        typo.learn("my purple elefnt dinosaur")
        assertTrue(typo.rows().none { it.word == "dinosaur" })

        // The host's reset ends it.
        val reset = open()
        reset.say("my purple")
        reset.resetContext()
        reset.learn("my purple elephant")
        assertTrue(reset.rows().none { it.word == "elephant" })

        // A strip asked with other text shows the plain strip and gives nothing up.
        val on = open()
        on.say("my purple elephant")
        on.resetContext()
        val text = on.say("my purple")
        assertEquals(listOf("the", "and", "to"), on.after("something else "))
        assertEquals(listOf("the", "and", "to"), on.after("my purple\n"))
        assertEquals(listOf("the", "and", "to"), on.after(""))
        assertEquals("elephant", on.after(text).last())
        on.learn(text + "elephant")
        assertEquals(2, on.rows().first { it.context == listOf("my", "purple") }.observations)
    }

    @Test
    fun `a fix is learned at once and Delete takes it back`() {
        val on = open()
        on.say("my purple")
        on.resetContext()
        on.say("my")
        // "purpel" was fixed to "purple": the text now ends with the fix.
        on.repaired("my purple")
        fun count() = on.rows().first { it.context == listOf("my") && it.word == "purple" }.observations
        assertEquals(2, count())
        // The fix is an own word for what follows it.
        on.learn("my purple", corrected = true)
        assertEquals(2, count())
        on.learn("my purple elephant")
        assertTrue(on.rows().any { it.context == listOf("my", "purple") && it.word == "elephant" })

        val undone = open()
        undone.say("my purple")
        undone.resetContext()
        undone.say("my")
        undone.repaired("my purple")
        undone.takeBack()
        val row = undone.rows().single { it.context == listOf("my") }
        assertEquals("purple", row.word)
        assertEquals(1, row.observations)
        // Taking back twice, or with nothing to take back, changes nothing; the phrase ended.
        undone.takeBack()
        assertEquals(1, undone.rows().size)
        undone.learn("my purpel elephant")
        assertEquals(1, undone.rows().size)
    }

    @Test
    fun `blocked and swear words are never kept or shown`() {
        val blocked = open(blocked = listOf("elephant"))
        blocked.say("my purple elephant dinosaur")
        blocked.accept("my purple ", "elephant", owned = true)
        assertTrue(blocked.rows().none { it.word == "elephant" || "elephant" in it.context })

        // A word blocked later leaves with the snapshot's rows that hold it.
        val before = open().apply { say("my purple elephant dinosaur") }
        assertTrue(before.rows().any { "elephant" in it.context })
        val later = open(blocked = listOf("elephant"))
        assertTrue(later.restorePersonal(before.personal()))
        assertTrue(later.rows().isNotEmpty())
        assertTrue(later.rows().none { it.word == "elephant" || "elephant" in it.context })

        val swear = asset(LanguageProfile.ENGLISH.profanityAsset).readLines().first { it.isNotBlank() && !it.startsWith("#") }.substringBefore('\t')
        val loud = open()
        loud.say("my purple $swear")
        loud.resetContext()
        assertEquals(swear, loud.after(loud.say("my purple")).last())
        val quiet = open(swears = false)
        assertTrue(quiet.restorePersonal(loud.personal()))
        assertFalse(swear in quiet.after(quiet.say("my purple")))
    }

    @Test
    fun `phrases survive the snapshot, a damaged one is ignored, forget clears them`() {
        val on = open()
        on.say("my purple elephant")
        val bytes = on.personal()
        val reopened = open()
        assertTrue(reopened.restorePersonal(bytes))
        assertEquals(on.rows().toSet(), reopened.rows().toSet())
        assertEquals("elephant", reopened.after(reopened.say("my purple")).last())
        // What was typed before a snapshot was read is not the phrase before the caret any more.
        val late = open()
        val text = late.say("my purple")
        assertTrue(late.restorePersonal(bytes))
        assertFalse("elephant" in late.after(text))

        val damaged = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val refused = open()
        assertFalse(refused.restorePersonal(damaged))
        assertFalse(refused.restorePersonal(ByteArray(0)))
        assertEquals(0, refused.personalEntries)

        // A snapshot from a store that holds more contexts (Android's 1,024) keeps its newest 512 here.
        fun word(i: Int) = "q" + i.toString(26).map { 'a' + it.digitToInt(26) }.joinToString("") + "x"
        val many = PersonalLearningCodec.encode((1..600).map { PersonalLearningState.Entry(listOf(word(it)), word(it + 1000), 2, 0, it.toLong()) })
        val capped = open()
        assertTrue(capped.restorePersonal(many))
        assertEquals(512, capped.personalEntries)
        assertTrue(capped.rows().none { it.lastObserved <= 88 })

        on.forget()
        assertEquals(0, on.personalEntries)
        assertFalse("elephant" in on.after(on.say("my purple")))

        // Another language keeps its own one-letter words.
        val spanish = open(LanguageProfile.SPANISH)
        spanish.say("casa grande y")
        assertTrue(spanish.rows().any { it.word == "y" })
        val again = open(LanguageProfile.SPANISH)
        assertTrue(again.restorePersonal(spanish.personal()))
        assertEquals(spanish.rows().toSet(), again.rows().toSet())
        // With no Chime for the language, the phrase's word still takes the last slot.
        again.say("mi casa perro")
        again.resetContext()
        assertEquals("perro", again.suggestions(again.say("mi casa")).last())
    }

    @Test
    fun `removing an added word drops the phrases that hold it`() {
        val on = open()
        assertTrue(on.syncAddedWords(listOf("zorblax")))
        on.say("my purple zorblax dinosaur")
        assertTrue(on.rows().any { it.word == "zorblax" })
        assertTrue(on.rows().any { "zorblax" in it.context })
        assertTrue(on.syncAddedWords(emptyList()))
        assertTrue(on.rows().isNotEmpty())
        assertTrue(on.rows().none { it.word == "zorblax" || "zorblax" in it.context })
    }

    @Test
    fun `beside chime a common word typed after one word needs chime to list it, a phrase does not`() {
        val on = open()
        repeat(2) { on.say("purple one"); on.resetContext() }
        // "one" typed twice after "purple": a common word behind a single word, and these neural words don't hold it.
        assertFalse("one" in on.after(on.say("purple")))
        assertEquals("one", on.suggestions("purple ", 3, listOf("the", "and", "one", "to")).last().lowercase())
        on.resetContext()
        // Behind two own words it needs no backing.
        on.say("my purple one")
        on.resetContext()
        assertEquals(listOf("the", "and", "one"), on.after(on.say("my purple")))

        // With Chime's real words: its first two stay, and the phrase's word takes the last slot. The word is one
        // Chime cannot give, an added word.
        val chime = ChimePredictor.verified(asset(ChimePredictor.FILE_NAME).readBytes())
        val real = open()
        val plain = open(on = false)
        for (engine in listOf(real, plain)) assertTrue(engine.syncAddedWords(listOf("zorblax")))
        real.say("see you zorblax")
        real.resetContext()
        val text = real.say("see you")
        val words = chime.predict(text)
        val without = plain.suggestions(text, 3, words)
        val shown = real.suggestions(text, 3, words)
        assertEquals(3, without.size)
        assertFalse("zorblax" in without)
        assertEquals(without.take(2), shown.take(2))
        assertEquals("zorblax", shown.last())
        assertEquals("zorblax", real.phraseWord)
    }

    @Test
    fun `the phrase's word is named, and a learned pair's word is not`() {
        val on = open()
        on.say("my purple elephant")
        on.resetContext()
        val text = on.say("my purple")
        assertEquals(listOf("the", "and", "elephant"), on.after(text))
        assertEquals("elephant", on.phraseWord)
        // Asked as two, it is the second: what the host shows beside an emoji.
        assertEquals(listOf("the", "elephant"), on.after(text, limit = 2))
        assertEquals("elephant", on.phraseWord)
        // Cased as the answer cased it.
        val start = open()
        start.accept("", "Elephant", owned = true)
        start.accept("Elephant ", "dinosaur", owned = true)
        start.resetContext()
        start.accept("", "Elephant", owned = true)
        assertEquals("dinosaur", start.suggestions("Elephant ", 3, neural).last())
        assertEquals("dinosaur", start.phraseWord)
        // Other text, or no Chime and no phrase: none.
        on.after("something else ")
        assertEquals(null, on.phraseWord)
        on.suggestions("thank ")
        assertEquals(null, on.phraseWord)

        // "elephant" typed twice after a "purple" that was already in the field: a learned pair and no phrase. The
        // pair's word holds the last slot of three or of two, and is not the phrase's, so the host leaves the first
        // two slots as they are when an emoji takes the third.
        val pair = open()
        repeat(2) { pair.learn("purple elephant") }
        assertEquals(0, pair.personalEntries)
        pair.resetContext()
        val purple = pair.say("purple")
        assertEquals(listOf("the", "and", "elephant"), pair.after(purple))
        assertEquals(null, pair.phraseWord)
        assertEquals(listOf("the", "elephant"), pair.after(purple, limit = 2))
        assertEquals(null, pair.phraseWord)
        // The same word as a phrase too, with fewer uses than the pair has: it is the phrase's word.
        pair.say("my purple elephant")
        pair.resetContext()
        val both = pair.say("my purple")
        assertEquals(listOf("the", "and", "elephant"), pair.after(both))
        assertEquals("elephant", pair.phraseWord)
    }

    @Test
    fun `the snapshot is the codec's, byte for byte`() {
        fun same(engine: TypingEngine) {
            val bytes = engine.personal()
            val rows = PersonalLearningCodec.decode(bytes, engine.language.oneLetterWords)
            assertEquals(engine.personalEntries, rows.size)
            assertTrue(bytes.contentEquals(PersonalLearningCodec.encode(rows, engine.language.oneLetterWords)))
        }
        // None, with the switch off and on.
        same(open(on = false))
        assertTrue(open(on = false).personal().contentEquals(PersonalLearningCodec.encode(emptyList())))
        same(open())
        // Typed, tapped, fixed and taken back.
        val typed = open()
        typed.say("feed my purple elephant")
        typed.accept("feed my purple elephant ", "dinosaur", owned = true)
        typed.repaired("feed my purple elephant dinosaur today")
        typed.takeBack()
        assertTrue(typed.personalEntries > 0)
        same(typed)
        // Spanish, with its one-letter word.
        val spanish = open(LanguageProfile.SPANISH)
        spanish.say("casa grande y")
        assertTrue(spanish.rows().any { it.word == "y" })
        same(spanish)
        // The most the iPhone holds: 512 contexts of eight words each, and a word outside ASCII.
        fun word(i: Int) = "q" + i.toString(26).map { 'a' + it.digitToInt(26) }.joinToString("") + "x"
        val full = open()
        val rows = (1..512).flatMap { c -> (1..8).map { f ->
            PersonalLearningState.Entry(listOf(word(c), if (c % 2 == 0) "café" else word(c + 600)), word(2000 + f), 1 + f % 3, f % 2, (c * 8 + f).toLong())
        } }
        assertTrue(full.restorePersonal(PersonalLearningCodec.encode(rows)))
        assertEquals(4096, full.personalEntries)
        same(full)
        // Past it, the oldest contexts are gone and what is left is still the codec's.
        val over = open()
        assertTrue(over.restorePersonal(PersonalLearningCodec.encode(rows + (1..100).map {
            PersonalLearningState.Entry(listOf(word(5000 + it)), word(6000 + it), 2, 0, (10_000 + it).toLong())
        })))
        assertEquals(4096 - 100 * 8 + 100, over.personalEntries)
        same(over)
    }

    @Test
    fun `the phrases are changed only by a phrase`() {
        val on = open()
        assertFalse(on.personalChanged)
        // A word with no own word before it is a pair at most, never a phrase.
        on.learn("my")
        on.accept("my ", "purple")
        assertFalse(on.personalChanged)
        on.resetContext()
        on.say("my purple")
        assertTrue(on.personalChanged)
        on.personalSaved()
        assertFalse(on.personalChanged)
        // A lookup, a reset, a word that is not one and a tap behind text not typed here change nothing.
        on.after("my purple ")
        on.resetContext()
        on.learn("my purple elefnt")
        on.accept("pasted ", "word", owned = false)
        assertFalse(on.personalChanged)
        // A fix and its Delete each do.
        on.say("my")
        on.repaired("my purple")
        assertTrue(on.personalChanged)
        on.personalSaved()
        on.takeBack()
        assertTrue(on.personalChanged)
        on.personalSaved()
        on.takeBack()
        assertFalse(on.personalChanged)

        // Reading a file is not a change, unless rows with a word blocked since were left out.
        val bytes = open().apply { say("my purple elephant dinosaur") }.personal()
        val reopened = open()
        assertTrue(reopened.restorePersonal(bytes))
        assertFalse(reopened.personalChanged)
        val blocked = open(blocked = listOf("elephant"))
        assertTrue(blocked.restorePersonal(bytes))
        assertTrue(blocked.personalChanged)
        // Forget and a removed added word are.
        reopened.forget()
        assertTrue(reopened.personalChanged)
        val added = open()
        assertTrue(added.syncAddedWords(listOf("zorblax")))
        assertFalse(added.personalChanged)
        added.say("my zorblax")
        added.personalSaved()
        assertTrue(added.syncAddedWords(emptyList()))
        assertTrue(added.personalChanged)
    }
}
