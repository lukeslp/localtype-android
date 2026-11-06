package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionLogSessionTest {
    /** The device's own context rules, for expected values and for the ss/prev self-check. */
    private val rules = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList())

    /** Drives a session with the shapes the service posts; defaults make every event verified and consistent. */
    private inner class Script(keyChars: Int = 200, contextChars: Int = 160) {
        val blocks = mutableListOf<List<LogRecord>>()
        val session = PredictionLogSession({ blocks += it }, keyChars = keyChars, contextChars = contextChars)
        val records: List<LogRecord> get() = blocks.flatten()
        inline fun <reified T : LogRecord> all(): List<T> = records.filterIsInstance<T>()
        val words get() = all<LogRecord.Word>()
        val offers get() = all<LogRecord.Offer>()
        val taps get() = all<LogRecord.Tap>()
        val typeds get() = all<LogRecord.Typed>()
        val mutations get() = all<LogRecord.Mutation>()
        val fieldEnds get() = all<LogRecord.FieldEnd>()

        fun field(seq: Int = 1, restart: Boolean = false) =
            session.fieldStart(seq, restart, autocorrect = true, profanity = false, model = 0, emoji = true)

        fun offer(
            context: String,
            prefix: String = rules.activePrefix(context),
            touches: Int = prefix.length,
            revisited: Boolean = false,
            ranked: List<Pair<String, Int>> = listOf("the" to 100, "hello" to 90, "hi" to 80),
            slots: List<String> = listOf("the", "hello", "hi"),
            src3: String = "RRR",
            model: Int = 0,
            dict: Int = 20000,
            shown: Boolean = true,
            branch: Char = 'N',
            mode: Char = 'L',
            us: Int = 100,
            rus: Int = 300,
            stripRevision: Int = 1,
        ) = session.offer(
            OfferEvent(context, prefix, touches, revisited, mode, branch, shown, ranked, slots, src3, model, dict, us, rus, stripRevision),
        )

        /** Offers every prefix of [word] after [before], as typing it letter by letter would. */
        fun type(before: String, word: String) {
            for (p in 0..word.length) offer(before + word.take(p))
        }

        fun tap(
            before: String,
            prefix: String,
            word: String,
            idx: Int = 0,
            src: Char = 'R',
            prev: String? = rules.lastCompletedWord(before),
            stripRevision: Int = 1,
            verified: Boolean = true,
        ) = session.tap(TapEvent(before, prefix, idx, word, src, prev, stripRevision, verified))

        fun typed(
            beforeSeparator: String,
            committed: String = rules.activePrefix(beforeSeparator),
            typedRaw: String? = null,
            sep: Char = ' ',
            learned: Boolean = true,
            verified: Boolean = true,
            prev: String? = rules.lastCompletedWord(beforeSeparator),
        ) = session.typed(TypedEvent(beforeSeparator, typedRaw, committed, sep, learned, verified, prev))

        /** Types [word] after [before] and finishes it with a space. */
        fun finish(before: String, word: String, learned: Boolean = true) {
            type(before, word)
            typed(before + word, learned = learned)
        }
    }

    @Test
    fun `repair links verified original through a following word and Undo retains learning`() {
        val s = Script()
        s.field()
        s.finish("", "Teh", learned = false)
        s.session.repair(RepairEvent("", "teh", "the", 'O'))
        s.session.repair(RepairEvent("", "teh", "the", 'J'))
        s.session.repair(RepairEvent("", "teh", "the", 'P', true))
        s.finish("the ", "help")
        s.session.repair(RepairEvent("", "TEH", "the", 'U'))
        s.finish("Teh help ", "folks")
        val repairs = s.all<LogRecord.Repair>()
        assertEquals(listOf('O', 'J', 'P', 'U'), repairs.map { it.outcome })
        assertTrue(repairs.all { it.wid == s.words.first().wid && it.original == "Teh" })
        assertEquals(listOf(false, false, true, false), repairs.map { it.learned })
        assertEquals("Teh help ".lowercase(), s.words.last().sentence.lowercase())
        assertEquals(0, s.session.hiddenMutations)
    }

    @Test
    fun `unlinked and tainted learned repairs hide both words and context`() {
        val s = Script()
        s.field()
        s.offer("private secret", touches = 0)
        s.typed("private secret", verified = false, learned = false)
        s.session.repair(RepairEvent("private ", "secret", "confidential", 'P', true, "private"))
        s.session.repair(RepairEvent("unseen ", "secret", "confidential", 'O'))
        assertTrue(s.all<LogRecord.Repair>().isEmpty())
        assertEquals(LogRecord.Mutation(1, "", ""), s.mutations.single())
        assertFalse(PredictionLogCodec.encodeBlock(s.records).contains("private"))
        assertFalse(PredictionLogCodec.encodeBlock(s.records).contains("secret"))
        assertFalse(PredictionLogCodec.encodeBlock(s.records).contains("confidential"))
    }

    @Test
    fun `repair history is bounded and fresh fields reset original identity`() {
        val s = Script()
        s.field()
        var before = ""
        repeat(PredictionLogSession.HISTORY_WORDS + 1) {
            s.finish(before, "hello", learned = false)
            before += "hello "
        }
        s.session.repair(RepairEvent("", "hello", "hi", 'O'))
        assertTrue(s.all<LogRecord.Repair>().isEmpty())
        s.session.repair(RepairEvent(before.dropLast(6), "hello", "hi", 'O'))
        assertEquals(1, s.all<LogRecord.Repair>().size)
        s.field(seq = 2)
        s.session.repair(RepairEvent(before.dropLast(6), "hello", "hi", 'O'))
        assertEquals(1, s.all<LogRecord.Repair>().size)
    }

    @Test
    fun `same field restart retains repair identity while snapshot and reset invalidate it`() {
        val s = Script()
        s.field()
        s.finish("", "teh", learned = false)
        s.session.fieldEnd()
        s.field(seq = 2, restart = true)
        s.session.repair(RepairEvent("", "teh", "the", 'O'))
        assertEquals(1, s.all<LogRecord.Repair>().size)
        s.session.snapshot(PredictionLogCodec.SNAP_REBUILD, emptyMap(), emptyList())
        s.session.repair(RepairEvent("", "teh", "the", 'P', true))
        assertEquals(1, s.all<LogRecord.Repair>().size)
        assertEquals(1, s.session.hiddenMutations)
        s.session.reset()
        s.field(seq = 3, restart = true)
        s.session.repair(RepairEvent("", "teh", "the", 'O'))
        assertEquals(1, s.all<LogRecord.Repair>().size)
    }

    @Test
    fun `applied learning flushes earlier offers first and unknown prev stays hidden`() {
        val s = Script()
        s.field()
        s.finish("", "teh", learned = false)
        s.type("teh ", "he")
        s.session.repair(RepairEvent("", "teh", "the", 'P', true, "unverified"))
        assertTrue(s.records.indexOfFirst { it is LogRecord.Abandon } < s.records.indexOfFirst { it is LogRecord.Repair })
        assertEquals("", s.all<LogRecord.Repair>().single().prev)
        assertFalse(PredictionLogCodec.encodeBlock(s.records).contains("unverified"))
    }

    @Test
    fun `immediate repair records do not learn again and invalid Undo cannot claim benefit`() {
        val s = Script()
        s.field()
        s.type("", "teh")
        s.typed("the", committed = "the", typedRaw = "teh")
        s.session.repair(RepairEvent("", "teh", "the", 'U'))
        assertTrue(s.all<LogRecord.Repair>().isEmpty())
        s.session.repair(RepairEvent("", "teh", "the", 'P'))
        s.session.repair(RepairEvent("", "teh", "the", 'P'))
        s.session.repair(RepairEvent("", "teh", "wrong", 'U'))
        s.session.repair(RepairEvent("", "teh", "the", 'U'))
        s.session.repair(RepairEvent("", "teh", "the", 'U'))
        assertEquals(listOf('P', 'U'), s.all<LogRecord.Repair>().map { it.outcome })
        assertTrue(s.all<LogRecord.Repair>().none { it.learned })
        assertTrue(s.typeds.single().learned)
    }

    // region dedupe and merge

    @Test
    fun `neural field source merge and tap preserve ngram reference and timing`() {
        val s = Script()
        val digest = "ab".repeat(32)
        s.session.fieldStart(1, false, true, false, 0, true, digest)
        s.offer("", slots = listOf("the", "hello", "hi"), src3 = "RRR", us = 100)
        s.offer("", slots = listOf("thanks", "the", "hello"), src3 = "NRR", us = 300, stripRevision = 2)
        s.tap("", "", "thanks", src = 'N', stripRevision = 2)
        s.session.fieldEnd()
        assertEquals(digest, s.all<LogRecord.Field>().single().neuralSha256)
        assertEquals("NRR", s.offers.single().src3)
        assertEquals(listOf("the" to 100, "hello" to 90, "hi" to 80), s.offers.single().ranked)
        assertEquals(100, s.offers.single().us)
        assertEquals('N', s.taps.single().src)
        s.session.fieldStart(2, true, true, false, 0, true, digest)
        s.offer("thanks ", slots = listOf("folks", "the", "hello"), src3 = "PRR")
        s.tap("thanks ", "", "folks", src = 'P')
        assertEquals('P', s.taps.last().src)
        assertEquals("thanks ", s.words.last().sentence)
        s.field(3, restart = true)
        s.finish("thanks folks ", "hello")
        assertEquals("thanks folks ", s.words.last().sentence)
        assertNull(s.all<LogRecord.Field>().last().neuralSha256)
    }

    @Test
    fun `double refresh yields one O per prefix and counts the merge`() {
        val s = Script()
        s.field()
        s.offer("")
        s.offer("")
        s.offer("h")
        s.offer("h")
        s.typed("h", learned = false)
        s.session.fieldEnd()
        assertEquals(listOf(0 to "", 1 to "h"), s.offers.map { it.p to it.prefix })
        assertEquals(2, s.fieldEnds.single().dup)
    }

    @Test
    fun `dict 0 then dict above 0 at one prefix keeps one O with the loaded dictionary`() {
        val s = Script()
        s.field()
        s.offer("", dict = 0, ranked = emptyList(), us = 5)
        s.offer("", dict = 20000, us = 200)
        s.offer("h")
        s.finish("", "h", learned = false)
        val first = s.offers.first { it.p == 0 }
        assertEquals(1, s.offers.count { it.p == 0 })
        assertEquals(20000, first.dict)
        assertEquals(200, first.us)
        assertEquals(3, first.ranked.size)
    }

    @Test
    fun `model answer re-refresh updates slots and src3 and keeps the first timing`() {
        val s = Script()
        s.field()
        s.offer("")
        s.offer("he", slots = listOf("hello", "help", "hey"), src3 = "RRR", us = 100)
        s.offer("he", slots = listOf("heading", "hello", "help"), src3 = "MRR", model = 1, us = 300, stripRevision = 2)
        s.typed("he", learned = false)
        val merged = s.offers.single { it.prefix == "he" }
        assertEquals("MRR", merged.src3)
        assertEquals(listOf("heading", "hello", "help"), merged.slots)
        assertEquals(1, merged.model)
        assertEquals(100, merged.us)
        assertEquals(1, s.offers.count { it.prefix == "he" })
    }

    @Test
    fun `backspace re-offer merges and a different prefix is kept`() {
        val s = Script()
        s.field()
        s.type("", "hel")
        s.offer("he")
        s.offer("hep")
        s.typed("hep", learned = false)
        s.session.fieldEnd()
        assertEquals(listOf("", "h", "he", "hel", "hep"), s.offers.map { it.prefix })
        assertEquals(1, s.fieldEnds.single().dup)
        assertEquals(1, s.words.size)
    }

    @Test
    fun `a full window keeps one word key while the window slides`() {
        val base = "ab ".repeat(100)
        val s = Script()
        s.field()
        s.offer("")
        s.tap("", "", "ab")
        for (p in 0..5) s.offer((base + "hello".take(p)).takeLast(280))
        s.typed((base + "hello ").takeLast(280).dropLast(1), learned = false)
        assertEquals(2, s.words.size)
        assertEquals(7, s.offers.size)
        // No sentence boundary anywhere in a full window, and only the last "ab" was committed here.
        assertTrue(s.words[1].cut)
        assertEquals("ab ", s.words[1].sentence)
    }

    // endregion

    // region identity

    @Test
    fun `field start offer with revisited true opens with revisit 0`() {
        val s = Script()
        s.field()
        s.offer("", revisited = true)
        s.offer("h")
        s.offer("hi")
        s.tap("", "hi", "hi")
        s.session.fieldEnd()
        assertFalse(s.words.single().revisit)
        assertTrue(s.all<LogRecord.Revisit>().isEmpty())
        assertEquals(0, s.fieldEnds.single().revisit)
    }

    @Test
    fun `stale reads after a commit are discarded and counted`() {
        val s = Script()
        s.field()
        s.type("", "hel")
        s.tap("", "hel", "hello")
        s.offer("hel")
        s.offer("hello")
        // An empty prefix at the closed key is never stale: a field cleared after Send looks the same.
        s.offer("")
        s.offer("hello ")
        s.finish("hello ", "there", learned = false)
        s.session.fieldEnd()
        assertEquals(2, s.words.size)
        assertEquals(2, s.fieldEnds.single().stale)
        assertEquals(1, s.fieldEnds.single().single)
    }

    @Test
    fun `an equal key with a new prefix after send is an ordinary instance`() {
        val s = Script()
        s.field()
        s.type("", "hi")
        s.tap("", "hi", "hi")
        // The field was emptied by Send and the p=0 refresh coalesced away.
        s.offer("t")
        s.offer("th")
        s.tap("", "th", "thanks")
        s.session.fieldEnd()
        assertEquals(2, s.words.size)
        assertFalse(s.words[1].revisit)
        assertEquals(0, s.fieldEnds.single().stale)
        assertTrue(s.all<LogRecord.Revisit>().isEmpty())
    }

    @Test
    fun `backspace over the space after a tap opens a revisit and writes R at once`() {
        val s = Script()
        s.field()
        s.type("", "hi")
        s.tap("", "hi", "hi")
        // The trace was cleared by the commit, so the revisited word carries no touches.
        s.offer("hi", revisited = true, touches = 0)
        s.session.fieldEnd()
        assertEquals(listOf(LogRecord.Revisit(1)), s.all<LogRecord.Revisit>())
        assertEquals(1, s.fieldEnds.single().revisit)
        // The revisit instance itself is unverified and never written.
        assertEquals(1, s.words.size)
        assertEquals(1, s.fieldEnds.single().unverifiedForeign)
    }

    @Test
    fun `the next-word refresh after a tap does not hide the revisit`() {
        val s = Script()
        s.field()
        s.type("", "hel")
        s.tap("", "hel", "hello")
        // The commit refreshes the strip at the new key before the person backspaces over the space.
        s.offer("hello ")
        s.offer("hello", revisited = true, touches = 0)
        s.session.fieldEnd()
        assertEquals(listOf(LogRecord.Revisit(1)), s.all<LogRecord.Revisit>())
        assertEquals(1, s.fieldEnds.single().revisit)
        assertEquals(1, s.fieldEnds.single().single)
        assertEquals(1, s.words.size)
    }

    @Test
    fun `a field cleared after send opens an ordinary instance`() {
        val s = Script()
        s.field()
        s.type("", "th")
        s.tap("", "th", "Thanks")
        s.offer("Thanks ")
        // Send emptied the field; the cursor jump marks the trace revisited and the prefix is empty.
        s.offer("", revisited = true, touches = 0)
        s.type("", "ok")
        s.typed("ok")
        s.session.fieldEnd()
        assertTrue(s.all<LogRecord.Revisit>().isEmpty())
        assertEquals(listOf(false, false), s.words.map { it.revisit })
        assertEquals(0, s.fieldEnds.single().revisit)
        assertEquals(0, s.fieldEnds.single().stale)
        assertEquals(listOf(0, 1, 2), s.offers.filter { it.wid == 2 }.map { it.p })
    }

    @Test
    fun `a revisit at the key of an abandoned instance writes no R`() {
        val s = Script()
        s.field(1)
        s.type("", "hi")
        s.session.fieldEnd()
        s.field(2, restart = true)
        s.offer("hi", revisited = true, touches = 0)
        s.session.fieldEnd()
        assertTrue(s.all<LogRecord.Revisit>().isEmpty())
        assertEquals(listOf(LogRecord.Abandon(1, 'F')), s.all<LogRecord.Abandon>())
        assertEquals(listOf(0, 1), s.fieldEnds.map { it.revisit })
    }

    @Test
    fun `an instance with only the next-word offer is counted and not written`() {
        val s = Script()
        s.field()
        s.offer("")
        s.offer("hello. ")
        s.offer("hello. world. ")
        s.session.fieldEnd()
        assertTrue(s.words.isEmpty())
        assertEquals(3, s.fieldEnds.single().single)
    }

    @Test
    fun `a new key closes the open instance as abandoned`() {
        val s = Script()
        s.field()
        s.type("", "he")
        s.offer("he\n")
        s.session.fieldEnd()
        assertEquals(listOf(LogRecord.Abandon(1, 'N')), s.all<LogRecord.Abandon>())
    }

    // endregion

    // region verification

    @Test
    fun `touch count above p taints as short and below p as foreign`() {
        val s = Script()
        s.field()
        s.offer("")
        s.offer("h", touches = 2)
        s.typed("h", learned = false)
        s.offer("h. ")
        s.offer("h. e", touches = 0)
        s.typed("h. e", learned = false)
        s.session.fieldEnd()
        assertTrue(s.words.isEmpty())
        val ef = s.fieldEnds.single()
        assertEquals(1, ef.unverifiedShort)
        assertEquals(1, ef.unverifiedForeign)
    }

    @Test
    fun `an unverified typed word is dropped and leaves a hidden mutation`() {
        val s = Script()
        s.field()
        s.type("", "the")
        s.typed("the", verified = false)
        s.session.fieldEnd()
        assertTrue(s.words.isEmpty())
        // The device credited a sentence start, which is text-free and so written as START.
        assertEquals(listOf(LogRecord.Mutation(1, "", AdaptiveNgramModel.START)), s.mutations)
        assertEquals(1, s.session.hiddenMutations)
        assertEquals(1, s.fieldEnds.single().x)
        assertEquals(1, s.fieldEnds.single().unverifiedForeign)
    }

    @Test
    fun `a mutation's previous word is what the device used when verified or START, else empty`() {
        val s = Script()
        s.field()
        s.type("", "hi")
        s.tap("", "hi", "hi")
        // Unverified letters taint the instance; the device's previous word differs from the verified one.
        s.offer("hi y", touches = 0)
        s.tap("hi ", "y", "you", prev = "yo")
        s.offer("hi you t", touches = 0)
        s.tap("hi you ", "t", "there", prev = "you")
        // After a comma the device credits START as well.
        s.type("hi you there, ", "th")
        s.typed("hi you there, th", verified = false)
        s.session.fieldEnd()
        assertEquals(
            listOf(LogRecord.Mutation(1, "you", ""), LogRecord.Mutation(1, "there", "you"), LogRecord.Mutation(1, "", AdaptiveNgramModel.START)),
            s.mutations,
        )
    }

    @Test
    fun `a hidden mutation keeps the verified previous word`() {
        val s = Script()
        s.field()
        s.type("", "hi")
        s.tap("", "hi", "hi")
        s.type("hi ", "the")
        s.typed("hi the", verified = false)
        assertEquals(listOf(LogRecord.Mutation(1, "", "hi")), s.mutations)
    }

    @Test
    fun `backspacing to a shorter prefix and finishing it is accepted`() {
        val s = Script()
        s.field()
        s.type("", "helo")
        s.offer("hel")
        s.typed("hel", learned = false)
        assertEquals("hel", s.typeds.single().typed)
        assertEquals("hel", s.typeds.single().committed)
        assertEquals(listOf("", "h", "he", "hel", "helo"), s.offers.map { it.prefix })
    }

    @Test
    fun `autocorrect keeps the raw keys beside the committed word`() {
        val s = Script()
        s.field()
        s.type("", "helo")
        s.typed("hello", typedRaw = "helo")
        val y = s.typeds.single()
        assertEquals("helo", y.typed)
        assertEquals("hello", y.committed)
        assertTrue(y.corrected)
        assertTrue(y.learned)
        assertEquals(' ', y.sep)
    }

    @Test
    fun `a lone i is committed as I`() {
        val s = Script()
        s.field()
        s.type("", "i")
        s.typed("I", typedRaw = "i")
        assertEquals("I", s.typeds.single().committed)
        assertEquals("i", s.typeds.single().typed)
        // The history holds the committed word, so the next word verifies against it.
        s.offer("I ")
        s.offer("I a")
        s.typed("I am", learned = false)
        assertEquals("I ", s.words[1].sentence)
        assertEquals("i", s.words[1].prev)
    }

    @Test
    fun `a typed word without a matching open instance leaves only a mutation`() {
        val s = Script()
        s.field()
        s.typed("hello", verified = true)
        s.type("", "hi")
        s.typed("yo hi", verified = false)
        assertTrue(s.words.isEmpty())
        // The first word starts the sentence, so its row carries START; the second's previous word "yo" was never verified.
        assertEquals(listOf(LogRecord.Mutation(1, "hello", AdaptiveNgramModel.START), LogRecord.Mutation(1, "", "")), s.mutations)
        assertEquals(1, s.session.hiddenMutations)
    }

    // endregion

    // region taps

    @Test
    fun `tap sources pass through and X is forced on a strip revision mismatch or no instance`() {
        val s = Script()
        s.field()
        s.type("", "th")
        s.tap("", "th", "the", src = 'R')
        s.type("the ", "q")
        s.tap("the ", "q", "quick", src = 'M', idx = 1)
        s.type("the quick ", "brwn")
        s.tap("the quick ", "brwn", "brown", src = 'C', idx = 1)
        s.type("the quick brown ", "fx")
        s.tap("the quick brown ", "fx", "fx", src = 'L')
        s.type("the quick brown fx ", "j")
        s.session.tapEmoji(2)
        s.type("the quick brown fx. ", "o")
        s.tap("the quick brown fx. ", "o", "over", src = 'R', stripRevision = 7)
        s.tap("somewhere else ", "", "lost", src = 'R')
        s.tap("", "", "hey", src = 'R')
        assertEquals(listOf('R', 'M', 'C', 'L', 'E', 'X'), s.taps.map { it.src })
        assertEquals(listOf(2, 1, 4, 2, 1, 1), s.taps.map { it.p })
        assertEquals("hi", s.taps[4].word)
        // With no instance, only a sentence start is known for sure; "else" was never verified.
        assertEquals(listOf(LogRecord.Mutation(1, "lost", ""), LogRecord.Mutation(1, "hey", AdaptiveNgramModel.START)), s.mutations)
    }

    @Test
    fun `an unverified literal tap leaves a hidden mutation and stays out of the history`() {
        val s = Script()
        s.field()
        // "Jonh" was pasted: no touches, and the corrector offers "John" beside the literal.
        s.offer("Jonh", touches = 0, branch = 'C', slots = listOf("Jonh", "John", "Jon"), src3 = "LCR")
        s.tap("", "Jonh", "Jonh", idx = 0, src = 'L', verified = false)
        s.type("Jonh ", "ok")
        s.typed("Jonh ok")
        s.session.fieldEnd()
        assertTrue(s.words.isEmpty())
        // The next word's previous word is the pasted text, so it is unknown rather than verified.
        assertEquals(listOf(LogRecord.Mutation(1, "", AdaptiveNgramModel.START), LogRecord.Mutation(1, "ok", "")), s.mutations)
        assertEquals(1, s.session.hiddenMutations)
        assertEquals(1, s.fieldEnds.single().unverifiedForeign)
        assertEquals(1, s.fieldEnds.single().unknown)
    }

    @Test
    fun `a correction tap on a touch-typed prefix is written`() {
        val s = Script()
        s.field()
        s.type("", "Jonh")
        s.tap("", "Jonh", "John", idx = 1, src = 'C')
        s.session.fieldEnd()
        assertEquals(listOf('C'), s.taps.map { it.src })
        assertTrue(s.mutations.isEmpty())
    }

    @Test
    fun `ps is 0 when the previous word the device used differs`() {
        val s = Script()
        s.field()
        s.type("", "hi")
        s.tap("", "hi", "hi")
        s.type("hi ", "th")
        s.tap("hi ", "th", "there", prev = "yo")
        s.type("hi there ", "y")
        s.typed("hi there you", prev = null)
        s.type("hi there you ", "a")
        s.typed("hi there you all", prev = "you")
        assertEquals(listOf(true, false), s.taps.map { it.ps })
        assertEquals(listOf(false, true), s.typeds.map { it.ps })
    }

    @Test
    fun `a tap on an unknown instance writes X with the word and an empty prev`() {
        val s = Script()
        s.field()
        s.type("Dear John ", "th")
        s.tap("Dear John ", "th", "thanks")
        s.session.fieldEnd()
        assertTrue(s.words.isEmpty())
        assertEquals(listOf(LogRecord.Mutation(1, "thanks", "")), s.mutations)
        assertEquals(1, s.fieldEnds.single().unknown)
        assertEquals(0, s.session.hiddenMutations)
    }

    @Test
    fun `an emoji tap on a dropped instance writes nothing`() {
        val s = Script()
        s.field()
        s.type("Dear John ", "j")
        s.session.tapEmoji(2)
        s.session.fieldEnd()
        assertTrue(s.records.none { it is LogRecord.Word || it is LogRecord.Mutation })
        assertEquals(1, s.fieldEnds.single().unknown)
    }

    // endregion

    // region verified sentence

    @Test
    fun `an unverified word before punctuation keeps only the trailing run`() {
        val s = Script()
        s.field()
        s.type("Dear John, ", "th")
        s.typed("Dear John, th", learned = false)
        val w = s.words.single()
        assertEquals(", ", w.sentence)
        assertEquals("", w.prev)
        assertFalse(w.ss)
        assertFalse(w.cut)
    }

    @Test
    fun `digits between verified words are masked in the stored tail`() {
        val s = Script()
        s.field()
        var before = ""
        for (word in listOf("my", "code", "is")) {
            s.finish(before, word)
            before += "$word "
        }
        // A pasted one-time code, then a typed word: the words verify, the digits do not.
        s.finish("my code is 483920 ", "ok")
        s.session.fieldEnd()
        val w = s.words.last()
        assertEquals("my code is 0 ", w.sentence)
        assertEquals("", w.prev)
        assertFalse(w.ss)
        assertNull(rules.lastCompletedWord(w.sentence))
        assertEquals(rules.isSentenceStart("my code is 483920 "), rules.isSentenceStart(w.sentence))
    }

    @Test
    fun `a trailing run with no verified word is masked too`() {
        val s = Script()
        s.field()
        s.finish(" 4111 1111 1111 ", "ok")
        s.session.fieldEnd()
        val w = s.words.single()
        assertEquals(" 0 0 0 ", w.sentence)
        assertEquals("", w.prev)
        assertTrue(s.mutations.isEmpty())
    }

    @Test
    fun `an unverified previous word is unknown and dropped`() {
        val s = Script()
        s.field()
        s.type("Dear John ", "th")
        s.typed("Dear John th")
        s.session.fieldEnd()
        assertTrue(s.words.isEmpty())
        assertEquals(listOf(LogRecord.Mutation(1, "th", "")), s.mutations)
        assertEquals(1, s.fieldEnds.single().unknown)
    }

    @Test
    fun `the unknown cascade stops after one word`() {
        val s = Script()
        s.field()
        var before = "Dear John "
        for (word in listOf("thanks", "for", "the", "help")) {
            s.finish(before, word)
            before += "$word "
        }
        s.session.fieldEnd()
        assertEquals(listOf("thanks ", "thanks for ", "thanks for the "), s.words.map { it.sentence })
        assertEquals(listOf("thanks", "for", "the"), s.words.map { it.prev })
        assertEquals(1, s.fieldEnds.single().unknown)
        assertEquals(listOf(LogRecord.Mutation(1, "thanks", "")), s.mutations)
    }

    @Test
    fun `only the current sentence is stored and earlier ones stay out`() {
        val s = Script()
        s.field()
        var before = ""
        for (word in listOf("see", "you")) {
            s.finish(before, word)
            before += "$word "
        }
        s.offer("see you. ")
        s.offer("see you. T")
        s.typed("see you. Thanks")
        s.finish("see you. Thanks ", "again")
        assertEquals(listOf("", "see ", " ", "Thanks "), s.words.map { it.sentence })
        assertEquals(listOf(true, false, true, false), s.words.map { it.ss })
        assertEquals(listOf("", "see", "", "thanks"), s.words.map { it.prev })
    }

    @Test
    fun `a long tail is cut on a token boundary`() {
        val s = Script()
        s.field()
        var before = ""
        repeat(40) {
            s.offer(before)
            s.tap(before, "", "hello")
            before += "hello "
        }
        s.offer(before)
        s.offer(before + "w")
        s.typed(before + "w", learned = false)
        val w = s.words.last()
        assertTrue(w.cut)
        assertTrue(w.sentence.length <= 160)
        assertEquals("hello ".repeat(26), w.sentence)
    }

    @Test
    fun `ss and prev recomputed from the stored sentence match the device`() {
        val s = Script()
        s.field()
        val contexts = listOf(
            "Hi there.  ",
            "Hello, world ",
            "Done. The ",
            "one\ntwo ",
            "Wait... ",
            "Really?! ",
            "It's 5 ",
            "Hello,\tworld ",
            "Hello - world ",
        )
        val written = contexts.map { context ->
            // Every word in the context was committed on this keyboard first, in order.
            for (match in Regex("[\\p{L}][\\p{L}'’-]*").findAll(context)) {
                val before = context.substring(0, match.range.first)
                s.offer(before)
                s.tap(before, "", match.value)
            }
            val count = s.words.size
            s.offer(context)
            s.offer(context + "x")
            s.typed(context + "x", learned = false)
            assertEquals(context, count + 1, s.words.size)
            s.words.last()
        }
        written.forEachIndexed { i, w ->
            assertEquals(contexts[i], rules.isSentenceStart(contexts[i]), w.ss)
            assertEquals(contexts[i], rules.lastCompletedWord(contexts[i]).orEmpty(), w.prev)
            assertEquals(contexts[i], w.ss, rules.isSentenceStart(w.sentence))
            assertEquals(contexts[i], w.prev, rules.lastCompletedWord(w.sentence).orEmpty())
        }
        assertEquals(
            // The digit is not verified text and is stored as the placeholder, which resets context the same way.
            listOf("  ", "Hello, world ", "The ", "two ", " ", " ", "It's 0 ", "Hello,\tworld ", "Hello - world "),
            written.map { it.sentence },
        )
    }

    // endregion

    // region history

    @Test
    fun `history survives a restarting field and clears on a fresh one`() {
        val s = Script()
        s.field(1)
        s.finish("", "hello")
        s.session.fieldEnd()
        s.field(2, restart = true)
        s.finish("hello ", "there")
        s.session.fieldEnd()
        s.field(3, restart = false)
        s.finish("hello there ", "friend")
        s.session.fieldEnd()
        assertEquals(listOf("", "hello "), s.words.map { it.sentence })
        assertEquals(listOf(1, 2), s.words.map { it.fieldSeq })
        assertEquals(listOf(0, 0, 1), s.fieldEnds.map { it.unknown })
    }

    @Test
    fun `history holds the last 64 committed words`() {
        val s = Script(contextChars = 1000)
        s.field()
        val words = ('a'..'z').flatMap { a -> ('a'..'c').map { b -> "$a$b" } }.take(70)
        var before = ""
        for (word in words) {
            s.offer(before)
            s.tap(before, "", word)
            before += "$word "
        }
        s.offer(before)
        s.offer(before + "x")
        s.typed(before + "x", learned = false)
        assertEquals(words.drop(6).joinToString(" ") + " ", s.words.last().sentence)
        assertEquals(PredictionLogSession.HISTORY_WORDS, 70 - 6)
    }

    /**
     * Reversed on 2026-10-13 (Luke's decision of 2026-10-12): this pinned "a word from a tainted instance never
     * enters the history". The X row already prints the verified word, so the file shows it either way, and
     * keeping it out of the history only made the next word's pair unreplayable.
     */
    @Test
    fun `a word its X row prints enters the history`() {
        val s = Script()
        s.field()
        s.offer("")
        s.offer("h", touches = 0)
        s.offer("hi", touches = 0)
        s.typed("hi")
        s.finish("hi ", "there")
        s.session.fieldEnd()
        assertEquals(listOf(LogRecord.Mutation(1, "hi", AdaptiveNgramModel.START)), s.mutations)
        assertEquals(listOf("hi "), s.words.map { it.sentence })
        assertEquals(listOf("hi"), s.words.map { it.prev })
        assertEquals(listOf(true), s.typeds.map { it.ps })
        assertEquals(0, s.fieldEnds.single().unknown)
        assertEquals(1, s.fieldEnds.single().unverifiedForeign)
    }

    @Test
    fun `a tainted word that was not learned prints nothing and stays out of the history`() {
        val s = Script()
        s.field()
        s.offer("")
        s.offer("h", touches = 0)
        s.offer("hi", touches = 0)
        s.typed("hi", learned = false)
        s.finish("hi ", "there")
        s.session.fieldEnd()
        assertTrue(s.words.isEmpty())
        assertEquals(listOf(LogRecord.Mutation(1, "there", "")), s.mutations)
        assertFalse(PredictionLogCodec.encodeBlock(s.records).contains("hi"))
    }

    @Test
    fun `a word with no instance names the newest history word and is the next word's previous word`() {
        val s = Script()
        s.field()
        s.finish("", "hi")
        // Typed inside the refresh debounce: no offer ever opened an instance for it.
        s.typed("hi there")
        s.finish("hi there ", "friend")
        s.session.fieldEnd()
        assertEquals(listOf(LogRecord.Mutation(1, "there", "hi")), s.mutations)
        assertEquals(listOf("", "hi there "), s.words.map { it.sentence })
        assertEquals("there", s.words.last().prev)
        assertEquals(0, s.fieldEnds.single().unknown)
    }

    @Test
    fun `a word with no instance never names a previous word this session did not just commit`() {
        val s = Script()
        s.field()
        s.finish("Dear John ", "hi")
        // The device's previous word is pre-filled text here, and an older history word there.
        s.typed("Dear John hi then Dear there", prev = "dear")
        s.typed("hi John there", prev = "john")
        assertEquals(
            listOf(LogRecord.Mutation(1, "hi", ""), LogRecord.Mutation(1, "there", ""), LogRecord.Mutation(1, "there", "")),
            s.mutations,
        )
        assertFalse(PredictionLogCodec.encodeBlock(s.records).lowercase().contains("john"))
    }

    @Test
    fun `an orphan after a learned repair names the repaired previous word`() {
        val s = Script()
        s.field()
        s.finish("", "Teh", learned = false)
        s.session.repair(RepairEvent("", "teh", "the", 'P', true))
        s.typed("The help")
        assertEquals(listOf(LogRecord.Mutation(1, "help", "the")), s.mutations)
    }

    @Test
    fun `a period then a space writes the typed row`() {
        val s = Script()
        s.field()
        s.finish("", "say")
        s.type("say ", "hello")
        // The period is committed at once and learning waits: the refresh after it has a new key.
        s.offer("say hello.")
        assertTrue(s.all<LogRecord.Abandon>().isEmpty())
        s.typed("say hello")
        s.offer("say hello. ")
        s.session.fieldEnd()
        assertTrue(s.all<LogRecord.Abandon>().isEmpty())
        assertTrue(s.mutations.isEmpty())
        assertEquals(listOf("say", "hello"), s.typeds.map { it.committed })
        assertEquals(listOf("", "say "), s.words.map { it.sentence })
        assertEquals((0..5).toList(), s.offers.filter { it.wid == 2 }.map { it.p })
        assertEquals(listOf(true, true), s.typeds.map { it.ps })
        // The two empty-prefix instances after the period were never written.
        assertEquals(2, s.fieldEnds.single().single)
        assertEquals(0, s.fieldEnds.single().x)
    }

    @Test
    fun `the offer ranked before a period word's learning is not written after it`() {
        val s = Script()
        s.field()
        s.type("", "hello")
        s.offer("hello.")
        // Enter on a field with an action learns the word and adds no space; the next word starts at the same key.
        s.typed("hello", sep = '\n')
        s.offer("hello.")
        s.offer("hello.c")
        s.typed("hello.c", learned = false)
        s.session.fieldEnd()
        assertEquals(listOf("hello", "c"), s.typeds.map { it.committed })
        // The second block holds only offers made after the first word was learned: its first empty-prefix offer was dropped.
        assertEquals(listOf(0, 1), s.offers.filter { it.wid == 2 }.map { it.p })
        assertEquals(1, s.fieldEnds.single().single)
        assertEquals(s.records.indexOfFirst { it is LogRecord.Typed }, s.records.indexOfFirst { it is LogRecord.Word && it.wid == 2 } - 1)
    }

    @Test
    fun `a corrected word ended by a period keeps its raw keys`() {
        val s = Script()
        s.field()
        s.type("", "helo")
        s.offer("helo.")
        s.offer("helo.")
        s.typed("hello", typedRaw = "helo")
        assertEquals(LogRecord.Typed(1, "helo", "hello", ' ', corrected = true, learned = true, ps = true), s.typeds.single())
        assertTrue(s.mutations.isEmpty())
    }

    @Test
    fun `a period then a letter abandons the word`() {
        val s = Script()
        s.field()
        s.type("", "example")
        s.offer("example.")
        assertTrue(s.records.none { it is LogRecord.Word })
        // A domain: the letter cancels the keyboard's deferred learning, so no outcome will come.
        s.offer("example.c")
        assertEquals(listOf(LogRecord.Abandon(1, 'N')), s.all<LogRecord.Abandon>())
        s.offer("example.co")
        s.typed("example.co", learned = false)
        s.session.fieldEnd()
        assertEquals(listOf("", ""), s.words.map { it.sentence })
        assertEquals(listOf("co"), s.typeds.map { it.committed })
    }

    @Test
    fun `a tap after a period abandons the word and is written itself`() {
        val s = Script()
        s.field()
        s.type("", "hello")
        s.offer("hello.")
        s.tap("hello.", "", "Thanks")
        assertEquals(listOf(LogRecord.Abandon(1, 'N')), s.all<LogRecord.Abandon>())
        assertEquals(listOf("Thanks"), s.taps.map { it.word })
        assertEquals(listOf(1, 2), s.words.map { it.wid })
        // The deferred learning can still arrive when nothing cancelled it; its instance is gone.
        s.typed("hello")
        assertEquals(listOf(LogRecord.Mutation(1, "hello", AdaptiveNgramModel.START)), s.mutations)
    }

    @Test
    fun `a field end while a word is parked writes it as abandoned`() {
        val s = Script()
        s.field()
        s.type("", "hello")
        s.offer("hello.")
        s.session.fieldEnd()
        assertEquals(listOf(LogRecord.Abandon(1, 'N')), s.all<LogRecord.Abandon>())
        assertEquals(1, s.fieldEnds.single().single)
        assertTrue(s.records.last() is LogRecord.FieldEnd)
    }

    @Test
    fun `another word's outcome abandons the parked word first`() {
        val s = Script()
        s.field()
        s.type("", "hello")
        s.offer("hello.")
        s.typed("other")
        assertEquals(listOf(LogRecord.Abandon(1, 'N')), s.all<LogRecord.Abandon>())
        assertEquals(listOf(LogRecord.Mutation(1, "other", AdaptiveNgramModel.START)), s.mutations)
        assertTrue(s.records.indexOfFirst { it is LogRecord.Abandon } < s.records.indexOfFirst { it is LogRecord.Mutation })
    }

    @Test
    fun `a learned repair flushes the parked word before its FIX row`() {
        val s = Script()
        s.field()
        s.finish("", "Teh", learned = false)
        s.type("Teh ", "help")
        s.offer("Teh help.")
        s.session.repair(RepairEvent("", "teh", "the", 'P', true))
        val abandon = s.records.indexOfFirst { it is LogRecord.Abandon }
        assertTrue(abandon in 0 until s.records.indexOfFirst { it is LogRecord.Repair })
        // An unlearned repair row changes nothing the parked offers depend on, so the word keeps waiting.
        val t = Script()
        t.field()
        t.finish("", "Teh", learned = false)
        t.type("Teh ", "help")
        t.offer("Teh help.")
        t.session.repair(RepairEvent("", "teh", "the", 'O'))
        t.typed("Teh help")
        assertTrue(t.all<LogRecord.Abandon>().isEmpty())
        assertEquals(listOf("Teh", "help"), t.typeds.map { it.committed })
    }

    @Test
    fun `a period that does not follow the open word parks nothing`() {
        val s = Script()
        s.field()
        s.type("", "hel")
        // The text before the new word is not this word and a period.
        s.offer("other.")
        assertEquals(listOf(LogRecord.Abandon(1, 'N')), s.all<LogRecord.Abandon>())
        val t = Script()
        t.field()
        t.type("", "hel")
        t.offer("help.")
        t.typed("hello")
        assertEquals(listOf(LogRecord.Abandon(1, 'N')), t.all<LogRecord.Abandon>())
        assertEquals(listOf(LogRecord.Mutation(1, "hello", AdaptiveNgramModel.START)), t.mutations)
    }

    @Test
    fun `re-entering the newest word aligns the text before it and replaces its history entry`() {
        val s = Script()
        s.field()
        s.finish("", "hi")
        s.finish("hi ", "there")
        // Backspace over the space, delete two letters, type another ending, finish it.
        s.offer("hi there", revisited = true)
        s.offer("hi the", revisited = true)
        s.offer("hi them", revisited = true)
        s.typed("hi them")
        s.finish("hi them ", "all")
        s.session.fieldEnd()
        assertTrue(s.mutations.isEmpty())
        assertEquals(1, s.all<LogRecord.Revisit>().size)
        assertEquals(listOf(false, false, true, false), s.words.map { it.revisit })
        assertEquals(listOf("", "hi ", "hi ", "hi them "), s.words.map { it.sentence })
        assertEquals(listOf("", "hi", "hi", "them"), s.words.map { it.prev })
        assertEquals(0, s.fieldEnds.single().unknown)
    }

    @Test
    fun `a tainted revisit names its previous word in the X row`() {
        val s = Script()
        s.field()
        s.finish("", "hi")
        s.finish("hi ", "there")
        s.offer("hi there", revisited = true, touches = 0)
        s.typed("hi there")
        s.finish("hi there ", "all")
        s.session.fieldEnd()
        assertEquals(listOf(LogRecord.Mutation(1, "there", "hi")), s.mutations)
        assertEquals("hi there ", s.words.last().sentence)
        assertEquals(0, s.fieldEnds.single().unknown)
    }

    @Test
    fun `a revisit after an undone correction is written with its previous word`() {
        val s = Script()
        s.field()
        s.finish("", "see")
        s.type("see ", "teh")
        s.typed("see the", typedRaw = "teh")
        s.session.repair(RepairEvent("see ", "teh", "the", 'P'))
        s.session.repair(RepairEvent("see ", "teh", "the", 'U'))
        // Backspace undid the correction; the raw word is back and a space keeps it.
        s.offer("see teh", revisited = true)
        s.typed("see teh")
        s.finish("see teh ", "now")
        s.session.fieldEnd()
        assertTrue(s.mutations.isEmpty())
        assertEquals(listOf("", "see ", "see ", "see teh "), s.words.map { it.sentence })
        assertEquals(listOf("see", "the", "teh", "now"), s.typeds.map { it.committed })
    }

    // endregion

    // region fields and snapshots

    @Test
    fun `field end abandons the open instance and writes every counter`() {
        val s = Script()
        s.field(4)
        s.type("", "he")
        s.session.fieldEnd()
        assertEquals(listOf(LogRecord.Abandon(1, 'F')), s.all<LogRecord.Abandon>())
        assertEquals(LogRecord.FieldEnd(4, 0, 0, 0, 0, 0, 0, 0, 0), s.fieldEnds.single())
        assertEquals(LogRecord.Field(4, false, true, false, 0, true), s.all<LogRecord.Field>().single())
    }

    @Test
    fun `one field can raise all nine counters`() {
        val s = Script()
        s.field()
        s.offer("")
        s.offer("")
        s.offer("h")
        s.offer("hi")
        s.tap("", "hi", "hi")
        s.offer("hi")
        s.offer("hi ")
        s.offer("hi there ")
        s.offer("hi there f")
        s.tap("hi there ", "f", "for")
        s.offer("hi there for ")
        s.offer("hi there for t", touches = 2)
        s.offer("hi there for to. ")
        s.offer("hi there for to. w", touches = 0)
        s.typed("hi there for to. wo", learned = false)
        s.offer("hi there for to. wo", revisited = true, touches = 0)
        s.session.fieldEnd()
        assertEquals(LogRecord.FieldEnd(1, dup = 1, stale = 1, single = 1, unverifiedShort = 1, unverifiedForeign = 2, unknown = 1, revisit = 1, x = 1), s.fieldEnds.single())
        assertEquals(1, s.words.size)
    }

    @Test
    fun `a field start while a field is open ends it first`() {
        val s = Script()
        s.field(1)
        s.type("", "he")
        s.field(2, restart = true)
        s.session.fieldEnd()
        assertEquals(listOf("F", "W", "O", "O", "O", "A", "EF", "F", "EF"), s.records.map { it.tag })
        assertEquals(listOf(1, 2), s.fieldEnds.map { it.seq })
    }

    @Test
    fun `offers outside a field are ignored`() {
        val s = Script()
        s.offer("")
        s.offer("h")
        assertTrue(s.records.isEmpty())
    }

    @Test
    fun `reset forgets the history and restarts the wid sequence`() {
        val s = Script()
        s.field()
        s.finish("", "hello")
        s.session.reset()
        s.field()
        // The history is gone, so "hello" is no longer verified.
        s.offer("hello ")
        s.offer("hello t")
        s.typed("hello t", learned = false)
        s.finish("", "hi", learned = false)
        s.session.fieldEnd()
        assertEquals(listOf(1, 1), s.words.map { it.wid })
        assertEquals(listOf("", ""), s.words.map { it.sentence })
        // The first field was dropped by the reset without an EF row.
        assertEquals(1, s.fieldEnds.size)
        assertEquals(1, s.fieldEnds.single().unknown)
    }

    @Test
    fun `a snapshot writes sorted tables as one block and returns its hash`() {
        val s = Script()
        val hash = s.session.snapshot(
            PredictionLogCodec.SNAP_PROCESS,
            mapOf("thanks" to 3, "hello" to 1),
            listOf(Triple("thanks", "you", 2), Triple(AdaptiveNgramModel.START, "thanks", 3)),
        )
        assertNotNull(hash)
        assertEquals(1, s.blocks.size)
        assertEquals(
            listOf(
                LogRecord.Snapshot("process", 4),
                LogRecord.Unigram("hello", 1),
                LogRecord.Unigram("thanks", 3),
                LogRecord.Bigram(AdaptiveNgramModel.START, "thanks", 3),
                LogRecord.Bigram("thanks", "you", 2),
                LogRecord.SnapshotEnd,
            ),
            s.blocks.single(),
        )
        val rows = s.blocks.single().drop(1).dropLast(1).map(PredictionLogCodec::encode)
        assertEquals(PredictionLogCodec.blockHash(rows), hash)
    }

    @Test
    fun `a matching hash writes SNAP same without rows`() {
        val s = Script()
        val tables = mapOf("hello" to 2)
        val first = s.session.snapshot(PredictionLogCodec.SNAP_PROCESS, tables, emptyList())
        val again = s.session.snapshot(PredictionLogCodec.SNAP_REBUILD, tables, emptyList(), lastHash = first)
        val changed = s.session.snapshot(PredictionLogCodec.SNAP_REBUILD, mapOf("hello" to 3), emptyList(), lastHash = first)
        assertEquals(first, again)
        assertEquals(listOf(LogRecord.Snapshot("same", 0), LogRecord.SnapshotEnd), s.blocks[1])
        assertEquals(LogRecord.Snapshot("rebuild", 1), s.blocks[2].first())
        assertTrue(changed != first)
    }

    @Test
    fun `a snapshot over the row cap is partial and has no hash`() {
        val s = Script()
        val unigrams = HashMap<String, Int>(60_000)
        for (i in 0 until PredictionLogSession.MAX_SNAPSHOT_ROWS) unigrams["w$i"] = 1
        val hash = s.session.snapshot(PredictionLogCodec.SNAP_FILE, unigrams, listOf(Triple("a", "b", 1)))
        assertNull(hash)
        val block = s.blocks.single()
        assertEquals(LogRecord.Snapshot("file-partial", PredictionLogSession.MAX_SNAPSHOT_ROWS), block.first())
        assertEquals(LogRecord.SnapshotEnd, block.last())
        assertEquals(PredictionLogSession.MAX_SNAPSHOT_ROWS + 2, block.size)
        assertTrue(block.none { it is LogRecord.Bigram })
    }

    @Test
    fun `every written block round-trips through the codec`() {
        val s = Script()
        s.field()
        s.finish("", "hello")
        s.type("hello ", "th")
        s.tap("hello ", "th", "there")
        s.offer("hello there", revisited = true, touches = 0)
        s.typed("hello there", verified = false)
        s.session.fieldEnd()
        for (record in s.records) {
            assertEquals(record, PredictionLogCodec.decode(PredictionLogCodec.encode(record)))
        }
        assertEquals(listOf("F", "W", "O", "O", "O", "O", "O", "O", "Y", "W", "O", "O", "O", "T", "R", "X", "EF"), s.records.map { it.tag })
    }

    // endregion
}
