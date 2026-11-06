package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class SmallAccommodationsTest {
    private val id = RecentWordCorrection.Identity(1, 2, SmallAccommodations.VERSION)
    private fun snapshot(text: String) = VerifiedWordTransaction.Snapshot(text, "", EditorEditState.Selection(text.length, text.length))
    private fun anchor(word: String, prefix: String = "", own: Boolean = true, protected: Boolean = false) =
        SmallAccommodations.remember(id, snapshot("$prefix$word "), word, 100, own, protected)
    private fun pair(first: String, second: String, prefix: String = "", ownPrevious: String? = first.lowercase()) =
        SmallAccommodations.word(anchor(first, prefix), anchor(second, "$prefix$first "), id,
            snapshot("$prefix$first $second "), ownPrevious, 200, known = true)
    private fun capitals(raw: String, prefix: String = "", known: Boolean = true, locked: Boolean = false) =
        SmallAccommodations.word(null, anchor(raw, prefix), id, snapshot("$prefix$raw "), null, 200, known, locked)

    @Test fun `one owned manual space before a comma requires exact boundaries`() {
        val offer = SmallAccommodations.comma(anchor("Hello"), id, snapshot("Hello ,"), 200)!!
        assertEquals("Hello ", offer.original)
        assertEquals("Hello", offer.replacement)
        assertEquals(",", offer.delimiter)
        assertTrue(offer.automatic)
        assertEquals(0, offer.start)
        for (text in listOf("Hello  ,", "Hello\t,", "Hello\n,", "Hello . ,", "Other Hello ,", "Hello ,suffix"))
            assertNull(text, SmallAccommodations.comma(anchor("Hello"), id, snapshot(text), 200))
        assertNull(SmallAccommodations.comma(null, id, snapshot("Hello ,"), 200))
    }

    @Test fun `bang and question spacing reuse exact owned comma boundaries without touching other punctuation`() {
        for (punctuation in listOf("!", "?")) {
            val offer = SmallAccommodations.comma(anchor("hello"), id, snapshot("hello $punctuation"), 200, punctuation)!!
            assertTrue(offer.automatic)
            assertEquals(punctuation, offer.delimiter)
            assertEquals("hello ", offer.original)
            val state = RecentWordCorrection(); val word = record(offer)
            state.applied(word, offer.replacement, 200, "")
            state.append(" more", 300)
            assertEquals("hello ", state.undoEdit(id, snapshot("hello$punctuation more"), 400)!!.text)
            for (text in listOf("hello  $punctuation", "hello\t$punctuation", "hello?!", "hello!!", "hello $punctuation tail"))
                assertNull(text, SmallAccommodations.comma(anchor("hello"), id, snapshot(text), 200, punctuation))
        }
        for (punctuation in listOf(".", ":", ";", ")", "?!"))
            assertNull(SmallAccommodations.comma(anchor("hello"), id, snapshot("hello $punctuation"), 200, punctuation))
    }

    @Test fun `pasted protected quoted token-like and partial words supply no anchor`() {
        assertNull(anchor("hello", own = false))
        assertNull(anchor("hello", protected = true))
        for (word in listOf("name7", "camel_case", "can't", "name-name", "😀", "")) assertNull(word, anchor(word))
        for (prefix in listOf("\"", "“", "'", "`", "key=", "path/", "email@", "#", "say: ", "not"))
            assertNull(prefix, anchor("hello", prefix))
        assertNull(SmallAccommodations.remember(id, snapshot("hello ").copy(after = "next"), "hello", 100, true))
    }

    @Test fun `repeated function words are explicit phrase offers`() {
        for (word in listOf("the", "a", "an", "to", "of", "in", "on", "for", "with")) {
            val offer = pair(word, word)!!
            assertEquals("$word $word", offer.original)
            assertEquals(word, offer.replacement)
            assertEquals(SmallAccommodations.Kind.REPEATED, offer.kind)
            assertFalse(offer.automatic)
        }
        assertEquals("The", pair("The", "the")!!.replacement)
        for (word in listOf("had", "that", "very", "really", "yes", "no", "bye", "hello"))
            assertNull(word, pair(word, word))
        assertNull(pair("The", "The"))
        assertNull(pair("the", "then"))
    }

    @Test fun `pairs require both own completions at exactly the same positions`() {
        for (ownPrevious in listOf(null, "other")) assertNull(pair("the", "the", ownPrevious = ownPrevious))
        val s = snapshot("the the ")
        assertNull(SmallAccommodations.word(null, anchor("the", "the "), id, s, "the", 200, true))
        assertNull(SmallAccommodations.word(anchor("the"), null, id, s, "the", 200, true))
        assertNull(SmallAccommodations.word(anchor("the"), anchor("the", "other "), id, s, "the", 200, true))
    }

    @Test fun `your not offers a contraction and preserves case without guessing possessives`() {
        assertEquals("you're", pair("your", "not")!!.replacement)
        assertEquals("You're", pair("Your", "not")!!.replacement)
        assertEquals(" not ", pair("your", "not")!!.delimiter)
        assertFalse(pair("your", "not")!!.automatic)
        for (follower in listOf("car", "welcome", "very", "being", "going", "already", "own", "Not"))
            assertNull(follower, pair("your", follower))
        assertNull(pair("YOUR", "not"))
        assertNull(pair("you're", "not"))
    }

    @Test fun `obvious dictionary capital patterns offer only the intended case change`() {
        assertEquals("Working", capitals("WOrking")!!.replacement)
        assertEquals("Thanks", capitals("THanks")!!.replacement)
        assertEquals("Working", capitals("WOrking", "say ")!!.replacement)
        assertTrue(DomainTyping.protected("tHANKS", ""))
        assertFalse(capitals("WOrking")!!.automatic)
        for (raw in listOf("Working", "working", "WORKING", "tHANKS", "iPhone", "McDonald", "eBay", "ABc", "wORKing", "JSON"))
            assertNull(raw, capitals(raw))
        assertNull(capitals("WOrking", known = false))
        assertNull(capitals("WOrking", locked = true))
        assertNull(capitals("WOrking", "key= "))
    }

    @Test fun `uncertain sentence context does not guess capitalization`() {
        val s = snapshot("WOrking ").copy(beforeComplete = false)
        val a = SmallAccommodations.remember(id, s, "WOrking", 100, true)
        assertNull(SmallAccommodations.word(null, a, id, s, null, 200, true))
    }

    @Test fun `cursor dictionary policy time and trailing-text changes invalidate all offers`() {
        val a = anchor("hello")
        val comma = snapshot("hello ,")
        for (identity in listOf(id.copy(field = 3), id.copy(dictionary = 4), id.copy(policy = "old")))
            assertNull(SmallAccommodations.comma(a, identity, comma, 200))
        for (now in listOf(99L, 10_101L)) assertNull(SmallAccommodations.comma(a, id, comma, now))
        for (s in listOf(comma.copy(selection = EditorEditState.Selection(0, 0)),
            comma.copy(selection = EditorEditState.Selection(0, 7)), comma.copy(after = "tail")))
            assertNull(SmallAccommodations.comma(a, id, s, 200))
        val current = anchor("not", "your ")
        for (s in listOf(snapshot("your not ").copy(after = "tail"), snapshot("your not ").copy(selection = EditorEditState.Selection(0, 0))))
            assertNull(SmallAccommodations.word(anchor("your"), current, id, s, "your", 200, true))
        assertNull(SmallAccommodations.word(anchor("your"), current, id.copy(field = 2), snapshot("your not "), "your", 200, true))
        assertNull(SmallAccommodations.word(anchor("your"), current, id, snapshot("your not "), "your", 10_101, true))
    }

    private fun record(offer: SmallAccommodations.Offer) = RecentWordCorrection.Word(id, offer.original,
        offer.delimiter, offer.start, offer.context.takeLast(32), "", 100,
        repairWindowMs = SmallAccommodations.OFFER_MS, grammar = true)

    @Test fun `article offers use explicit pronunciation lists and preserve article case`() {
        for (word in listOf("apple", "egg", "idea", "orange", "umbrella", "hour")) {
            val offer = pair("a", word)!!
            assertEquals("an", offer.replacement)
            assertEquals(" $word ", offer.delimiter)
            assertEquals(SmallAccommodations.Kind.ARTICLE, offer.kind)
            assertFalse(offer.automatic)
        }
        for (word in listOf("banana", "book", "cat", "house", "university", "uniform", "user", "unicorn"))
            assertEquals("a", pair("an", word)!!.replacement)
        assertEquals("An", pair("A", "apple")!!.replacement)
        assertEquals("A", pair("An", "banana")!!.replacement)
        assertEquals("an", pair("a", "apple", "I ate ")!!.replacement)
    }

    @Test fun `articles leave dialect names initials numbers plurals quotes and labels literal`() {
        for (word in listOf("herb", "historic", "historical", "hotel", "heir", "one", "european", "FBI",
            "SQL", "MRI", "Uber", "Apple", "apples", "bananas", "8", "x_ray")) {
            assertNull(word, pair("a", word))
            assertNull(word, pair("an", word))
        }
        for (prefix in listOf("vitamin ", "plan ", "type ", "grade ", "option ", "part ", "section ",
            "class ", "size ", "letter ", "row ", "column ", "bread ", "apples ", "1 ", "a) ", "\"")) {
            assertNull(prefix, pair("a", "apple", prefix))
            assertNull(prefix, pair("an", "banana", prefix))
        }
        assertNull(pair("a", "university"))
        assertNull(pair("an", "hour"))
        assertNull(pair("AN", "banana"))
        assertNull(pair("a", "apple", ownPrevious = null))
    }

    @Test fun `article Apply and Undo change only the article and preserve the complete following text`() {
        val offer = pair("a", "apple", "I ate ")!!
        val recent = RecentWordCorrection(); val word = record(offer)
        recent.begin(word); recent.append("today", 200)
        val apply = recent.repair(id, snapshot("I ate a apple today"), offer.replacement, 300)!!
        assertEquals(6, apply.start)
        assertEquals(7, apply.end)
        assertEquals("an", apply.text)
        recent.applied(word, offer.replacement, 300)
        recent.append(" slowly", 400)
        val undo = recent.undoEdit(id, snapshot("I ate an apple today slowly"), 500)!!
        assertEquals("a", undo.text)
        assertEquals("I ate a apple today slowly".length, undo.caret)
    }

    @Test fun `Apply and Undo retain later typing for each offered convenience`() {
        for ((offer, original, fixed) in listOf(
            Triple(pair("the", "the")!!, "the the ", "the "),
            Triple(pair("your", "not")!!, "your not ", "you're not "),
            Triple(capitals("WOrking")!!, "WOrking ", "Working "))) {
            val state = RecentWordCorrection();val word = record(offer);state.begin(word)
            state.append("nice", 200)
            val edit = state.repair(id, snapshot(original + "nice"), offer.replacement, 300)!!
            assertEquals(original.length + 4 + offer.replacement.length - offer.original.length, edit.caret)
            state.applied(word, offer.replacement, 300)
            state.append(" day ", 400, boundary = true)
            val undo = state.undoEdit(id, snapshot(fixed + "nice day "), 500)!!
            assertEquals(offer.original, undo.text)
            assertEquals((original + "nice day ").length, undo.caret)
        }
    }

    @Test fun `comma Undo restores its manual space while keeping following text`() {
        val offer = SmallAccommodations.comma(anchor("Hello"), id, snapshot("Hello ,"), 200)!!
        val state = RecentWordCorrection();val word = record(offer)
        state.applied(word, offer.replacement, 200, "")
        state.append(" friend ", 300, boundary = true)
        assertEquals(RecentWordCorrection.RangeEdit(0, 5, "Hello ", 15),
            state.undoEdit(id, snapshot("Hello, friend "), 400))
        assertNull(state.undoEdit(id, snapshot("Hello, friend "), 5_200))
    }

    @Test fun `an uncertain editor command is not retried or given Undo`() {
        val initial = snapshot("Hello ,")
        var commands = 0
        val applied = VerifiedWordTransaction.apply(WordEdits.Replacement(7, 0, "Hello,"), initial,
            direct = true, read = { initial }, range = { _, _, _ -> commands++;true },
            select = { _, _ -> fail("unexpected selection");false }, commit = { fail("unexpected retry");false })
        assertNull(applied)
        assertEquals(1, commands)
        assertEquals("Hello ,", initial.before)
    }

    @Test fun `only single-word repairs fit FIX and the new policy cannot append old logs`() {
        assertFalse(SmallAccommodations.loggable("the the", "the"))
        assertFalse(SmallAccommodations.loggable("Hello ", "Hello"))
        assertTrue(SmallAccommodations.loggable("your", "you're"))
        assertTrue(SmallAccommodations.loggable("WOrking", "Working"))
        val old = PredictionLogCodec.correctionHeader("${CorrectionPolicy().version}:${ContextualContractions.VERSION}", true, true)
        val new = PredictionLogCodec.correctionHeader("${CorrectionPolicy().version}:${ContextualContractions.VERSION}:${SmallAccommodations.VERSION}", true, true)
        assertFalse(PredictionLogCodec.sameRanking(old, new))
        val published = PredictionLogCodec.correctionHeader("${CorrectionPolicy().version}:${ContextualContractions.VERSION}:small-v1", true, true)
        assertFalse(PredictionLogCodec.sameRanking(published, new))
        val spacing = PredictionLogCodec.correctionHeader("${CorrectionPolicy().version}:${ContextualContractions.VERSION}:small-v2", true, true)
        assertFalse(PredictionLogCodec.sameRanking(spacing, new))
        assertEquals(spacing, PredictionLogCodec.encodeHeader(spacing).trimEnd().lines().mapNotNull(PredictionLogCodec::parseHeader).toMap().filterKeys { it in spacing })
        assertEquals(published, PredictionLogCodec.encodeHeader(published).trimEnd().lines().mapNotNull(PredictionLogCodec::parseHeader).toMap().filterKeys { it in published })
        assertEquals(old, PredictionLogCodec.encodeHeader(old).trimEnd().lines().mapNotNull(PredictionLogCodec::parseHeader).toMap().filterKeys { it in old })
    }
}
