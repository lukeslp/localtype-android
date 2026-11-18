package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Remember sites I type as the iPhone keyboard gets it ([TypingEngine.Sites]), held to the Android keyboard's own
 * rules: [RememberedDomains], [DomainCodec], [TypedRun], [Expansions.domain] and the service's `rememberTypedHost`.
 * The iPhone's copy lives in the engine facade because it may not reach the app module; these cases run both.
 */
class TypingEngineSitesTest {
    private val tldText = File("src/main/assets/tlds.txt").readText()
    private val tlds = DomainHosts.parseTlds(tldText.lineSequence())

    private fun sites(saved: String? = null) = TypingEngine.Sites(tldText, saved)

    private class FileStore(var file: String = "") : RememberedDomains.Store {
        override fun load() = DomainCodec.decode(file)
        override fun save(entries: List<RememberedDomains.Entry>) {
            file = DomainCodec.encode(entries)
        }
    }

    /** Types [text] one key at a time after [before], as the keyboard reports each key. */
    private fun type(sites: TypingEngine.Sites, before: String, text: String): String {
        var field = before
        for (c in text) {
            sites.appended(field, c.toString())
            field += c
        }
        return field
    }

    /** [host] typed in full in an empty field and ended with Space or Return on [day]. */
    private fun typeWhole(sites: TypingEngine.Sites, host: String, day: Long): Boolean =
        sites.finished(type(sites, "", host), null, day)

    private fun offer(sites: TypingEngine.Sites, run: String, address: Boolean = false, mail: Boolean = false, completes: Boolean = false) =
        sites.offer(run, null, address, mail) { completes }

    /** The runs of ExpansionsTest and DomainHostsTest's typing cases, and a few around them. */
    private val runs = listOf(
        "d", "da", "dat", "data", "datapoems.", "datapoems.i", "Datapoems.i", "datapoems.io", "https://www.Datapoe", "luke@gm", "luk", "lukesl",
        "lukex", "example.com", "the", "theverge.", "zzz", "luke@p", "https://lu", "https://l", "luke@", "@lu", "@luk", "luke@l", "luke@g",
        "@g", "gma", "https://www.exa", "example.", "(exa", "example.com/", "example.com/pa", "example.com:80", "example.com?x", "ftp://exa",
        "", "https://", "www.", "ex_ample", "exa!", "a..b", "-ab.c", "@exa", "(@exa", "pr", "pro", "luke@pro", "www.da", "LUKE@L",
    )

    @Test
    fun `the list is the android list after the same typing, byte for byte`() {
        val store = FileStore()
        val android = RememberedDomains(store)
        val phone = sites()
        fun typed(host: String, day: Long) {
            android.typed(host, day)
            assertTrue(host, typeWhole(phone, host, day))
            assertEquals(store.file, phone.text())
        }
        fun used(host: String, day: Long) {
            android.used(host, day)
            phone.taken(host, day)
            // Android writes only when something changed; a first use with nothing yet written reads as the bare header.
            assertEquals(store.file.ifEmpty { DomainCodec.encode(emptyList()) }, phone.text())
        }
        fun sameOffers() {
            for (run in runs) for (field in Expansions.Field.entries) for (completes in listOf(false, true)) {
                val expected = Expansions.domain(run, android.offered, field) { completes }
                val got = offer(phone, run, address = field == Expansions.Field.ADDRESS, mail = field == Expansions.Field.MAIL, completes = completes)
                assertEquals("$run in $field, completes $completes", expected?.host, got?.host)
                assertEquals("$run in $field, completes $completes", expected?.text, got?.text)
            }
            assertEquals(android.entries.size, phone.count)
            assertEquals(android.offered.size, phone.offeredCount)
        }

        typed("datapoems.io", 100)
        assertEquals(0, phone.offeredCount)
        sameOffers()
        typed("datapoems.io", 101)
        assertEquals(1, phone.offeredCount)
        sameOffers()
        // The order: most typed, then most recent, then by name.
        for ((host, times, day) in listOf(Triple("lukesteuber.com", 2, 5L), Triple("lukeslp.dev", 2, 5L), Triple("proton.me", 5, 1L),
            Triple("protein.org", 2, 9L), Triple("theverge.com", 2, 9L), Triple("example.com", 3, 2L), Triple("gmail.com", 2, 3L))) {
            repeat(times) { typed(host, day) }
        }
        sameOffers()
        assertEquals("proton.me", offer(phone, "pr", address = true)!!.host)
        assertEquals("lukeslp.dev", offer(phone, "luke@l")!!.host)
        // Taking one from the strip moves its day and never its count; an unknown host and the same day change nothing.
        used("lukesteuber.com", 50)
        used("lukesteuber.com", 50)
        used("never.there", 50)
        sameOffers()
        assertEquals("lukesteuber.com", offer(phone, "luke@l")!!.host)

        // A full list drops the host used longest ago, then the least typed, then the last by name.
        val full = RememberedDomains(FileStore().also { fullStore ->
            val list = RememberedDomains(fullStore)
            repeat(RememberedDomains.MAX) { list.typed("site$it.com", 10L + it / 4) }
        })
        val fullPhone = sites()
        repeat(RememberedDomains.MAX) { assertTrue(typeWhole(fullPhone, "site$it.com", 10L + it / 4)) }
        full.used("site0.com", 500)
        fullPhone.taken("site0.com", 500)
        for ((host, day) in listOf("new.org" to 501L, "site2.com" to 502L, "newer.org" to 503L, "newest.org" to 503L)) {
            full.typed(host, day)
            assertTrue(typeWhole(fullPhone, host, day))
        }
        assertEquals(DomainCodec.encode(DomainCodec.decode(fullPhone.text())), fullPhone.text())
        assertEquals(full.entries, RememberedDomains(FileStore(fullPhone.text())).entries)
        assertEquals(RememberedDomains.MAX, fullPhone.count)

        android.clear()
        phone.forget()
        assertEquals(store.file, phone.text())
        assertEquals("localtype-domains 1\n", phone.text())
        sameOffers()
    }

    @Test
    fun `the run is android's typed run through the same keys`() {
        /** One step of a script: both sides get it, and [field] is the text before the caret after it. */
        class Step(val field: String, val both: (TypedRun, TypingEngine.Sites) -> Unit)
        fun keys(before: String, text: String): List<Step> = text.indices.map { i ->
            Step(before + text.substring(0, i + 1)) { run, sites ->
                run.appended(before + text.substring(0, i), text[i].toString())
                sites.appended(before + text.substring(0, i), text[i].toString())
            }
        }
        fun delete(before: String, chars: Int, after: String = before.dropLast(chars)) = Step(after) { run, sites ->
            run.deleted(before, chars)
            sites.deleted(before, chars)
        }
        fun reset(field: String) = Step(field) { run, sites -> run.reset(); sites.reset() }
        fun append(before: String?, text: String, field: String) = Step(field) { run, sites -> run.appended(before, text); sites.appended(before, text) }
        /** The caret is somewhere: the run is asked about [field] without any edit. */
        fun at(field: String) = Step(field) { _, _ -> }

        val scripts: List<List<Step>> = listOf(
            keys("", "datapoems.io"),
            keys("", "datapoems.io") + keys("datapoems.io", " see lukesteuber.com"),
            keys("data", "poems.io"),
            keys("data", "poems.io") + keys("datapoems.io ", "example.com"),
            keys("", "data") + reset("datapoems") + keys("datapoems", ".io"),
            keys("", "data") + reset("datapoems") + keys("datapoems", ".io") + keys("datapoems.io", " again.org"),
            keys("go to ", "datapeo") + delete("go to datapeo", 2) + keys("go to datap", "oems.io"),
            keys("go to ", "datapoems.io") + delete("go to datapoems.io", 40, after = "go "),
            keys("", "example.com") + delete("exa", 1, after = "example.com"),
            keys("", "example.com") + delete("exa", 1, after = "ex"),
            keys("", "ab") + delete("ab", 2) + append("", "c", "c"),
            keys("", "ab") + delete("ab", 0, after = "ab"),
            keys("", "example.com") + at("another example.org"),
            keys("", "example.com") + at("xexample.com"),
            keys("", "example.com") + at(""),
            keys("", "example.com") + at("then example.com"),
            keys("", "example") + append(null, ".", "example.") + keys("example.", "com"),
            keys("", "a".repeat(261)),
            keys("", "a".repeat(250) + ".com"),
            listOf(append("", "see example", "see example"), append("see example", ".com", "see example.com")),
            listOf(append("", "see example", "see example"), append("see example", ". ", "see example. "), append("see example. ", "x", "see example. x")) +
                keys("see example. x", "y.org"),
        )
        for ((n, script) in scripts.withIndex()) {
            // After every step, a fresh pair replayed that far: Space or Return counts a host exactly when Android's run holds one.
            for (upTo in 1..script.size) {
                val run = TypedRun()
                val phone = sites()
                script.take(upTo).forEach { it.both(run, phone) }
                val field = script[upTo - 1].field
                val expected = run.current(field)?.takeIf { run.mayBeAddress }?.let { DomainHosts.host(it, tlds) }
                assertEquals("script $n step $upTo at \"$field\"", expected != null, phone.finished(field, null, 7))
                assertEquals("script $n step $upTo", if (expected == null) "localtype-domains 1\n" else "localtype-domains 1\n$expected\t1\t7\n", phone.text())
            }
        }
        // Somewhere in those scripts a host was counted and somewhere it was refused.
        assertTrue(typeWhole(sites(), "datapoems.io", 1))
        assertFalse(sites().let { it.finished(type(it, "data", "poems.io"), null, 1) })

        // keep: a caret that moved gives the run up for good; one that didn't keeps it.
        val moved = sites()
        val field = type(moved, "", "example.com")
        moved.keep(field)
        moved.keep("exa")
        assertFalse(moved.finished(field, null, 1))
        val stayed = sites()
        stayed.keep(type(stayed, "see ", "example.com"))
        assertTrue(stayed.finished("see example.com", null, 1))
    }

    @Test
    fun `a site typed whole is held once and offered from the second time`() {
        val phone = sites()
        assertFalse(phone.changed)
        val field = type(phone, "", "datapoems.io")
        assertTrue(phone.finished(field, null, 100))
        assertTrue(phone.changed)
        // A second Return on a search that keeps its text is not a second typing.
        assertFalse(phone.finished(field, null, 100))
        assertEquals("localtype-domains 1\ndatapoems.io\t1\t100\n", phone.text())
        phone.saved()
        assertFalse(phone.changed)
        assertNull(offer(phone, "da", address = true))
        assertTrue(phone.finished(type(phone, "$field ", "DataPoems.io"), "", 101))
        val offered = offer(phone, "Da", address = true)!!
        assertEquals("datapoems.io", offered.host)
        // What is appended keeps the capitals already typed.
        assertEquals("tapoems.io", offered.text)
        phone.taken("datapoems.io", 105)
        assertEquals("localtype-domains 1\ndatapoems.io\t2\t105\n", phone.text())

        // Text standing right after the caret is more of the same token; white space is not.
        val middle = sites()
        assertFalse(middle.finished(type(middle, "", "example.com"), "/path", 1))
        assertTrue(middle.finished("example.com", " and more", 1))

        // Only the host is kept.
        val whole = sites()
        assertTrue(typeWhole(whole, "http://user:secret@example.com:8080/private/path", 3))
        assertTrue(typeWhole(whole, "Someone@Example.co.uk", 3))
        assertTrue(whole.finished(type(whole, "see ", "(www.lukesteuber.com)."), null, 3))
        assertEquals("localtype-domains 1\nexample.com\t1\t3\nexample.co.uk\t1\t3\nlukesteuber.com\t1\t3\n", whole.text())

        // Ordinary text with a dot in it is never kept, and neither is a word.
        val prose = sites()
        for (run in listOf("e.g.", "file.txt", "a.com", "3.14", "end.The", "192.168.1.1", "ftp://example.com", "@example.com", "hello", "wait...")) {
            assertFalse(run, typeWhole(prose, run, 1))
        }
        assertEquals(0, prose.count)
        assertFalse(prose.changed)

        // Without the list of endings nothing is a site.
        val bare = TypingEngine.Sites("", null)
        assertFalse(typeWhole(bare, "datapoems.io", 1))
    }

    @Test
    fun `nothing is offered where the address goes on after the caret`() {
        val phone = sites("localtype-domains 1\nexample.com\t2\t1\n")
        assertNull(phone.offer("exa", ".com", true, false) { false })
        assertNull(phone.offer("exa", "mple", true, false) { false })
        for (after in listOf(null, "", " more", ")", "\nnext", ", and")) {
            assertEquals(after, "mple.com", phone.offer("exa", after, true, false) { false }?.text)
            assertEquals(after, Expansions.hostEndsAt(after.orEmpty()), true)
        }
        assertFalse(Expansions.hostEndsAt(".com"))
        // The word list is asked only when a site fits and nothing else has settled it.
        var asked = 0
        phone.offer("zzz", null, false, false) { asked++; true }
        phone.offer("example.", null, false, false) { asked++; true }
        phone.offer("exa", null, true, false) { asked++; true }
        phone.offer("luke@e", null, false, true) { asked++; true }
        assertEquals(0, asked)
        assertNull(phone.offer("exa", null, false, false) { asked++; true })
        assertEquals(1, asked)
        // Only the text since the last white space is read.
        assertEquals("mple.com", phone.offer("go to exa", null, false, false) { false }?.text)
        assertNull(phone.offer("exa ", null, true, false) { false })
    }

    @Test
    fun `the saved text reads as the android file does`() {
        val damaged = "localtype-domains 1\ngood.com\t3\t7\nBad Host\t2\t7\nno-tld\t2\t7\nshort.io\t2\nx.org\tmany\t7\ngood.com\t9\t9\nhttps://x.com/path\t2\t7\nzero.net\t0\t7\n\nlast.dev\t2\t8\r\n"
        val phone = sites(damaged)
        assertEquals(DomainCodec.encode(RememberedDomains(FileStore(damaged)).let { list -> DomainCodec.decode(damaged).filter { it in list.entries } }), phone.text())
        assertEquals("localtype-domains 1\ngood.com\t3\t7\nlast.dev\t2\t8\n", phone.text())
        assertFalse(phone.changed)
        assertEquals(0, sites("something else\ngood.com\t3\t7\n").count)
        assertEquals(0, sites("").count)
        assertEquals(0, sites(null).count)
        // More than the list holds, and a count past its cap.
        val many = buildString {
            append("localtype-domains 1\n")
            repeat(70) { append("site$it.com\t${if (it == 0) 123456 else 2}\t$it\n") }
        }
        assertEquals(RememberedDomains(FileStore(many)).entries.size, sites(many).count)
        assertTrue(sites(many).text().startsWith("localtype-domains 1\nsite0.com\t9999\t0\n"))
        // What it writes, it reads back the same.
        assertEquals(phone.text(), sites(phone.text()).text())
        // Forgetting an empty list changes nothing; forgetting a held one leaves the bare header to write.
        val empty = sites()
        empty.forget()
        assertFalse(empty.changed)
        phone.forget()
        assertTrue(phone.changed)
        assertEquals("localtype-domains 1\n", phone.text())
        assertNull(offer(phone, "goo", address = true))
    }

    @Test
    fun `completes is the word list's answer for the start of a word`() {
        val language = LanguageProfile.ENGLISH
        fun asset(name: String) = File("src/main/assets/$name")
        val engine = TypingEngine(
            language = language,
            words = asset(language.wordsAsset).readText(),
            swears = asset(language.profanityAsset).readText(),
            lexicon = asset(language.lexiconAsset).readText(),
            modern = asset("words_en_modern.txt").readText(),
            prior = null,
            journal = null,
            suggestSwears = true,
        )
        val dictionary = WordDictionary.parse(
            asset(language.wordsAsset).readText().lineSequence() + asset("words_en_modern.txt").readText().lineSequence() +
                asset(language.profanityAsset).readText().lineSequence(),
            language,
        )
        for (prefix in listOf("the", "The", "dat", "zzz", "datap", "")) {
            assertEquals(prefix, prefix.isNotEmpty() && dictionary.completions(prefix.lowercase(), 1).isNotEmpty(), engine.completes(prefix))
        }
        assertTrue(engine.completes("the"))
        assertFalse(engine.completes("zzz"))
        assertFalse(engine.completes(""))
    }
}
