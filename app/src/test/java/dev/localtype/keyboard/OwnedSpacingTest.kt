package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class OwnedSpacingTest {
    private val id = RecentWordCorrection.Identity(1, 2, SmallAccommodations.VERSION)
    private val known = setOf("see", "tomorrow", "hello", "friend", "later", "i")
    private fun s(text: String) = VerifiedWordTransaction.Snapshot(text, "", EditorEditState.Selection(text.length, text.length))
    private fun ordinary(word: String) = word.lowercase() in known
    private fun extend(state: OwnedSpacing, initial: String, text: String, allowed: Boolean = true, now: Long = 200): String {
        var before = initial
        text.forEach { state.appended(id, before, it.toString(), now, allowed); before += it }
        return before
    }
    private fun paren(text: String = "see tomorrow "): Pair<OwnedSpacing, String> {
        val state = OwnedSpacing(); state.opened(id, s("("), 100)
        return state to extend(state, "(", text)
    }
    private fun comma(first: String = "hello", second: String = "friend "): Pair<OwnedSpacing, String> {
        val state = OwnedSpacing(); state.commaTyped(id, s("$first,"), first, 100, true)
        return state to extend(state, "$first,", second)
    }
    @Test fun `an owned simple parenthetical run cleans only the last manual space`() {
        val (state, before) = paren()
        val offer = state.closing(id, s(before), 300, ::ordinary)!!
        assertTrue(offer.automatic)
        assertEquals("tomorrow ", offer.original)
        assertEquals("tomorrow", offer.replacement)
        assertEquals("(see ", offer.context)
        assertEquals(5, offer.start)
        assertEquals(")", offer.delimiter)
        assertFalse(state.active)
    }
    @Test fun `existing pasted nested mathematical quoted identifier and double-space parentheses stay literal`() {
        assertNull(OwnedSpacing().closing(id, s("(later "), 300, ::ordinary))
        for (text in listOf("later", "later  ", " later ", "later\t", "later\n", "(later ", "see) later ",
            "x+y ", "see/later ", "#later ", "@later ", "v2 ", "camelCase ", "\"later\" ", "LATER ", "unlisted ")) {
            val (state, before) = paren(text)
            assertNull(text, state.closing(id, s(before), 300, ::ordinary))
        }
        val (state, before) = paren()
        assertNull(state.closing(id, s(before), 300) { false }) // kept/added/shortcut/reviewed word
    }
    @Test fun `opening requires a coherent end receipt and a prose boundary`() {
        for (receipt in listOf(s("( "), s("say("), s("@name ("), s("key= ("), s("(later ("),
            s("(").copy(beforeComplete = false), s("(").copy(after = "tail"), s("(").copy(selection = EditorEditState.Selection(0, 1)))) {
            val state = OwnedSpacing(); state.opened(id, receipt, 100)
            assertFalse(receipt.toString(), state.active)
        }
        val state = OwnedSpacing(); state.opened(id, s("say ("), 100)
        assertTrue(state.active)
    }
    private fun dot(first: String = "of", second: String = "the ", context: String = ""): Pair<OwnedSpacing, String> {
        val state = OwnedSpacing(); state.dotTyped(id, s("$context$first."), first, 100, true)
        return state to extend(state, "$context$first.", second)
    }
    private val words = { a: String, b: String -> ordinary(a) && ordinary(b) || (a == "of" && b == "the") || (a == "end" && b == "Then") }
    @Test fun `a period typed for a space is offered back as a space at the next word's space`() {
        val (state, before) = dot()
        val offer = state.dotSpace(id, s(before), "the", 300, words)!!
        assertFalse(offer.automatic)
        assertEquals(SmallAccommodations.Kind.DOT_SPACE, offer.kind)
        assertEquals("of.the", offer.original)
        assertEquals("of the", offer.replacement)
        assertEquals(" ", offer.delimiter)
        assertEquals(0, offer.start)
        assertNull(state.dotSpace(id, s(before), "the", 300, words))
        // A capital after the dot is a sentence end that lost its space.
        val (capital, text) = dot("end", "Then ", "the ")
        val sentence = capital.dotSpace(id, s(text), "Then", 300, words)!!
        assertEquals("end. Then", sentence.replacement)
        assertEquals(4, sentence.start)
    }
    @Test fun `addresses, unowned dots, uncommon words and longer runs stay literal`() {
        for (ending in listOf("com", "org", "net", "io", "app", "dev")) {
            val (state, before) = dot("hello", "$ending ")
            assertNull(ending, state.dotSpace(id, s(before), ending, 300) { _, _ -> true })
        }
        assertNull(OwnedSpacing().dotSpace(id, s("of.the "), "the", 300, words))
        for (second in listOf("the.com ", "th e ", "the  ", "the_x ", "t2 ")) {
            val (state, before) = dot(second = second)
            assertNull(second, state.dotSpace(id, s(before), "the", 300, words))
        }
        val (state, before) = dot()
        assertNull(state.dotSpace(id, s(before), "the", 300) { _, _ -> false })
        for (word in listOf("@of", "#of", "v2", "OF", "a")) {
            val fresh = OwnedSpacing(); fresh.dotTyped(id, s("$word."), word, 100, true)
            assertFalse(word, fresh.active)
        }
        val unverified = OwnedSpacing(); unverified.dotTyped(id, s("of."), "of", 100, false)
        assertFalse(unverified.active)
        // A quoted or path-like context is not prose.
        val path = OwnedSpacing(); path.dotTyped(id, s("see /of."), "of", 100, true)
        assertFalse(path.active)
        val (late, text) = dot()
        assertNull(late.dotSpace(id, s(text), "the", 10_101, words))
    }
    @Test fun `comma space is an Apply offer only after the second owned ordinary word ends`() {
        val (state, before) = comma()
        val offer = state.missingCommaSpace(id, s(before), "friend", 300, true)!!
        assertFalse(offer.automatic)
        assertEquals("hello,friend", offer.original)
        assertEquals("hello, friend", offer.replacement)
        assertEquals(" ", offer.delimiter)
        assertEquals(0, offer.start)
        assertNull(state.missingCommaSpace(id, s(before), "friend", 300, true))
    }
    @Test fun `comma offers never infer either word or split numeric tagged addressed or multiple-comma runs`() {
        assertNull(OwnedSpacing().missingCommaSpace(id, s("hello,friend "), "friend", 300, true))
        for ((first, second) in listOf("100" to "200 ", "@hello" to "friend ", "#hello" to "friend ",
            "hello" to "friend,see ", "hello" to " friend ", "hello" to "friend  ",
            "hello" to "friend.dev ", "hello" to "@friend ", "hello" to "friend_name ")) {
            val (state, before) = comma(first, second)
            assertNull("$first,$second", state.missingCommaSpace(id, s(before), "friend", 300, true))
        }
        val state = OwnedSpacing(); state.commaTyped(id, s("hello,"), "hello", 100, false)
        assertFalse(state.active)
        val (unverified, before) = comma()
        assertNull(unverified.missingCommaSpace(id, s(before), "friend", 300, false))
        for (word in listOf("@hello", "#hello", "snake_case", "camelCase", "v2", "HELLO")) {
            state.commaTyped(id, s("$word,"), word, 100, true)
            assertFalse(word, state.active)
        }
    }
    @Test fun `unknown fields text cursor time identity and ownership reset both runs`() {
        for (identity in listOf(id.copy(field = 2), id.copy(dictionary = 3), id.copy(policy = "old"))) {
            val (p, text) = paren(); assertNull(p.closing(identity, s(text), 300, ::ordinary))
            val (c, commaText) = comma(); assertNull(c.missingCommaSpace(identity, s(commaText), "friend", 300, true))
        }
        for (now in listOf(99L, 10_101L)) {
            val (state, text) = paren(); assertNull(state.closing(id, s(text), now, ::ordinary))
        }
        for (change in listOf<(VerifiedWordTransaction.Snapshot) -> VerifiedWordTransaction.Snapshot>(
            { it.copy(after = "tail") }, { it.copy(beforeComplete = false) },
            { it.copy(before = "other " + it.before) }, { it.copy(selection = EditorEditState.Selection(1, 1)) })) {
            val (state, text) = paren(); assertNull(state.closing(id, change(s(text)), 300, ::ordinary))
        }
        for (allowed in listOf(false, true)) {
            val (state, text) = paren("later")
            state.appended(id, if (allowed) "foreign" else text, " ", 200, allowed)
            assertFalse(state.active)
        }
        val (state, text) = paren(); state.reset()
        assertNull(state.closing(id, s(text), 300, ::ordinary))
    }
    @Test fun `both spacing repairs use range anchored Undo and Apply preserves later typing`() {
        val (p, before) = paren()
        val auto = p.closing(id, s(before), 300, ::ordinary)!!
        val (c, commaBefore) = comma()
        val manual = c.missingCommaSpace(id, s(commaBefore), "friend", 300, true)!!
        for (offer in listOf(auto, manual)) {
            val state = RecentWordCorrection()
            val word = RecentWordCorrection.Word(id, offer.original, offer.delimiter, offer.start,
                offer.context, "", 300, repairWindowMs = 5_000, grammar = true)
            val original = offer.context + offer.original + offer.delimiter
            state.begin(word)
            state.append("more", 400)
            assertNotNull(state.repair(id, s(original + "more"), offer.replacement, 500))
            state.applied(word, offer.replacement, 500)
            state.append(" text", 600)
            val fixed = offer.context + offer.replacement + offer.delimiter + "more text"
            val undo = state.undoEdit(id, s(fixed), 700)!!
            assertEquals(offer.original, undo.text)
            assertEquals((original + "more text").length, undo.caret)
        }
    }
    @Test fun `bounded runs and restarts cannot resurrect ownership`() {
        val (state, text) = paren("later ".repeat(20))
        assertFalse(state.active)
        assertNull(state.closing(id, s(text), 300, ::ordinary))
        val (c, commaText) = comma("hello", "friend")
        c.reset(); extend(c, commaText, " ")
        assertNull(c.missingCommaSpace(id, s("hello,friend "), "friend", 300, true))
    }

    private fun boundary(first: String, mark: String, tail: String, context: String = "",
        verified: Boolean = true): Pair<OwnedSpacing, String> {
        val state = OwnedSpacing()
        val manual = mark in setOf(":", ";")
        val initial = context + first + (if (manual) " " else "") + mark
        val anchor = SmallAccommodations.remember(id, s(context + first + " "), first, 100, true)
        state.punctuationTyped(id, s(initial), first, mark, 200, verified, anchor)
        return state to extend(state, initial, tail)
    }

    @Test fun `one fresh opening space is removed at the completed ordinary word`() {
        val (state, text) = paren(" hello ")
        val offer = state.completed(id, s(text), "hello", 300, true)!!
        assertEquals("( hello", offer.original)
        assertEquals("(hello", offer.replacement)
        assertEquals(" ", offer.delimiter)
        assertTrue(offer.automatic)
        assertEquals(0, offer.start)
        assertNull(state.completed(id, s(text), "hello", 300, true))
        state.reset() // The verified editor transaction resets all prior runs.
        state.openingApplied(id, offer, s("(hello "), 400)
        val rest = extend(state, "(hello ", "tomorrow ", now = 450)
        assertNotNull(state.closing(id, s(rest), 500, ::ordinary))
    }

    @Test fun `opening padding leaves labels emoticons nesting double spacing and unverified words literal`() {
        for ((tail, word) in listOf(" hello" to "hello", "  hello " to "hello", " a " to "a",
            " I " to "I", " x " to "x", " kg " to "kg", " fig " to "fig", " ref " to "ref",
            " hello  " to "hello", " hello\t" to "hello",
            " (hello " to "hello", " HELLO " to "HELLO", " hello/world " to "world")) {
            val (state, text) = paren(tail)
            assertNull(tail, state.completed(id, s(text), word, 300, true))
        }
        for (prefix in listOf(":(", ";(", "f(", "say :(")) {
            val state = OwnedSpacing(); state.opened(id, s(prefix), 100)
            val text = extend(state, prefix, " hello ")
            assertNull(prefix, state.completed(id, s(text), "hello", 300, true))
        }
        val (state, text) = paren(" hello ")
        assertNull(state.completed(id, s(text), "hello", 300, false))
        assertNull(OwnedSpacing().completed(id, s("( hello "), "hello", 300, true))
    }

    @Test fun `colon and semicolon repair waits for an owned confirming prose word`() {
        for ((first, mark) in listOf("note" to ":", "remember" to ":", "later" to ";")) {
            val (state, text) = boundary(first, mark, " bring ")
            val offer = state.completed(id, s(text), "bring", 300, true)!!
            assertEquals("$first $mark bring", offer.original)
            assertEquals("$first$mark bring", offer.replacement)
            assertTrue(offer.automatic)
            assertEquals(SmallAccommodations.Kind.COLON_SEMICOLON, offer.kind)
            assertFalse(state.active)
        }
        val (state, text) = boundary("note", ":", " bring")
        assertNull(state.completed(id, s(text), "bring", 300, true))
    }

    @Test fun `colon and semicolon leave faces labels numeric foreign and deliberate spacing alone`() {
        for ((first, mark, tail) in listOf(
            Triple("note", ":", "P "), Triple("note", ":", "D "), Triple("note", ":", ") "),
            Triple("note", ":", "bring "), Triple("note", ":", "  bring "), Triple("note", ":", "\tbring "),
            Triple("note", ":", " Bring "), Triple("note", ":", " b "), Triple("note", ":", " 30 "),
            Triple("10", ":", " bring "), Triple("Q", ":", " bring "), Triple("NAME", ":", " bring "),
            Triple("ratio", ":", " value "), Triple("Attention", ":", " demain "),
            Triple("note", ":", " danger "), Triple("attention", ";", " danger "),
            Triple("later", ";", "p "), Triple("later", ";", " hello/world "))) {
            val (state, text) = boundary(first, mark, tail)
            assertNull("$first $mark$tail", state.completed(id, s(text), tail.trim(), 300, true))
        }
        for (prefix in listOf("key= ", "#tag ", "@name ", "https://example.test ", "( ", "\"quote ")) {
            val (state, text) = boundary("note", ":", " bring ", prefix)
            assertNull(prefix, state.completed(id, s(text), "bring", 300, true))
        }
        val (state, text) = boundary("note", ":", " bring ", verified = false)
        assertNull(state.completed(id, s(text), "bring", 300, true))
    }

    @Test fun `missing sentence spacing is automatic only after a capitalized ordinary second word`() {
        for (mark in listOf("!", "?")) {
            val (state, text) = boundary("Really", mark, "Yes ")
            val offer = state.completed(id, s(text), "Yes", 300, true)!!
            assertEquals("Really${mark}Yes", offer.original)
            assertEquals("Really$mark Yes", offer.replacement)
            assertEquals(SmallAccommodations.Kind.SENTENCE_SPACE, offer.kind)
            assertTrue(offer.automatic)
        }
    }

    @Test fun `sentence spacing preserves punctuation runs queries names tags and lower-case ambiguity`() {
        for ((first, mark, tail) in listOf(
            Triple("Really", "?", "yes "), Triple("Really", "?", " Yes "), Triple("Really", "?", "!Yes "),
            Triple("Really", "!", "!Yes "), Triple("Really", "?", "Y "), Triple("Really", "?", "YES "),
            Triple("Really", "?", "Yes=foo "), Triple("Really", "?", "Yes_name "),
            Triple("Yahoo", "!", "Mail "), Triple("Panic", "!", "At "), Triple("Q", "?", "Answer "),
            Triple("search", "?", "query "), Triple("api", "?", "key=value "), Triple("what2", "?", "Yes "))) {
            val (state, text) = boundary(first, mark, tail)
            assertNull("$first$mark$tail", state.completed(id, s(text), tail.trim(), 300, true))
        }
        val (state, text) = boundary("Really", "?", "Yes ", verified = false)
        assertNull(state.completed(id, s(text), "Yes", 300, true))
    }

    @Test fun `new spacing rules reject stale or partial receipts and ownership resets`() {
        for (make in listOf<() -> Pair<OwnedSpacing, String>>(
            { paren(" hello ") }, { boundary("note", ":", " bring ") }, { boundary("Really", "?", "Hello ") })) {
            for (identity in listOf(id.copy(field = 9), id.copy(dictionary = 4), id.copy(policy = "old"))) {
                val (state, text) = make()
                assertNull(state.completed(identity, s(text), text.trim().takeLastWhile { it.isLetter() }, 300, true))
            }
            for (change in listOf<(VerifiedWordTransaction.Snapshot) -> VerifiedWordTransaction.Snapshot>(
                { it.copy(beforeComplete = false) }, { it.copy(after = "tail") },
                { it.copy(selection = EditorEditState.Selection(0, 0)) }, { it.copy(before = "other " + it.before) })) {
                val (state, text) = make()
                assertNull(state.completed(id, change(s(text)), text.trim().takeLastWhile { it.isLetter() }, 300, true))
            }
            val (stale, text) = make()
            assertNull(stale.completed(id, s(text), text.trim().takeLastWhile { it.isLetter() }, 10_201, true))
            val (reset, other) = make(); reset.reset()
            assertNull(reset.completed(id, s(other), other.trim().takeLastWhile { it.isLetter() }, 300, true))
        }
    }

    @Test fun `every new automatic range has precise Undo that keeps following typing`() {
        for ((state, text) in listOf(paren(" hello "), boundary("note", ":", " bring "), boundary("Really", "?", "Hello "))) {
            val offer = state.completed(id, s(text), text.trim().takeLastWhile { it.isLetter() }, 300, true)!!
            val recent = RecentWordCorrection()
            val word = RecentWordCorrection.Word(id, offer.original, offer.delimiter, offer.start,
                offer.context, "", 300, repairWindowMs = 5_000, grammar = true)
            recent.begin(word)
            assertNotNull(recent.repair(id, s(text), offer.replacement, 400))
            recent.applied(word, offer.replacement, 400)
            recent.append("more text", 500)
            val fixed = offer.context + offer.replacement + offer.delimiter + "more text"
            val undo = recent.undoEdit(id, s(fixed), 600)!!
            assertEquals(offer.original, undo.text)
            assertEquals((text + "more text").length, undo.caret)
            assertFalse(SmallAccommodations.loggable(offer.original, offer.replacement))
        }
    }

    @Test fun `rejected ignored and missing effects cannot certify new spacing or trigger retries`() {
        for (make in listOf<() -> Pair<OwnedSpacing, String>>(
            { paren(" hello ") }, { boundary("note", ":", " bring ") }, { boundary("Really", "?", "Hello ") })) {
            for (mode in listOf("reject", "ignore", "missing")) {
                val (state, text) = make()
                val offer = state.completed(id, s(text), text.trim().takeLastWhile { it.isLetter() }, 300, true)!!
                val edit = WordEdits.Replacement(offer.original.length + 1, 0, offer.replacement + " ")
                var sent = 0; var receipts = 0
                assertNull(VerifiedWordTransaction.apply(edit, s(text), direct = true,
                    read = { if (mode == "missing") null else s(text) },
                    range = { _, _, _ -> sent++; mode != "reject" },
                    select = { _, _ -> fail("unexpected selection retry"); false },
                    commit = { fail("unexpected commit retry"); false }, verified = { receipts++ }))
                assertEquals(1, sent)
                assertEquals(0, receipts)
            }
        }
    }
}
