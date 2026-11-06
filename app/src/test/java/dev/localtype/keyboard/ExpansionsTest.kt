package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpansionsTest {
    private class Memory : SavedTexts.Store {
        override fun load(): List<SavedTexts.Entry> = emptyList()
        override fun save(entries: List<SavedTexts.Entry>) = Unit
    }

    private fun hosts(vararg names: String) = names.mapIndexed { n, host -> RememberedDomains.Entry(host, 9 - n, 1) }

    @Test
    fun `a shortcut typed exactly offers its saved text, replacing the word`() {
        val saved = SavedTexts(Memory())
        saved.save("221B Baker Street, London NW1 6XE, United Kingdom")
        saved.setCode("221B Baker Street, London NW1 6XE, United Kingdom", "addr")
        val offer = Expansions.shortcut("Addr", saved)!!
        assertEquals(Expansions.Kind.SHORTCUT, offer.kind)
        assertEquals(4, offer.replace)
        assertEquals("221B Baker Street, London NW1 6XE, United Kingdom", offer.text)
        assertEquals("221B Baker Stree…", offer.label)
        assertNull(Expansions.shortcut("add", saved))
        assertNull(Expansions.shortcut("", saved))
        assertNull(Expansions.shortcut("addr", null))
    }

    private fun offer(run: String, list: List<RememberedDomains.Entry>, field: Expansions.Field = Expansions.Field.TEXT, completes: Boolean = false) =
        Expansions.domain(run, list, field) { completes }

    @Test
    fun `a host is finished by appending what is missing`() {
        val offer = offer("data", hosts("datapoems.io"), Expansions.Field.ADDRESS, completes = true)!!
        assertEquals(Expansions.Offer(Expansions.Kind.DOMAIN, "datapoems.io", 0, "poems.io", "datapoems.io"), offer)
        assertEquals("ms.io", offer("https://www.Datapoe", hosts("datapoems.io"), Expansions.Field.ADDRESS)!!.text)
        assertEquals("ail.com", offer("luke@gm", hosts("gmail.com"))!!.text)
    }

    @Test
    fun `the first host in the list that fits is the one offered`() {
        val list = hosts("lukesteuber.com", "lukeslp.dev", "example.com")
        assertEquals("lukesteuber.com", offer("luk", list, Expansions.Field.ADDRESS)!!.host)
        assertEquals("lukeslp.dev", offer("lukesl", list, Expansions.Field.ADDRESS)!!.host)
        assertNull(offer("lukex", list, Expansions.Field.ADDRESS))
        // A host already typed in full has nothing left to offer.
        assertNull(offer("example.com", list, Expansions.Field.ADDRESS))
    }

    @Test
    fun `in an address bar two characters are enough and in ordinary text it takes three`() {
        val list = hosts("datapoems.io")
        assertNull(offer("d", list, Expansions.Field.ADDRESS))
        assertEquals("datapoems.io", offer("da", list, Expansions.Field.ADDRESS)!!.host)
        assertNull(offer("da", list))
        assertEquals("datapoems.io", offer("dat", list)!!.host)
    }

    @Test
    fun `in ordinary text a host does not take the place of a word the dictionary completes`() {
        val list = hosts("datapoems.io", "theverge.com")
        // "the" and "dat" both start dictionary words: the predictions keep the slot.
        assertNull(offer("the", list, completes = true))
        assertNull(offer("dat", list, completes = true))
        // Once there is a dot the run is an address, whatever the dictionary says about its last label.
        assertEquals("datapoems.io", offer("datapoems.i", list, completes = true)!!.host)
        assertEquals("theverge.com", offer("theverge.", list, completes = true)!!.host)
        // The dictionary is only asked when a host fits and nothing else has settled it.
        var asked = 0
        Expansions.domain("zzz", list, Expansions.Field.TEXT) { asked++; true }
        Expansions.domain("datapoems.", list, Expansions.Field.TEXT) { asked++; true }
        Expansions.domain("dat", list, Expansions.Field.ADDRESS) { asked++; true }
        assertEquals(0, asked)
    }

    @Test
    fun `after a scheme or the at sign of a mail address the run is an address wherever it is typed`() {
        val list = hosts("proton.me", "lukesteuber.com")
        // "p" starts plenty of words, and it is still the host that is wanted here.
        assertEquals("roton.me", offer("luke@p", list, completes = true)!!.text)
        assertEquals("kesteuber.com", offer("https://lu", list, completes = true)!!.text)
        assertNull(offer("https://l", list))
        assertNull(offer("luke@", list))
        // A handle is not a mail address.
        assertNull(offer("@lu", list))
        assertNull(offer("@luk", list))
    }

    @Test
    fun `after an at sign the usual mail providers fill in until a host of the person's own fits`() {
        fun common(run: String, list: List<RememberedDomains.Entry> = emptyList(), field: Expansions.Field = Expansions.Field.MAIL) =
            Expansions.domain(run, list, field, MailDomains.COMMON) { false }
        val gmail = common("luke@g")!!
        assertEquals("gmail.com", gmail.label)
        assertEquals("gmail.com", gmail.text)
        assertEquals(1, gmail.replace)
        assertNull(gmail.host)  // never counted as one of the person's own hosts
        assertEquals("icloud.com", common("luke@i")!!.label)
        assertEquals("proton.me", common("luke@p", hosts("proton.me"))!!.host)
        assertEquals("lukesteuber.com", common("luke@l", hosts("lukesteuber.com"))!!.label)
        assertNull(common("luke@"))
        assertNull(common("luke@gmail.com"))
        assertNull(common("@g"))  // a handle
        assertNull(common("gma", field = Expansions.Field.TEXT))
        assertEquals(listOf("gmail.com"), MailDomains.complete("gm"))
        assertEquals("gm", MailDomains.typedAfterAt("Luke@GM"))
        assertNull(MailDomains.typedAfterAt("luke"))
        assertNull(MailDomains.typedAfterAt("@gm"))
        assertEquals(emptyList<String>(), MailDomains.complete(""))
    }

    @Test
    fun `a mail field offers nothing before the at sign, where the name goes`() {
        val list = hosts("lukesteuber.com", "proton.me")
        assertNull(offer("luk", list, Expansions.Field.MAIL))
        assertNull(offer("lukesteuber.c", list, Expansions.Field.MAIL))
        assertEquals("proton.me", offer("luke@p", list, Expansions.Field.MAIL)!!.host)
    }

    @Test
    fun `nothing is offered once a path has begun, or with no hosts`() {
        assertNull(offer("datapoems.io/", hosts("datapoems.io"), Expansions.Field.ADDRESS))
        assertNull(offer("data", emptyList(), Expansions.Field.ADDRESS))
        assertEquals("datapoems.io", Expansions.run("go to datapoems.io"))
        assertEquals("", Expansions.run("ends in a space "))
    }

    @Test
    fun `a host is not offered when the address goes on after the caret`() {
        assertTrue(Expansions.hostEndsAt(""))
        assertTrue(Expansions.hostEndsAt(" more"))
        assertTrue(Expansions.hostEndsAt(")"))
        assertTrue(Expansions.hostEndsAt(","))
        for (after in listOf(".com", "com", "/path", ":80", "-x", "_x", "@x", "9")) assertFalse(after, Expansions.hostEndsAt(after))
        assertTrue(Expansions.wordEndsAt(""))
        assertTrue(Expansions.wordEndsAt(", "))
        assertTrue(Expansions.wordEndsAt(".c"))
        assertFalse(Expansions.wordEndsAt("es"))
        assertFalse(Expansions.wordEndsAt("2"))
        // An apostrophe or hyphen with a letter behind it is the same word going on.
        assertFalse(Expansions.wordEndsAt("’t"))
        assertFalse(Expansions.wordEndsAt("'s"))
        assertFalse(Expansions.wordEndsAt("-b"))
        assertTrue(Expansions.wordEndsAt("' "))
        assertTrue(Expansions.wordEndsAt("’"))
        assertTrue(Expansions.wordEndsAt("- "))
    }

    private val saved = SavedWords.of(listOf("Flat 4, 221B Baker Street\nLondon NW1", "red green", "it’s on my way"))

    @Test
    fun `the words of the saved texts are spelled as the word model spells them`() {
        assertEquals(setOf("flat", "b", "baker", "street", "london", "nw", "red", "green", "it's", "on", "my", "way"), saved)
        assertTrue(SavedWords.of(emptyList()).isEmpty())
        assertTrue(SavedWords.of(listOf("221 4455", "…")).isEmpty())
    }

    @Test
    fun `a word after a saved text's word teaches nothing unless the person typed that word here`() {
        // Straight after the expansion: "london" is the saved text's, and nothing says the person typed it.
        assertTrue(SavedWords.blocks("london", null, saved))
        assertTrue(SavedWords.blocks("London", null, saved))
        // Whatever was deleted from it or typed around it, a word that is left is still the saved text's.
        assertTrue(SavedWords.blocks("street", null, saved))
        assertTrue(SavedWords.blocks("red", null, saved))
        // The person's own last word was something else: the word before this one was not typed here.
        assertTrue(SavedWords.blocks("london", "see", saved))
        // The same word typed by the person, here, is theirs.
        assertFalse(SavedWords.blocks("london", "london", saved))
        assertFalse(SavedWords.blocks("London", "london", saved))
        assertFalse(SavedWords.blocks("it’s", "it's", saved))
        // A word no saved text holds teaches as it always has, known to be typed or not.
        assertFalse(SavedWords.blocks("see", null, saved))
        assertFalse(SavedWords.blocks("see", "see", saved))
        // A sentence start has no word before it.
        assertFalse(SavedWords.blocks(null, null, saved))
        assertFalse(SavedWords.blocks(null, "london", saved))
    }

    @Test
    fun `letters typed onto a saved text's word do not make it the person's`() {
        assertTrue(SavedWords.blocks("streethello", null, saved))
        assertTrue(SavedWords.blocks("londons", null, saved))
        assertTrue(SavedWords.blocks("xbaker", null, saved))
        // Typed whole by the person here, such a word is theirs like any other.
        assertFalse(SavedWords.blocks("streets", "streets", saved))
        // A saved word of three letters or fewer starts and ends too many ordinary words to be told this way.
        assertFalse(SavedWords.blocks("reduce", null, saved))
        assertFalse(SavedWords.blocks("money", null, saved))
        // A word that only has a saved word in its middle is another word.
        assertFalse(SavedWords.blocks("inflation", null, SavedWords.of(listOf("flat"))))
    }

    @Test
    fun `until the saved texts have been read, only a word known to be typed here counts as the person's`() {
        assertTrue(SavedWords.blocks("see", null, null))
        assertTrue(SavedWords.blocks("see", "you", null))
        assertFalse(SavedWords.blocks("see", "see", null))
        assertFalse(SavedWords.blocks(null, null, null))
    }

    @Test
    fun `a sentence the person types themselves is learned even when a saved text holds the same words`() {
        // Each word is known to have been typed here by the time the next one is finished.
        val typed = listOf("it's", "on", "my", "way")
        for (i in 1 until typed.size) assertFalse(typed[i], SavedWords.blocks(typed[i - 1], typed[i - 1], saved))
        // After Backspace the record is given up: the next word after one of those words goes unlearned, once.
        assertTrue(SavedWords.blocks("my", null, saved))
    }

    @Test
    fun `a cache key holding a saved text's word is not written down`() {
        // Keys as the model cache makes them: the last three words, then the last one.
        assertTrue(SavedWords.inKeys(ModelSuggestionCache.keysFor("send to flat four baker street london "), saved))
        assertTrue(SavedWords.inKeys(ModelSuggestionCache.keysFor("baker street london see you "), saved))
        // Digits are not words to the cache either, so they do not push a saved word out of the key.
        assertTrue(SavedWords.inKeys(ModelSuggestionCache.keysFor("london 1 2 hello "), saved))
        // A word grown from a saved word is kept out of the keys as it is out of the pairs.
        assertTrue(SavedWords.inKeys(ModelSuggestionCache.keysFor("streethello "), saved))
        assertTrue(SavedWords.blocks("streethello", null, saved))
        assertFalse(SavedWords.inKeys(ModelSuggestionCache.keysFor("london see you soon "), saved))
        assertFalse(SavedWords.inKeys(ModelSuggestionCache.keysFor("see you soon "), saved))
        // No key, nothing to write; and before the saved texts are read no key is written.
        assertFalse(SavedWords.inKeys(emptyList(), saved))
        assertTrue(SavedWords.inKeys(ModelSuggestionCache.keysFor("see you soon "), null))
    }

    @Test
    fun `in an address bar a run is a word until anything but letters is in it`() {
        for (run in listOf("", "what", "what's", "well-known", "café", "Naïve")) assertTrue(run, Expansions.proseRun(run))
        for (run in listOf("example.", "example.com", "https://ex", "localhost:80", "a/b", "me@ex", "v2", "c++", "why?", "100%", "hello,"))
            assertFalse(run, Expansions.proseRun(run))
    }
}
