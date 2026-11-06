package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedTextsTest {
    /** Keeps what the list writes as the file's text, so every test also goes through the codec. */
    private class FileStore(var file: String = "") : SavedTexts.Store {
        var writes = 0
        override fun load(): List<SavedTexts.Entry> = SavedTextCodec.decodeEntries(file)
        override fun save(entries: List<SavedTexts.Entry>) {
            file = SavedTextCodec.encodeEntries(entries)
            writes++
        }
    }

    private fun stored(store: FileStore) = SavedTextCodec.decode(store.file)

    // The file format

    @Test
    fun `the file keeps tabs, line breaks, separators, backslashes and emoji exactly`() {
        val items = listOf(
            "tab\there\tand there",
            "two\nlines",
            "\nstarts and ends with a line break\n",
            "windows\r\nline end",
            "a lone\rcarriage return",
            "line\u2028separator, paragraph\u2029separator, next line\u0085done",
            "C:\\Users\\luke\\notes.txt",
            "a typed \\n is not a line break, nor \\\\n",
            "ends with a backslash\\",
            "\\",
            "👨‍👩‍👧 🇨🇦 👍🏽 ❤️ 🏴󠁧󠁢󠁳󠁣󠁴󠁿 1️⃣",
            "localtype-saved-text 1",
        )
        assertEquals(items, SavedTextCodec.decode(SavedTextCodec.encode(items)))
    }

    @Test
    fun `each item is one line after the version line, newest first`() {
        val file = SavedTextCodec.encode(listOf("first\nitem", "back\\slash", "tab\tkept"))
        assertEquals("${SavedTextCodec.HEADER}\nfirst\\nitem\nback\\\\slash\ntab\tkept\n", file)
        assertEquals(listOf(SavedTextCodec.HEADER), SavedTextCodec.encode(emptyList()).lines().filter { it.isNotEmpty() })
    }

    @Test
    fun `a file in another format, or an empty one, reads as nothing`() {
        assertEquals(emptyList<String>(), SavedTextCodec.decode(""))
        assertEquals(emptyList<String>(), SavedTextCodec.decode("hello\nworld\n"))
        assertEquals(emptyList<String>(), SavedTextCodec.decode("localtype-saved-text 2\nhello\n"))
        assertEquals(emptyList<String>(), SavedTextCodec.decode(SavedTextCodec.HEADER))
    }

    @Test
    fun `a file saved with CRLF line ends, or edited by hand, still reads`() {
        assertEquals(listOf("one", "two"), SavedTextCodec.decode("${SavedTextCodec.HEADER}\r\none\r\ntwo\r\n"))
        // Blank lines are skipped, an unknown escape keeps its character, and a backslash at the end stays.
        assertEquals(listOf("one", "tq", "end\\"), SavedTextCodec.decode("${SavedTextCodec.HEADER}\none\n\n\\t\\q\nend\\"))
    }

    // The list

    @Test
    fun `saving puts text at the top and writes the file`() {
        val store = FileStore()
        val texts = SavedTexts(store)
        assertTrue(texts.save("123 Main Street"))
        assertTrue(texts.save("Meet at the north door"))
        assertEquals(listOf("Meet at the north door", "123 Main Street"), texts.items)
        assertEquals(texts.items, stored(store))
        // A new list over the same file: a keyboard restart.
        assertEquals(texts.items, SavedTexts(store).items)
    }

    @Test
    fun `saving text that is already saved moves it to the top without a copy`() {
        val texts = SavedTexts(FileStore())
        listOf("a", "b", "c").forEach { texts.save(it) }
        texts.save("a")
        assertEquals(listOf("a", "c", "b"), texts.items)
        // Text that differs only in case or spacing is different text.
        texts.save("A")
        texts.save("a ")
        assertEquals(listOf("a ", "A", "a", "c", "b"), texts.items)
    }

    @Test
    fun `saved text is capped at 50, dropping the oldest`() {
        val store = FileStore()
        val texts = SavedTexts(store)
        (1..55).forEach { texts.save("item $it") }
        assertEquals(SavedTexts.MAX, texts.items.size)
        assertEquals("item 55", texts.items.first())
        assertEquals("item 6", texts.items.last())
        assertEquals(SavedTexts.MAX, stored(store).size)
        // Saving one already there doesn't push anything out.
        texts.save("item 6")
        assertEquals(SavedTexts.MAX, texts.items.size)
        assertEquals("item 7", texts.items.last())
    }

    @Test
    fun `blank text and text over the paste limit are never saved`() {
        val store = FileStore()
        val texts = SavedTexts(store)
        assertFalse(texts.save(""))
        assertFalse(texts.save(" \n\t"))
        assertFalse(texts.save("x".repeat(SavedTexts.MAX_CHARS + 1)))
        assertTrue(texts.save("x".repeat(SavedTexts.MAX_CHARS)))
        assertEquals(1, texts.items.size)
        assertEquals(1, store.writes)
    }

    @Test
    fun `undo puts a removed item back where it was`() {
        val texts = SavedTexts(FileStore())
        listOf("c", "b", "a").forEach { texts.save(it) }
        texts.remove("b")
        assertEquals(listOf("a", "c"), texts.items)
        val removed = texts.lastChange as SavedTexts.Change.Removed
        assertEquals("b", removed.text)
        assertTrue(texts.undo())
        assertEquals(listOf("a", "b", "c"), texts.items)
        assertNull(texts.lastChange)
        assertFalse(texts.undo())
    }

    @Test
    fun `undo is only for the last change, and only until the panel closes`() {
        val texts = SavedTexts(FileStore())
        listOf("c", "b", "a").forEach { texts.save(it) }
        texts.remove("b")
        texts.save("d")
        assertFalse(texts.undo())
        assertEquals(listOf("d", "a", "c"), texts.items)

        texts.remove("a")
        texts.forgetLastChange()
        assertFalse(texts.undo())
        assertEquals(listOf("d", "c"), texts.items)
        // Removing what isn't there changes nothing.
        texts.remove("zzz")
        assertNull(texts.lastChange)
    }

    @Test
    fun `every change is reported, and each save is a new change`() {
        val texts = SavedTexts(FileStore())
        var changes = 0
        texts.onChange = { changes++ }
        texts.save("a")
        val first = texts.lastChange
        texts.save("a")
        assertTrue(texts.lastChange is SavedTexts.Change.Saved)
        assertFalse(first === texts.lastChange)
        texts.remove("a")
        texts.undo()
        texts.clear()
        assertEquals(5, changes)
        // Clearing an empty list is no change.
        texts.clear()
        assertEquals(5, changes)
    }

    @Test
    fun `remove all empties the file`() {
        val store = FileStore()
        val texts = SavedTexts(store)
        texts.save("a")
        texts.remove("a")
        texts.save("b")
        texts.clear()
        assertEquals(emptyList<String>(), texts.items)
        assertNull(texts.lastChange)
        assertEquals(emptyList<String>(), stored(store))
    }

    @Test
    fun `a damaged or hand-edited file loses its blanks, duplicates and overflow`() {
        val lines = listOf("keep", "  ", "keep", "x".repeat(SavedTexts.MAX_CHARS + 1)) + (1..60).map { "item $it" }
        val texts = SavedTexts(FileStore(SavedTextCodec.encode(lines)))
        assertEquals(SavedTexts.MAX, texts.items.size)
        assertEquals(listOf("keep", "item 1"), texts.items.take(2))
        assertEquals(texts.items.distinct(), texts.items)
    }

    @Test
    fun `a recent copy that is already saved shows only under Saved`() {
        val texts = SavedTexts(FileStore())
        texts.save("saved one")
        assertEquals(listOf("copied", "also copied"), texts.unsaved(listOf("copied", "saved one", "also copied")))
        assertEquals(emptyList<String>(), texts.unsaved(emptyList()))
    }

    // Previews

    @Test
    fun `a preview is one line with runs of whitespace as single spaces`() {
        assertEquals("Meet at 5 pm, by the north door", SavedTexts.preview("  Meet at 5 pm,\n\n\tby the north   door \n", 80))
        assertEquals("short", SavedTexts.preview("short", 5))
    }

    @Test
    fun `a long preview ends in an ellipsis between characters a person sees`() {
        assertEquals("I’ll be there at 5…", SavedTexts.preview("I’ll be there at 5 👍🏽 see you", 21))
        assertEquals("I’ll be there at 5 👍🏽…", SavedTexts.preview("I’ll be there at 5 👍🏽 see you", 23))
        // The cut lands in the middle of a family, a flag pair or a surrogate pair: whole emoji only.
        val families = "👨‍👩‍👧".repeat(30)
        assertEquals("👨‍👩‍👧".repeat(2) + "…", SavedTexts.preview(families, 20))
        assertEquals("👨‍👩‍👧".repeat(2) + "…", SavedTexts.preview(families, 23))
        assertEquals("🇨🇦🇨🇦…", SavedTexts.preview("🇨🇦".repeat(40), 10))
        assertEquals("🇨🇦🇨🇦…", SavedTexts.preview("🇨🇦".repeat(40), 11))
        assertEquals("ab…", SavedTexts.preview("ab😀😀😀", 3))
    }

    @Test
    fun `text fits when it isn't blank and is within the paste limit`() {
        assertTrue(SavedTexts.fits("a"))
        assertTrue(SavedTexts.fits("x".repeat(SavedTexts.MAX_CHARS)))
        assertFalse(SavedTexts.fits(""))
        assertFalse(SavedTexts.fits("\u2028 \n"))
        assertFalse(SavedTexts.fits("x".repeat(SavedTexts.MAX_CHARS + 1)))
    }

    // Shortcut words

    @Test
    fun `a shortcut is stored with its item and a file without shortcuts is what it always was`() {
        val store = FileStore()
        val texts = SavedTexts(store)
        texts.save("221B Baker Street, London")
        texts.save("See you at 5")
        val before = store.file
        assertEquals(SavedTextCodec.encode(listOf("See you at 5", "221B Baker Street, London")), before)
        assertEquals(SavedTexts.CodeResult.SET, texts.setCode("221B Baker Street, London", " Addr "))
        assertEquals(mapOf("221B Baker Street, London" to "addr"), texts.codes)
        assertEquals("localtype-saved-text 1\nSee you at 5\n221B Baker Street, London\\caddr\n", store.file)
        // Read back by a new list, as the keyboard does at start.
        val again = SavedTexts(FileStore(store.file))
        assertEquals(texts.items, again.items)
        assertEquals(texts.codes, again.codes)
        // Taking the shortcut away gives the old bytes back.
        assertEquals(SavedTexts.CodeResult.CLEARED, texts.setCode("221B Baker Street, London", ""))
        assertEquals(before, store.file)
    }

    @Test
    fun `text that only looks like a shortcut escape stays text`() {
        val items = listOf("ends in a typed \\chome", "C:\\code", "a\\", "\\c", "x\\cab")
        val entries = items.map { SavedTexts.Entry(it) } + SavedTexts.Entry("ends with a backslash\\", "home")
        assertEquals(entries, SavedTextCodec.decodeEntries(SavedTextCodec.encodeEntries(entries)))
        assertEquals(entries.map { it.text }, SavedTextCodec.decode(SavedTextCodec.encodeEntries(entries)))
    }

    @Test
    fun `a file with a bad or repeated shortcut loses the shortcut and keeps the item`() {
        val file = "localtype-saved-text 1\none\\caddr\ntwo\\caddr\nthree\\cx\nfour\\cno digits1\nfive\\cHome\n"
        val texts = SavedTexts(FileStore(file))
        // "three\cx" and "four\cno digits1" are not shortcuts at all, so the old reading stands: the escape keeps its letter.
        assertEquals(listOf("one", "two", "threecx", "fourcno digits1", "five"), texts.items)
        assertEquals(mapOf("one" to "addr", "five" to "home"), texts.codes)
    }

    @Test
    fun `a shortcut is letters only, two to twenty-four, and belongs to one item`() {
        val texts = SavedTexts(FileStore())
        texts.save("first")
        texts.save("second")
        assertEquals(SavedTexts.CodeResult.INVALID, texts.setCode("first", "a"))
        assertEquals(SavedTexts.CodeResult.INVALID, texts.setCode("first", "addr1"))
        assertEquals(SavedTexts.CodeResult.INVALID, texts.setCode("first", "my addr"))
        assertEquals(SavedTexts.CodeResult.INVALID, texts.setCode("first", "it's"))
        assertEquals(SavedTexts.CodeResult.INVALID, texts.setCode("first", "x".repeat(SavedTexts.MAX_CODE + 1)))
        assertEquals(SavedTexts.CodeResult.NOT_SAVED, texts.setCode("never saved", "addr"))
        assertEquals(SavedTexts.CodeResult.SET, texts.setCode("first", "ADDR"))
        assertEquals(SavedTexts.CodeResult.SET, texts.setCode("first", "addr"))
        assertEquals(SavedTexts.CodeResult.TAKEN, texts.setCode("second", "Addr"))
        assertEquals(SavedTexts.CodeResult.SET, texts.setCode("second", "señas"))
        assertEquals(mapOf("first" to "addr", "second" to "señas"), texts.codes)
        // A new word for the same item replaces the old one, which is free again.
        assertEquals(SavedTexts.CodeResult.SET, texts.setCode("first", "home"))
        assertEquals(SavedTexts.CodeResult.SET, texts.setCode("second", "addr"))
    }

    @Test
    fun `typing the shortcut, in any case, finds its item and nothing else does`() {
        val texts = SavedTexts(FileStore())
        texts.save("221B Baker Street")
        assertNull(texts.expansion("addr"))
        assertFalse(texts.isCode("addr"))
        texts.setCode("221B Baker Street", "addr")
        assertEquals("221B Baker Street", texts.expansion("addr"))
        assertEquals("221B Baker Street", texts.expansion("Addr"))
        assertTrue(texts.isCode("ADDR"))
        assertNull(texts.expansion("add"))
        assertNull(texts.expansion("addrs"))
        assertNull(texts.expansion(""))
    }

    @Test
    fun `a shortcut follows its item to the top, leaves with it, and comes back with Undo`() {
        val store = FileStore()
        val texts = SavedTexts(store)
        texts.save("address")
        texts.save("other")
        texts.setCode("address", "addr")
        texts.save("address")
        assertEquals(listOf("address", "other"), texts.items)
        assertEquals("address", texts.expansion("addr"))
        texts.remove("address")
        assertNull(texts.expansion("addr"))
        assertEquals("addr", (texts.lastChange as SavedTexts.Change.Removed).code)
        assertEquals(listOf(SavedTexts.Entry("other")), SavedTextCodec.decodeEntries(store.file))
        assertTrue(texts.undo())
        assertEquals("address", texts.expansion("addr"))
        assertEquals(listOf(SavedTexts.Entry("address", "addr"), SavedTexts.Entry("other")), SavedTextCodec.decodeEntries(store.file))
        texts.clear()
        assertTrue(texts.codes.isEmpty())
    }

    @Test
    fun `Undo does not take a shortcut another item was given meanwhile`() {
        val texts = SavedTexts(FileStore())
        texts.save("old")
        texts.save("new")
        texts.setCode("old", "addr")
        texts.remove("old")
        val removed = texts.lastChange
        // Setting a shortcut is a change of its own, so the panel's Undo is gone with it.
        texts.setCode("new", "addr")
        assertNull(texts.lastChange)
        assertFalse(texts.undo())
        assertEquals("addr", (removed as SavedTexts.Change.Removed).code)
        assertEquals("new", texts.expansion("addr"))
    }

    @Test
    fun `an item pushed out by the cap takes its shortcut with it`() {
        val texts = SavedTexts(FileStore())
        texts.save("oldest")
        texts.setCode("oldest", "old")
        repeat(SavedTexts.MAX) { texts.save("item $it") }
        assertFalse("oldest" in texts.items)
        assertTrue(texts.codes.isEmpty())
        assertNull(texts.expansion("old"))
    }

    @Test
    fun `the saved texts' words follow the list`() {
        val texts = SavedTexts(FileStore())
        assertTrue(texts.words.isEmpty())
        texts.save("Flat 4, Baker Street")
        assertEquals(setOf("flat", "baker", "street"), texts.words)
        texts.save("London")
        assertEquals(setOf("flat", "baker", "street", "london"), texts.words)
        texts.remove("Flat 4, Baker Street")
        assertEquals(setOf("london"), texts.words)
        texts.undo()
        assertEquals(setOf("flat", "baker", "street", "london"), texts.words)
        texts.clear()
        assertTrue(texts.words.isEmpty())
    }
}
