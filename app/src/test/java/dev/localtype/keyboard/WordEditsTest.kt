package dev.localtype.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordEditsTest {
    private val limit = 280
    private val lineBoundaries = listOf("\n", "\r\n", "\r", "\u2028", "\u2029")

    /** Models deleteSurroundingText followed by commitText, including Android's selection semantics. */
    private class Editor(marked: String) {
        private data class State(val text: String, val start: Int, val end: Int)
        private val history = ArrayList<State>()
        var text: String
        var start: Int
        var end: Int
        init {
            val selection = marked.indexOf('[')
            if (selection >= 0) {
                start = selection
                end = marked.indexOf(']') - 1
                text = marked.replace("[", "").replace("]", "")
            } else {
                start = marked.indexOf('|')
                end = start
                text = marked.replace("|", "")
            }
        }
        val selected get() = start != end
        fun before(n: Int) = text.substring(0, start).takeLast(n)
        fun after(n: Int) = text.substring(end).take(n)
        fun apply(edit: WordEdits.Replacement) {
            history += State(text, start, end)
            val from = start - edit.before
            val to = end + edit.after
            text = text.substring(0, from) + edit.text + text.substring(to)
            start = from + edit.text.length
            end = start
        }
        fun undo() {
            val previous = history.removeAt(history.lastIndex)
            text = previous.text
            start = previous.start
            end = previous.end
        }
        fun marked() = text.substring(0, start) + "|" + text.substring(end)
    }

    private fun suggestion(editor: Editor, word: String, revisited: Boolean = true, readLimit: Int = limit) =
        WordEdits.suggestion(editor.before(readLimit), editor.after(readLimit), editor.selected, word, revisited, readLimit)

    @Test
    fun `a suggestion replaces the entire word around the cursor`() {
        val editor = Editor("say hel|lo")
        val plan = suggestion(editor, "help")!!
        editor.apply(plan.replacement)
        assertEquals("say help |", editor.marked())
        assertFalse(plan.learningEligible)
    }

    @Test
    fun `an existing space is reused and the cursor passes it without another space`() {
        for ((input, expected) in listOf("hel|lo world" to "help |world", "hello|  world" to "help | world")) {
            val editor = Editor(input)
            val plan = suggestion(editor, "help")!!
            editor.apply(plan.replacement)
            assertEquals(expected, editor.marked())
            assertTrue(plan.replacement.autoSpaced)
        }
    }

    @Test
    fun `punctuation and tabs remain at the word boundary without a provisional space`() {
        for ((input, expected) in listOf(
            "hel|lo, world" to "help|, world", "hello|!" to "help|!", "hel|lo)" to "help|)",
            "hel|lo\tworld" to "help|\tworld",
        )) {
            val editor = Editor(input)
            val plan = suggestion(editor, "help")!!
            editor.apply(plan.replacement)
            assertEquals(expected, editor.marked())
            assertFalse(plan.replacement.autoSpaced)
        }
    }

    @Test
    fun `a tapped completion keeps the next typed letter before an existing line boundary`() {
        for (boundary in lineBoundaries) {
            val editor = Editor("please hell|${boundary}second")
            val plan = suggestion(editor, "hello", revisited = false)!!
            editor.apply(plan.replacement)
            editor.apply(WordEdits.Replacement(0, 0, "w"))
            assertEquals("please hello w|${boundary}second", editor.marked())
            assertTrue(plan.replacement.autoSpaced)
            assertTrue(plan.learningEligible)
            assertEquals("please ", plan.contextBeforeWord)
        }
    }

    @Test
    fun `completion inside a word adds a provisional separator without moving across the following line boundary`() {
        for (boundary in lineBoundaries) {
            val editor = Editor("please hel|lo${boundary}second")
            val plan = suggestion(editor, "help")!!
            editor.apply(plan.replacement)
            assertEquals("please help |${boundary}second", editor.marked())
            assertTrue(plan.replacement.autoSpaced)
            assertFalse(plan.learningEligible)
            assertEquals(2, plan.replacement.after)
        }
    }

    @Test
    fun `a selected suggestion keeps the next typed letter in the paragraph and changes only the selected span`() {
        for (boundary in lineBoundaries) {
            val editor = Editor("pre[hell]${boundary}second")
            val plan = suggestion(editor, "hello")!!
            editor.apply(plan.replacement)
            editor.apply(WordEdits.Replacement(0, 0, "w"))
            assertEquals("prehello w|${boundary}second", editor.marked())
            assertEquals(0, plan.replacement.before)
            assertEquals(0, plan.replacement.after)
            assertTrue(plan.replacement.autoSpaced)
            assertFalse(plan.learningEligible)
        }
    }

    @Test
    fun `punctuation after a completion consumes only its provisional separator before a line boundary`() {
        for (boundary in lineBoundaries) {
            for ((punctuation, expected) in listOf("," to ", ", "!" to "! ", "." to ".", "'" to "'", "”" to "” ")) {
                val editor = Editor("please hell|${boundary}second")
                val plan = suggestion(editor, "hello", revisited = false)!!
                editor.apply(plan.replacement)
                val attached = WordEdits.attachPunctuation(editor.before(limit), punctuation, plan.replacement.autoSpaced, false)!!
                editor.apply(attached)
                assertEquals("please hello$expected|${boundary}second", editor.marked())
                assertEquals(0, attached.after)
            }
        }
    }

    @Test
    fun `backspace and editor undo preserve a suggestion's following line boundary`() {
        for (boundary in lineBoundaries) {
            for (selected in listOf(false, true)) {
                val editor = Editor(if (selected) "please [hell]${boundary}second" else "please hell|${boundary}second")
                val originalText = editor.text
                val originalStart = editor.start
                val originalEnd = editor.end
                val plan = suggestion(editor, "hello", revisited = false)!!
                editor.apply(plan.replacement)
                editor.apply(WordEdits.Replacement(0, 0, "w"))
                editor.undo()
                assertEquals("please hello |${boundary}second", editor.marked())
                editor.apply(WordEdits.Replacement(1, 0, ""))
                assertEquals("please hello|${boundary}second", editor.marked())
                editor.undo()
                editor.undo()
                assertEquals(originalText, editor.text)
                assertEquals(originalStart, editor.start)
                assertEquals(originalEnd, editor.end)
            }
        }
    }

    @Test
    fun `ordinary completion at the field end still adds its automatic space`() {
        val editor = Editor("a hel|")
        val plan = suggestion(editor, "hello", revisited = false)!!
        editor.apply(plan.replacement)
        assertEquals("a hello |", editor.marked())
        assertTrue(plan.replacement.autoSpaced)
        assertTrue(plan.learningEligible)
        assertEquals("hel", plan.prefix)
        assertEquals("a ", plan.contextBeforeWord)
    }

    @Test
    fun `a sentence-start tap still learns when an untouched field marked the trace revisited`() {
        val editor = Editor("|")
        val plan = suggestion(editor, "Hey")!!
        editor.apply(plan.replacement)
        assertEquals("Hey |", editor.marked())
        assertTrue(plan.learningEligible)
    }

    @Test
    fun `apostrophes curly apostrophes and hyphens are part of the replaced word`() {
        for ((input, word) in listOf("don|'t" to "don't", "don|’t" to "don't", "mother|-in-law" to "mother", "x-|ray" to "x-ray")) {
            val editor = Editor(input)
            editor.apply(suggestion(editor, word)!!.replacement)
            assertEquals("$word |", editor.marked())
        }
    }

    @Test
    fun `a cursor at the word start replaces its suffix and retains capitalization`() {
        for ((input, expected) in listOf("say |Hello" to "say Help |", "say |WORLD" to "say HELP |", "say Hel|lo" to "say Help |")) {
            val editor = Editor(input)
            val plan = suggestion(editor, "help")!!
            editor.apply(plan.replacement)
            assertEquals(expected, editor.marked())
            assertFalse(plan.learningEligible)
        }
    }

    @Test
    fun `supplementary Unicode letters in the suffix are deleted as full UTF16 pairs`() {
        val editor = Editor("say a|𝑏𝑐 next")
        val plan = suggestion(editor, "abc")!!
        editor.apply(plan.replacement)
        assertEquals("say abc |next", editor.marked())
        assertFalse(plan.learningEligible)
    }

    @Test
    fun `an explicit partial selection replaces only the selection and keeps neighboring fragments`() {
        val editor = Editor("pre[hel]lo world")
        val plan = suggestion(editor, "help")!!
        editor.apply(plan.replacement)
        assertEquals("prehelp |lo world", editor.marked())
        assertEquals(0, plan.replacement.before)
        assertEquals(0, plan.replacement.after)
        assertEquals("", plan.prefix)
        assertEquals("", plan.contextBeforeWord)
        assertFalse(plan.learningEligible)
    }

    @Test
    fun `a selected word before an existing delimiter does not add another space`() {
        val editor = Editor("say [hello] world")
        val plan = suggestion(editor, "help")!!
        editor.apply(plan.replacement)
        assertEquals("say help |world", editor.marked())
        assertTrue(plan.replacement.autoSpaced)
    }

    @Test
    fun `a missing suffix refuses a collapsed replacement without changing text`() {
        val editor = Editor("hel|lo")
        assertNull(WordEdits.suggestion(editor.before(limit), null, false, "help", true, limit))
        assertEquals("hel|lo", editor.marked())
        // Explicit selection remains safe even when surrounding text cannot be read.
        val selected = Editor("pre[hel]lo")
        val plan = WordEdits.suggestion(null, null, true, "help", true, limit)!!
        selected.apply(plan.replacement)
        assertEquals("prehelp |lo", selected.marked())
    }

    @Test
    fun `a refused surrounding deletion cannot insert a duplicate suggestion`() {
        val editor = Editor("hel|lo")
        val plan = suggestion(editor, "help")!!
        assertNull(WordEdits.apply(plan.replacement, delete = { _, _ -> false }, commit = { error("refused edit must not commit") }))
        assertEquals("hel|lo", editor.marked())
    }

    @Test
    fun `a refused correction deletion still inserts the separator as typed`() {
        val editor = Editor("teh|")
        val plan = separator(editor, "?", correct = { "the" })
        val applied = WordEdits.apply(plan.replacement, "?", delete = { _, _ -> false }, commit = {
            editor.apply(WordEdits.Replacement(0, 0, it)); true
        })!!
        assertEquals("teh?|", editor.marked())
        assertEquals(WordEdits.Replacement(0, 0, "?"), applied)
        assertFalse(applied == plan.replacement)
    }

    @Test
    fun `selection replacement does not call surrounding deletion`() {
        val editor = Editor("pre[hel]lo")
        val plan = suggestion(editor, "help")!!
        val applied = WordEdits.apply(plan.replacement, delete = { _, _ -> error("selection must not delete neighbors") }, commit = {
            editor.apply(WordEdits.Replacement(0, 0, it)); true
        })
        assertEquals(plan.replacement, applied)
        assertEquals("prehelp |lo", editor.marked())
    }

    @Test
    fun `bounded reads that do not prove both word boundaries refuse the replacement`() {
        for (input in listOf("he|lllll", "aaaaa|lo", "he|llo𝑥")) {
            val editor = Editor(input)
            assertNull(input, suggestion(editor, "help", readLimit = 4))
            assertEquals(input, editor.marked())
        }
    }

    @Test
    fun `a boundary through a surrogate or combining accent is never split`() {
        assertNull(WordEdits.suggestion("cafe", "\u0301", false, "coffee", true, limit))
        assertNull(WordEdits.suggestion("hel", "lo\u0301", false, "help", true, limit))
        assertNull(WordEdits.suggestion("hel\u0301", "lo", false, "help", true, limit))
        assertNull(WordEdits.suggestion("he\u0301l", "lo", false, "help", true, limit))
        assertNull(WordEdits.suggestion("hel\uD835", "\uDC4E", false, "help", true, limit))
    }

    private fun separator(
        editor: Editor, value: String, revisited: Boolean = false, suggestions: Boolean = true,
        automatic: Boolean = true, after: String? = editor.after(limit), typed: Boolean = true, correct: (String) -> String? = { null },
    ) = WordEdits.separator(editor.before(limit), after, editor.selected, value, revisited, suggestions, automatic, limit, typed, correct = correct)

    @Test
    fun `a selected span cannot trigger double space or automatic punctuation attachment`() {
        val editor = Editor("hello [x]tail")
        assertNull(WordEdits.doubleSpace(editor.before(limit), true, editor.selected, true))
        assertNull(WordEdits.attachPunctuation(editor.before(limit), "!", true, editor.selected))
        editor.apply(separator(editor, "!").replacement)
        assertEquals("hello !|tail", editor.marked())
        val space = Editor("hello [x]tail")
        space.apply(separator(space, " ").replacement)
        assertEquals("hello  |tail", space.marked())
    }

    @Test
    fun `ordinary double space and punctuation attachment keep their existing behavior`() {
        val double = Editor("hello |")
        double.apply(WordEdits.doubleSpace(double.before(limit), true, false, true)!!)
        assertEquals("hello. |", double.marked())
        val punctuation = Editor("hello |")
        punctuation.apply(WordEdits.attachPunctuation(punctuation.before(limit), "!", true, false)!!)
        assertEquals("hello! |", punctuation.marked())
    }

    @Test
    fun `apostrophe joins only the automatic space after a completion without a trailing space`() {
        for (quote in listOf("'", "’")) {
            for (stem in listOf("Luke", "I", "friend", "parents", "you")) {
                val editor = Editor("$stem |next")
                editor.apply(WordEdits.attachPunctuation(editor.before(limit), quote, true, false)!!)
                assertEquals("$stem$quote|next", editor.marked())
            }
            assertNull(WordEdits.attachPunctuation("Luke ", quote, false, false))
            assertNull(WordEdits.attachPunctuation("Luke ", quote, true, true))
            assertNull(WordEdits.attachPunctuation("🙂 ", quote, true, false))
            assertNull(WordEdits.attachPunctuation(" ", quote, true, false))
        }
    }

    @Test
    fun `question and exclamation separators correct a completed typed word without a layer dependency`() {
        val corrector = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })
        for (value in listOf("?", "!", " ")) {
            val editor = Editor("teh|")
            val plan = separator(editor, value, correct = { corrector.correct(it) })
            editor.apply(plan.replacement)
            assertEquals("the$value|", editor.marked())
            assertTrue(plan.corrected)
            assertTrue(plan.learningEligible)
            assertEquals("the", plan.beforeSeparator)
            assertEquals("teh", plan.prefix)
            // The original prefix is retained for immediate delete undo, without the separator.
            val undo = WordEdits.Replacement(plan.replacement.text.length, 0, plan.prefix)
            editor.apply(undo)
            assertEquals("teh|", editor.marked())
        }
    }

    @Test
    fun `a lone lowercase i becomes I before punctuation too`() {
        for (value in listOf("?", "!", ".", ",", ":", ";", " ")) {
            val editor = Editor("so i|")
            val plan = separator(editor, value)
            editor.apply(plan.replacement)
            assertEquals("so I$value|", editor.marked())
            assertFalse(plan.corrected)
            assertEquals("so I", plan.beforeSeparator)
        }
        val inside = Editor("so i|phone")
        inside.apply(separator(inside, "?").replacement)
        assertEquals("so i?|phone", inside.marked())
    }

    @Test
    fun `a lone i stays lowercase with automatic capitalization off`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        for (value in listOf(" ", ".", "?", ",")) {
            val editor = Editor("so i|")
            val plan = WordEdits.separator(
                editor.before(limit), editor.after(limit), false, value, false,
                suggestions = true, automatic = true, readLimit = limit,
                capitalizeI = TypingRules.capitalizesLoneI(inputType, autoCapitalize = false),
            ) { null }
            editor.apply(plan.replacement)
            assertEquals("so i$value|", editor.marked())
            assertFalse(plan.corrected)
        }
    }

    @Test
    fun `names and addresses preserve typed words on space and punctuation while still learning`() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PERSON_NAME, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)) {
            val inputType = InputType.TYPE_CLASS_TEXT or variation
            for (word in listOf("teh", "i")) for (value in listOf(" ", ".", ",", "!", "?")) {
                val editor = Editor("$word|")
                val plan = WordEdits.separator(
                    editor.before(limit), editor.after(limit), false, value, false,
                    suggestions = TypingRules.wantsSuggestions(inputType),
                    automatic = TypingRules.allowsAutocorrect(inputType), readLimit = limit,
                    capitalizeI = !TypingRules.isNameOrAddress(inputType),
                ) { error("a name or address must not reach automatic correction") }
                editor.apply(plan.replacement)
                assertEquals("$word$value|", editor.marked())
                assertTrue(plan.learningEligible)
                assertFalse(plan.corrected)
            }
        }
    }

    @Test
    fun `punctuation inside an existing word inserts only the punctuation and cannot learn`() {
        val editor = Editor("hel|lo")
        val plan = separator(editor, "?", correct = { error("a partial word must not reach the corrector") })
        editor.apply(plan.replacement)
        assertEquals("hel?|lo", editor.marked())
        assertFalse(plan.learningEligible)
        assertFalse(plan.corrected)
    }

    @Test
    fun `punctuation on a selection changes no surrounding word`() {
        val editor = Editor("pre[hel]lo")
        val plan = separator(editor, "!", correct = { error("a selection must not reach the corrector") })
        editor.apply(plan.replacement)
        assertEquals("pre!|lo", editor.marked())
        assertFalse(plan.learningEligible)
    }

    @Test
    fun `revisited unknown-suffix and clipped words get a plain separator without learning`() {
        for (revisited in listOf(true, false)) {
            val editor = Editor("teh|")
            val plan = separator(editor, "!", revisited, after = if (revisited) "" else null,
                correct = { error("unproven or revisited words must not reach the corrector") })
            editor.apply(plan.replacement)
            assertEquals("teh!|", editor.marked())
            assertFalse(plan.learningEligible)
        }
        val clipped = WordEdits.separator("aaaa", "", false, "?", false, true, true, 4) { error("clipped word") }
        assertEquals("?", clipped.replacement.text)
        assertFalse(clipped.learningEligible)
    }

    @Test
    fun `web no-autocorrect and disabled-suggestion fields retain the typed spelling`() {
        val webOff = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val editor = Editor("teh|")
        val plan = separator(editor, "?", automatic = TypingRules.wantsSuggestions(webOff), correct = { error("web autofill-off") })
        editor.apply(plan.replacement)
        assertEquals("teh?|", editor.marked())
        val disabled = Editor("i|")
        val off = separator(disabled, "!", suggestions = false, correct = { error("disabled suggestions") })
        disabled.apply(off.replacement)
        assertEquals("i!|", disabled.marked())
        assertFalse(off.learningEligible)
    }

    @Test
    fun `edited-word taps remain outside personalization while ordinary and private field gates are retained`() {
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList())
        for (editor in listOf(Editor("hel|lo"), Editor("hello|"), Editor("[hello]"))) {
            val plan = suggestion(editor, "help")!!
            if (plan.learningEligible) model.accept(null, plan.word)
        }
        assertFalse(model.knows("help"))
        val ordinary = suggestion(Editor("hel|"), "hello", revisited = false)!!
        for ((flags, input, allowed) in listOf(
            Triple(0, InputType.TYPE_CLASS_TEXT, true),
            Triple(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, InputType.TYPE_CLASS_TEXT, false),
            Triple(0, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, false),
        )) {
            val fresh = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList())
            if (ordinary.learningEligible && TypingRules.allowsLearning(flags, input)) fresh.accept(null, ordinary.word)
            assertEquals(allowed, fresh.knows("hello"))
        }
    }

    @Test
    fun `a corrected punctuation outcome keeps raw spelling and verification before trace clearing`() {
        val trace = WordTrace()
        var prefix = ""
        "teh".forEach { trace.letter(prefix, null); prefix += it }
        val editor = Editor("teh|")
        val corrector = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })
        val plan = separator(editor, "?", correct = { corrector.correct(it) })
        val verified = plan.learningEligible && trace.hasTouchesFor(plan.prefix)
        editor.apply(plan.replacement)
        trace.finish()
        assertTrue(verified)
        assertFalse(trace.hasTouchesFor(plan.prefix))
        val records = ArrayList<LogRecord>()
        val session = PredictionLogSession({ records.addAll(it) })
        session.fieldStart(1, false, true, true, 0, false)
        session.offer(OfferEvent("teh", "teh", 3, false, 'S', 'N', true, emptyList(), listOf("the"), "R", 0, 1, 0, 0, 1))
        session.typed(TypedEvent(plan.beforeSeparator, plan.prefix, "the", '?', true, verified, null))
        val outcome = records.filterIsInstance<LogRecord.Typed>().single()
        assertEquals("teh", outcome.typed)
        assertEquals("the", outcome.committed)
        assertEquals('?', outcome.sep)
        assertTrue(outcome.corrected)
        assertTrue(records.filterIsInstance<LogRecord.Mutation>().isEmpty())
    }

    @Test
    fun `an edited suggestion abandons the pending instance without a learning mutation`() {
        val records = ArrayList<LogRecord>()
        val session = PredictionLogSession({ records.addAll(it) })
        session.fieldStart(1, false, true, true, 0, false)
        session.offer(OfferEvent("hel", "hel", 3, false, 'L', 'N', true, emptyList(), listOf("help"), "R", 0, 1, 0, 0, 1))
        val editor = Editor("hel|lo")
        val plan = suggestion(editor, "help", revisited = false)!!
        assertFalse(plan.learningEligible)
        editor.apply(plan.replacement)
        session.offer(OfferEvent(editor.before(limit), "", 0, true, 'L', 'N', true, emptyList(), emptyList(), "", 0, 1, 0, 0, 2))
        session.fieldEnd()
        assertEquals(1, records.filterIsInstance<LogRecord.Abandon>().size)
        assertTrue(records.filterIsInstance<LogRecord.Tap>().isEmpty())
        assertTrue(records.filterIsInstance<LogRecord.Mutation>().isEmpty())
    }

    @Test
    fun `a new completion before punctuation learns once and its immediate echo is not a revisit`() {
        val records = ArrayList<LogRecord>()
        val session = PredictionLogSession({ records.addAll(it) })
        session.fieldStart(1, false, true, true, 0, false)
        session.offer(OfferEvent("hel", "hel", 3, false, 'L', 'N', true, emptyList(), listOf("hello"), "R", 0, 1, 0, 0, 1))
        val trace = WordTrace()
        var prefix = ""
        "hel".forEach { trace.letter(prefix, null); prefix += it }
        val editor = Editor("hel|!")
        val plan = suggestion(editor, "hello", revisited = false)!!
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList(), dictionary = TypoCorpus.dictionary)
        assertTrue(plan.learningEligible)
        editor.apply(plan.replacement)
        model.accept(null, plan.word)
        session.tap(TapEvent(plan.contextBeforeWord, plan.prefix, 0, plan.word, 'R', null, 1, true))
        trace.finish()
        assertEquals("hello|!", editor.marked())
        session.offer(OfferEvent("hello", "hello", trace.touchCount, trace.revisited, 'L', 'N', true, emptyList(), emptyList(), "", 0, 1, 0, 0, 2))
        val punctuation = separator(editor, "?", revisited = trace.revisited, typed = trace.touchCount > 0,
            correct = { error("a tapped word must not be corrected or learned again") })
        assertFalse(punctuation.learningEligible)
        editor.apply(punctuation.replacement)
        if (punctuation.learningEligible) model.learnTypedWord(punctuation.beforeSeparator, true)
        session.fieldEnd()
        assertEquals("hello?|!", editor.marked())
        assertEquals(1, model.pairCount(AdaptiveNgramModel.START, "hello"))
        assertEquals(1, records.filterIsInstance<LogRecord.Tap>().size)
        assertTrue(records.filterIsInstance<LogRecord.Revisit>().isEmpty())
        assertTrue(records.filterIsInstance<LogRecord.Typed>().isEmpty())
        assertTrue(records.filterIsInstance<LogRecord.Mutation>().isEmpty())
    }
    @Test fun `reused prediction space attaches a closing quote but preserves deliberate extra whitespace`() {
        for (marked in listOf("\"it doesnt| next", "\"it [doesnt] next")) {
            val editor = Editor(marked)
            val plan = suggestion(editor, "doesn't")!!
            editor.apply(plan.replacement)
            assertEquals("\"it doesn't |next", editor.marked())
            editor.apply(WordEdits.attachPunctuation(editor.before(limit), "\"", plan.replacement.autoSpaced, false)!!)
            assertEquals("\"it doesn't\" |next", editor.marked())
        }
        val editor = Editor("[hello]  next")
        editor.apply(suggestion(editor, "help")!!.replacement)
        assertEquals("help | next", editor.marked())
        assertNull(WordEdits.attachPunctuation("hello ", "\"", false, false))
    }

    @Test fun `a straight quote after a completion's space opens a quotation unless one is open on the line`() {
        // No quotation open: the mark opens one, so nothing attaches and the space stays before it.
        assertNull(WordEdits.attachPunctuation("He said ", "\"", true, false))
        assertNull(WordEdits.attachPunctuation("\"Yes,\" she said ", "\"", true, false))
        // One open on this line: the mark closes it and takes the completion's space.
        assertEquals(WordEdits.Replacement(1, 0, "\" ", autoSpaced = true), WordEdits.attachPunctuation("He said \"hello ", "\"", true, false))
        // A quotation left open on an earlier line does not turn this one into a closing mark.
        assertNull(WordEdits.attachPunctuation("\"unfinished\nHe said ", "\"", true, false))
        // A curly closing mark says which it is.
        assertEquals(WordEdits.Replacement(1, 0, "” ", autoSpaced = true), WordEdits.attachPunctuation("He said ", "”", true, false))
    }

}
