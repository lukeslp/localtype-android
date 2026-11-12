package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** How each list takes in a copy made on another phone: what is here stays, and reading a copy twice changes nothing. */
class CopyMergeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    // Learned pairs

    @Test
    fun `pairs keep the larger count and a second reading changes nothing`() {
        val state = LearnedPairState(listOf(Triple("good", "morning", 5), Triple("good", "night", 2)))
        val copy = listOf(Triple("good", "morning", 3), Triple("good", "night", 9), Triple("see", "you", 4), Triple("see", "you", 0), Triple("", "x", 2))
        state.merge(copy)
        val expected = setOf(Triple("good", "morning", 5), Triple("good", "night", 9), Triple("see", "you", 4))
        assertEquals(expected, state.entries().toSet())
        state.merge(copy)
        assertEquals(expected, state.entries().toSet())
        state.merge(listOf(Triple("good", "night", 5_000)))
        assertTrue(Triple("good", "night", LearnedPairState.MAX_COUNT) in state.entries())
    }

    @Test
    fun `the added-words row is never taken from a list of pairs and the one here is kept`() {
        val state = LearnedPairState(listOf(Triple(AddedWords.CONTEXT, "zarf", 1)))
        state.merge(listOf(Triple(AddedWords.CONTEXT, "slipped", 1), Triple("a", "zarf", 2)))
        assertEquals(listOf("zarf"), AddedWords.words(state.entries()))
    }

    @Test
    fun `a full row here loses no follower to a copy, and a row with room takes the copy's strongest`() {
        val full = (0 until LearnedPairState.MAX_FOLLOWERS).map { Triple("the", "here$it", 1) }
        val state = LearnedPairState(full + Triple("a", "cat", 2))
        state.merge((0 until LearnedPairState.MAX_FOLLOWERS).map { Triple("the", "copy$it", 90) } + Triple("the", "here0", 40) +
            (0 until LearnedPairState.MAX_FOLLOWERS + 5).map { Triple("a", "copy$it", it + 1) })
        val the = state.entries().filter { it.first == "the" }
        assertEquals(full.map { it.second }.toSet(), the.map { it.second }.toSet())
        assertTrue(Triple("the", "here0", 40) in the)
        val a = state.entries().filter { it.first == "a" }
        assertEquals(LearnedPairState.MAX_FOLLOWERS, a.size)
        assertTrue(Triple("a", "cat", 2) in a)
        // The copy offered counts 1 to 69; the 63 places left went to the highest.
        assertEquals((7..69).toSet(), a.filter { it.second != "cat" }.map { it.third }.toSet())
    }

    @Test
    fun `at the bound on previous words the copy's rows leave before this phone's`() {
        val here = (0 until LearnedPairState.MAX_PREVIOUS_WORDS - 1).map { Triple("here$it", "word", 1) }
        val state = LearnedPairState(here)
        state.merge(listOf(Triple("copy1", "word", 9), Triple("copy2", "word", 9), Triple("copy3", "word", 9)))
        val previous = state.entries().map { it.first }.toSet()
        assertEquals(LearnedPairState.MAX_PREVIOUS_WORDS, previous.size)
        assertTrue(previous.containsAll(here.map { it.first }))
        // And a word typed next still pushes out a row from the copy first.
        state.record("typed", "now")
        assertTrue(state.entries().map { it.first }.toSet().containsAll(here.map { it.first } + "typed"))
    }

    @Test
    fun `a merge made before the first load is kept, and the keyboard's baseline has it`() {
        val queued = ArrayList<LearnedPairBuffer.Mutation>()
        val buffer = LearnedPairBuffer(queued::add)
        buffer.merge(listOf(Triple("see", "you", 4)))
        assertEquals(listOf(Triple("see", "you", 4)), buffer.entries())
        val baseline = buffer.publish(listOf(Triple("see", "you", 1), Triple("good", "work", 7)))
        assertEquals(setOf(Triple("see", "you", 4), Triple("good", "work", 7)), buffer.entries().toSet())
        assertEquals(buffer.entries().toSet(), baseline.toSet())
        assertEquals(1, queued.size)
    }

    @Test
    fun `the journal file writes a merge as one compact file and reads it back`() {
        val file = File(temporary.newFolder(), "learned_pairs.bin")
        val journal = LearnedPairJournalFile(file)
        journal.load()
        journal.record("good", "morning")
        journal.merge(listOf(Triple("good", "morning", 6), Triple("see", "you", 2)))
        val expected = setOf(Triple("good", "morning", 6), Triple("see", "you", 2))
        assertEquals(expected, LearnedPairJournalFile(file).load().entries().toSet())
        // One record for each pair: the merge rewrote the file instead of appending to it.
        assertEquals(2, LearnedPairJournal.decode(file.readBytes()).records)
        journal.record("see", "you")
        assertTrue(Triple("see", "you", 3) in LearnedPairJournalFile(file).load().entries())
    }

    @Test
    fun `a merge after a forget does not bring the forgotten words back`() {
        val file = File(temporary.newFolder(), "learned_pairs.bin")
        val journal = LearnedPairJournalFile(file)
        journal.load()
        journal.record("old", "secret")
        journal.clear()
        journal.merge(listOf(Triple("see", "you", 2)))
        assertEquals(listOf(Triple("see", "you", 2)), LearnedPairJournalFile(file).load().entries())
    }

    @Test
    fun `a learned word from a copy must be one typing could have taught`() {
        val english = LanguageProfile.ENGLISH
        fun ok(word: String, follower: Boolean = true) = KeyboardCopy.learnedWord(word, english, follower)
        assertTrue(ok("hello") && ok("don't") && ok("naïve") && ok("a") && ok("i"))
        assertFalse(ok("Hello"))
        assertFalse(ok("has a digit2"))
        assertFalse(ok("https://example.org"))
        assertFalse(ok("tab\there"))
        assertFalse(ok("abc123"))
        assertFalse(ok(""))
        assertFalse(ok("x".repeat(200)))
        assertFalse(ok(AdaptiveNgramModel.START))
        assertFalse(ok(AddedWords.CONTEXT))
        // A single letter is offered only when the language has it as a word; as a previous word it is fine.
        assertFalse(ok("x"))
        assertTrue(ok("x", follower = false))
        assertTrue(KeyboardCopy.learnedWord("می\u200Cخواهم", LanguageProfile.PERSIAN, true))
        // No bundled list may hold a slur, and neither may a copy: the first entry of the shared digests is refused.
        val listed = java.io.File("src/main/assets/words_en_profanity.txt").readLines().map { it.substringBefore('\t').trim() }.filter { it.isNotEmpty() }
        assertTrue(listed.none { SuggestionWordRules.isExcluded(it, english) } || listed.any { !ok(it) })
    }

    // Personal suffix learning

    private fun entry(context: List<String>, word: String, count: Int, explicit: Int = 0, observed: Long = 1) =
        PersonalLearningState.Entry(context, word, count, explicit, observed)

    @Test
    fun `evidence keeps the larger counts and this phone's recency, and a second reading changes nothing`() {
        val state = PersonalLearningState()
        repeat(2) { state.record(listOf("see", "you"), "soon", LearningOrigin.TYPED) }
        val before = state.entries().first { it.context == listOf("see", "you") }
        val copy = listOf(
            entry(listOf("see", "you"), "soon", 5, explicit = 1, observed = 900),
            entry(listOf("at"), "home", 3, observed = 901),
            entry(listOf("one", "two", "three", "four"), "five", 3),
            entry(listOf("at"), "x", 3),
        )
        state.merge(copy)
        val after = state.entries()
        val soon = after.first { it.context == listOf("see", "you") && it.word == "soon" }
        assertEquals(5, soon.observations)
        assertEquals(1, soon.explicitObservations)
        assertEquals(before.lastObserved, soon.lastObserved)
        // New to this phone: taken in as the oldest evidence here.
        assertEquals(0L, after.first { it.word == "home" }.lastObserved)
        assertFalse(after.any { it.word == "five" || it.word == "x" })
        state.merge(copy)
        assertEquals(after, state.entries())
        assertEquals("soon", state.followers(listOf("see", "you")).first().word)
    }

    @Test
    fun `on a phone that has learned nothing the copy's order is kept, but never its clock`() {
        val state = PersonalLearningState()
        state.merge(listOf(entry(listOf("at"), "work", 3, observed = Long.MAX_VALUE), entry(listOf("at"), "home", 3, observed = 7)))
        assertEquals(listOf("work", "home"), state.followers(listOf("at")).map { it.word })
        assertEquals(listOf(1L, 2L), state.entries().map { it.lastObserved }.sorted())
        // Later typing here is newer than anything the copy brought, and stays tellable from it.
        state.record(listOf("at"), "last", LearningOrigin.SUGGESTION_ACCEPT)
        assertEquals(3L, state.entries().first { it.word == "last" }.lastObserved)
    }

    @Test
    fun `a full context here loses no follower to a copy`() {
        val state = PersonalLearningState()
        val words = (1..8).map { "word${'a' + it}" }
        words.forEach { state.record(listOf("at"), it, LearningOrigin.TYPED) }
        state.merge((1..8).map { entry(listOf("at"), "copy${'a' + it}", 50, explicit = 50) } + entry(listOf("in"), "copyx", 4))
        assertEquals(words.toSet(), state.entries().filter { it.context == listOf("at") }.map { it.word }.toSet())
        assertEquals(listOf("copyx"), state.entries().filter { it.context == listOf("in") }.map { it.word })
    }

    // Saved text

    private class SavedStore(var entries: List<SavedTexts.Entry> = emptyList()) : SavedTexts.Store {
        var writes = 0
        override fun load() = entries
        override fun save(entries: List<SavedTexts.Entry>) {
            this.entries = entries
            writes++
        }
    }

    @Test
    fun `saved text from a copy goes below what is here, with a shortcut only when the word is free`() {
        val store = SavedStore(listOf(SavedTexts.Entry("my address", "addr"), SavedTexts.Entry("plain")))
        val texts = SavedTexts(store)
        val added = texts.merge(
            listOf(
                SavedTexts.Entry("plain", "pl"),
                SavedTexts.Entry("other address", "ADDR"),
                SavedTexts.Entry("signature", " Sig "),
                SavedTexts.Entry("   "),
                SavedTexts.Entry("bad code", "has a digit2"),
            ),
        )
        assertEquals(3, added)
        assertEquals(listOf("my address", "plain", "other address", "signature", "bad code"), texts.items)
        assertEquals(mapOf("my address" to "addr", "signature" to "sig"), texts.codes)
        assertEquals(1, store.writes)
        assertEquals(0, texts.merge(listOf(SavedTexts.Entry("signature", "sig"))))
        assertEquals(1, store.writes)
    }

    @Test
    fun `saved text stops at the limit and never pushes out an item already here`() {
        val here = (1..SavedTexts.MAX - 1).map { SavedTexts.Entry("here $it") }
        val texts = SavedTexts(SavedStore(here))
        assertEquals(1, texts.merge(listOf(SavedTexts.Entry("copy a"), SavedTexts.Entry("copy b"))))
        assertEquals(here.map { it.text } + "copy a", texts.items)
    }

    // Remembered sites

    private class SiteStore : RememberedDomains.Store {
        var entries: List<RememberedDomains.Entry> = emptyList()
        var writes = 0
        override fun load() = entries
        override fun save(entries: List<RememberedDomains.Entry>) {
            this.entries = entries
            writes++
        }
    }

    @Test
    fun `sites keep the larger count and later day, and only real hosts come in`() {
        val store = SiteStore()
        val domains = RememberedDomains(store)
        domains.typed("datapoems.io", 100)
        val writes = store.writes
        val copy = listOf(
            RememberedDomains.Entry("datapoems.io", 4, 90),
            RememberedDomains.Entry("example.org", 2, 95),
            RememberedDomains.Entry("Not A Host", 2, 95),
            RememberedDomains.Entry("javascript:alert(1)", 2, 95),
            RememberedDomains.Entry("zero.org", 0, 95),
        )
        val endings = setOf("io", "org")
        assertEquals(1, domains.merge(copy + RememberedDomains.Entry("typed.zz9", 9_999, 95), today = 100, endings = endings))
        assertEquals(setOf(RememberedDomains.Entry("datapoems.io", 4, 100), RememberedDomains.Entry("example.org", 2, 95)), domains.entries.toSet())
        assertEquals(writes + 1, store.writes)
        assertEquals(0, domains.merge(copy, today = 100, endings = endings))
        assertEquals(writes + 1, store.writes)
        // A day in the future counts as today, so it cannot outlast what is typed here.
        domains.merge(listOf(RememberedDomains.Entry("later.org", 2, 90_000), RememberedDomains.Entry("example.org", 2, 90_000)), today = 100, endings = endings)
        assertEquals(setOf(100L), domains.entries.filter { it.host != "datapoems.io" }.map { it.day }.toSet())
    }

    @Test
    fun `a full list of sites takes no new host from a copy`() {
        val domains = RememberedDomains(SiteStore())
        repeat(RememberedDomains.MAX) { domains.typed("site$it.org", 100) }
        assertEquals(0, domains.merge(listOf(RememberedDomains.Entry("extra.org", 50, 200)), today = 300, endings = setOf("org")))
        assertFalse(domains.entries.any { it.host == "extra.org" })
    }

    // Settings

    @Test
    fun `every key in the settings file either travels in a copy or is named as staying`() {
        val source = File("src/main/java/dev/localtype/keyboard/KeyboardSettings.kt").readText()
        val keys = Regex("""const val ([A-Z_]+) = "([a-z_]+)"""").findAll(source).map { it.groupValues[1] to it.groupValues[2] }
            .filter { (name, _) -> name != "PREFS" && name != "GESTURE_PREFIX" }.map { it.second }.toSet()
        assertTrue("found ${keys.size} keys", keys.size >= 55)
        val classed = KeyboardSettings.portableKeys + KeyboardSettings.stayingKeys
        assertEquals("neither travels nor stays", emptySet<String>(), keys - classed)
        assertEquals("both travels and stays", emptySet<String>(), KeyboardSettings.portableKeys intersect KeyboardSettings.stayingKeys)
        // The opt-in log is switched on by hand on each phone, and a model set up there is not set up here.
        assertTrue(KeyboardSettings.stayingKeys.containsAll(listOf("prediction_log", "model_set_up", "learned_generation")))
        assertTrue(KeyboardSettings.portableKeys.containsAll(listOf("gesture_left", "gesture_down", "language_ar", "language_nv")))
    }

    @Test
    fun `only settings the person has set are saved, and only ones a setting could hold are read`() {
        val stored = mapOf(
            "autocorrect" to false, "bottom_clearance" to 8, "theme" to "DARK", "tools_row" to "voice,arrows",
            "prediction_log" to true, "learned_generation" to 14, "update_latest_name" to "0.5.60",
            "number_row" to "yes", "strip_height" to "<script>", "unknown_key" to true,
        )
        val saved = KeyboardSettings.portableFrom(stored)
        assertEquals(
            listOf(
                KeyboardCopy.Setting('b', "autocorrect", "0"), KeyboardCopy.Setting('i', "bottom_clearance", "8"),
                KeyboardCopy.Setting('s', "theme", "DARK"), KeyboardCopy.Setting('s', "tools_row", "voice,arrows"),
            ),
            saved,
        )
        assertEquals(listOf(false, 8, "DARK", "voice,arrows"), saved.map(KeyboardSettings::portableValue))
        fun value(type: Char, key: String, value: String) = KeyboardSettings.portableValue(KeyboardCopy.Setting(type, key, value))
        assertEquals(true, value('b', "number_row", "1"))
        assertNull(value('b', "number_row", "true"))
        assertNull(value('b', "prediction_log", "1"))
        assertNull(value('b', "setup_complete", "1"))
        assertNull(value('s', "autocorrect", "1"))
        assertNull(value('i', "bottom_clearance", "4000"))
        assertNull(value('i', "dwell_ms", "-1"))
        assertNull(value('s', "theme", "a b"))
        assertNull(value('s', "theme", "x".repeat(300)))
        assertNull(value('b', "made_up", "1"))
    }
}
