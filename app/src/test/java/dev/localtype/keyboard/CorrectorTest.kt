package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CorrectorTest {
    private val dictionary = File("src/main/assets/words_en.txt").bufferedReader().useLines { WordDictionary.parse(it) }
    private val corrector = Corrector({ dictionary })

    @Test
    fun `fixes common thumb slips`() {
        assertEquals("the", corrector.correct("teh"))
        assertEquals("don't", corrector.correct("dont"))
        assertEquals("i'm", corrector.correct("im"))
        assertEquals("you're", corrector.correct("youre"))
        assertEquals("hello", corrector.correct("hwllo"))
        assertEquals("tomorrow", corrector.correct("tomorow"))
        assertEquals("the", corrector.correct("thhe"))
    }

    @Test
    fun `fixes two-edit misspellings in longer words`() {
        assertEquals("definitely", corrector.suggest("definately"))
        assertEquals("necessary", corrector.suggest("neccesary"))
        assertEquals("separate", corrector.suggest("seperate"))
        assertEquals("because", corrector.correct("becuase"))
        assertEquals("receive", corrector.correct("recieve"))
    }

    @Test
    fun `two-edit fixes apply on space in the app's word lists`() {
        val app = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })
        assertEquals("necessary", app.correct("neccesary"))
        assertEquals("catching", app.correct("cacthig"))
        // A known modern word is left alone rather than stretched to a near dictionary word.
        assertNull(app.correct("clipboard"))
    }

    @Test
    fun `capitalized unknown words are not stretched to two edits`() {
        assertNull(corrector.correct("Steuber"))
        assertNull(corrector.suggest("Neccesary"))
    }

    @Test
    fun `swear words are never corrected into other words`() {
        val protectingProfanity = Corrector({ dictionary }, isKnown = { it == "fuck" })
        assertNull(protectingProfanity.correct("fuck"))
    }

    @Test
    fun `blocked words are never offered as fixes`() {
        // Switched off, swear words leave the dictionary but stay in the lexicon, so both searches must skip them.
        val swearList = File("src/main/assets/words_en_profanity.txt").readLines()
        val swears = swearList.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        val withSwears = WordDictionary.parse((File("src/main/assets/words_en.txt").readLines() + swearList).asSequence())
        assertEquals("fucking", Corrector({ withSwears }).suggest("fuckimg"))
        assertEquals("fucking", Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon }).suggest("fuckimg"))

        assertNotEquals("fucking", Corrector({ withSwears }, blocked = { it in swears }).suggest("fuckimg"))
        val off = Corrector({ TypoCorpus.dictionary }, isKnown = { it in swears }, lexicon = { TypoCorpus.lexicon }, blocked = { it in swears })
        assertNotEquals("fucking", off.suggest("fuckimg"))
        // Typed in full, it is still left alone.
        assertNull(off.correct("fucking"))
    }

    @Test
    fun `keeps real words acronyms and mixed case alone`() {
        assertNull(corrector.correct("the"))
        assertNull(corrector.correct("NASA"))
        assertNull(corrector.correct("iPhone"))
        assertNull(corrector.correct("od"))
    }

    @Test
    fun `words the person has taught are never corrected`() {
        val teaching = Corrector({ dictionary }, isKnown = { it == "zyxt" })

        assertNull(teaching.correct("zyxt"))
    }

    @Test
    fun `leaves gibberish without a confident match`() {
        assertNull(corrector.correct("qzvxkw"))
    }

    @Test
    fun `neighbor keys follow the qwerty stagger`() {
        assertTrue(Corrector.areNeighbors('e', 'w'))
        assertTrue(Corrector.areNeighbors('e', 'd'))
        assertTrue(Corrector.areNeighbors('h', 'b'))
        assertFalse(Corrector.areNeighbors('e', 't'))
        assertFalse(Corrector.areNeighbors('q', 'p'))
        assertFalse(Corrector.areNeighbors('a', 'm'))
    }

    private val app = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })

    @Test
    fun `common missing apostrophes survive historical learning but respect deliberate kept forms`() {
        val forms = mapOf("dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
            "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
            "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't", "couldnt" to "couldn't",
            "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "im" to "i'm", "ive" to "i've",
            "youre" to "you're", "theyre" to "they're", "youve" to "you've", "weve" to "we've",
            "theyve" to "they've", "youll" to "you'll", "theyll" to "they'll", "thats" to "that's",
            "theres" to "there's", "heres" to "here's")
        val learned = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon }, isKnown = { it in forms })
        val kept = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon }, isKept = { it in forms })
        forms.forEach { (typed, fixed) ->
            assertEquals(typed, fixed, app.correct(typed, previous = "we"))
            assertEquals("historically learned $typed", fixed, learned.correct(typed, previous = "we"))
            assertNull("deliberately kept $typed", kept.correct(typed, previous = "we"))
        }
    }

    @Test
    fun `unambiguous missing apostrophes correct despite known words`() {
        val forms = mapOf("cant" to "can't", "wont" to "won't", "whats" to "what's", "hes" to "he's", "shes" to "she's")
        forms.forEach { (typed, fix) ->
            assertTrue(typed, typed in TypoCorpus.lexicon)
            assertEquals(typed, fix, app.correct(typed, previous = "he"))
            assertEquals(typed, fix, app.suggest(typed, previous = "he"))
        }
    }

    @Test
    fun `ambiguous missing apostrophes correct at sentence starts and after commas`() {
        mapOf("ill" to "i'll", "id" to "i'd", "lets" to "let's").forEach { (typed, fix) ->
            assertTrue(typed, typed in TypoCorpus.lexicon)
            assertEquals(typed, fix, app.correct(typed, sentenceStart = true))
            assertEquals(typed, fix, app.correct(typed, afterComma = true))
        }
    }

    @Test
    fun `ambiguous missing apostrophes correct after the listed conjunctions`() {
        for (previous in listOf("and", "but", "so", "then")) {
            mapOf("ill" to "i'll", "id" to "i'd", "lets" to "let's").forEach { (typed, fix) ->
                assertEquals("$previous $typed", fix, app.correct(typed, previous = previous))
            }
        }
        assertEquals("i'll", app.correct("ill", previous = "AND"))
    }

    @Test
    fun `ambiguous missing apostrophes are only offered elsewhere`() {
        mapOf("ill" to "i'll", "id" to "i'd", "lets" to "let's").forEach { (typed, fix) ->
            assertNull(typed, app.correct(typed))
            assertEquals(typed, fix, app.suggest(typed))
            assertNull("with $typed", app.correct(typed, previous = "with"))
        }
        assertNull("I feel ill", app.correct("ill", previous = "feel"))
        assertEquals("i'll", app.suggest("ill", previous = "feel"))
        assertNull("he lets me", app.correct("lets", previous = "he"))
        assertEquals("let's", app.suggest("lets", previous = "he"))
    }

    @Test
    fun `other real words never receive missing apostrophe fixes`() {
        for (typed in listOf("its", "were", "well", "shell", "hell")) {
            assertNull(typed, app.propose(typed, sentenceStart = true))
            assertNull(typed, app.propose(typed, previous = "and", afterComma = true))
        }
    }

    @Test
    fun `kept words override missing apostrophe fixes`() {
        val forms = setOf("cant", "wont", "whats", "hes", "shes", "ill", "id", "lets")
        val kept = Corrector({ TypoCorpus.dictionary }, isKept = { it in forms }, lexicon = { TypoCorpus.lexicon })
        for (typed in forms) {
            assertNull(typed, kept.propose(typed, previous = "and", sentenceStart = true, afterComma = true))
        }
    }

    @Test
    fun `historically learned forms still receive missing apostrophe fixes`() {
        val learned = Corrector({ TypoCorpus.dictionary }, isKnown = { true }, lexicon = { TypoCorpus.lexicon })
        val forms = mapOf("cant" to "can't", "wont" to "won't", "whats" to "what's", "hes" to "he's", "shes" to "she's",
            "ill" to "i'll", "id" to "i'd", "lets" to "let's")
        forms.forEach { (typed, fix) -> assertEquals(typed, fix, learned.correct(typed, sentenceStart = true)) }
    }

    @Test
    fun `missing apostrophe fixes use ordinary case rules`() {
        assertEquals("I'll", TypingRules.applyCase(app.correct("ill", sentenceStart = true)!!, "ill", true))
        assertEquals("I'd", TypingRules.applyCase(app.correct("id", previous = "then")!!, "id", false))
        assertEquals("Can't", TypingRules.applyCase(app.correct("Cant")!!, "Cant", false))
        assertNull(app.correct("ILL", sentenceStart = true))
        assertNull(app.correct("ID", sentenceStart = true))
    }

    @Test
    fun `where the finger landed decides between near misses`() {
        // "woklr": taken at key centers, "woke" is closer. Taps that landed toward u and d mean "would".
        val towardWould = listOf(
            KeyGeometry.touchOn('w', 0f, 0f), KeyGeometry.touchOn('o', 0f, 0f),
            KeyGeometry.touchOn('k', -0.45f, -0.5f), KeyGeometry.touchOn('l', 0f, 0f), KeyGeometry.touchOn('r', -0.2f, 0.5f),
        )
        assertEquals("would", app.correct("woklr", towardWould))
    }

    @Test
    fun `the previous word breaks close calls`() {
        val learned = Corrector(
            { TypoCorpus.dictionary },
            lexicon = { TypoCorpus.lexicon },
            contextCount = { previous, word -> if (previous == "i" && word == "would") 5 else 0 },
        )
        assertEquals("would", learned.correct("woklr", previous = "i"))
    }

    @Test
    fun `slang and short words the lists know are left alone`() {
        listOf("lol", "tbh", "bday", "vibing", "bestie", "yall", "ngl", "omw").forEach { assertNull(it, app.correct(it)) }
    }

    @Test
    fun `slang is not shortened into a common word`() {
        // Unknown words may be fixed, but not by dropping letters to reach a much shorter word.
        assertNull(app.correct("zoomer"))
    }

    @Test
    fun `possessives and contractions of real words are left as typed`() {
        assertNull(app.correct("here's"))
        assertNull(app.correct("Here's", sentenceStart = true))
        assertNull(app.correct("friend's"))
        assertNull(app.correct("parents'"))
        assertNull(app.correct("John's"))
        assertNull(app.correct("there'd"))
        // A stem that isn't a real word is still a typo.
        assertEquals("there's", app.correct("ther's"))
        assertEquals("that's", app.correct("tha's"))
        // A swapped apostrophe on a real word is still fixed when the 's form is in the dictionary.
        assertEquals("it's", app.correct("its'"))
        assertEquals("let's", app.correct("lets'"))
    }

    @Test
    fun `mid-sentence capitals are treated as names`() {
        assertNull(app.correct("Cacthig"))
        assertEquals("catching", app.correct("Cacthig", sentenceStart = true))
    }

    @Test
    fun `typing cost prefers near keys and cheap apostrophes`() {
        assert(Corrector.typingCost("dont", "don't") < 0.2f)
        assert(Corrector.typingCost("tge", "the") < Corrector.typingCost("tpe", "the"))
        assert(Corrector.typingCost("teh", "the") < 1f)
    }

    @Test
    fun `stopping early still allows for a swap that skips a row`() {
        // Row 2 of "teh" against "the" costs at least 1.0, but the swap reaches the end for 0.6.
        assertEquals(0.6f, Corrector.typingCost("teh", "the", limit = 0.8f), 1e-5f)
        // Below the swap's 0.6 it stops at row 2 rather than finishing the table.
        assertEquals(Float.MAX_VALUE, Corrector.typingCost("teh", "the", limit = 0.5f), 0f)
    }
}
