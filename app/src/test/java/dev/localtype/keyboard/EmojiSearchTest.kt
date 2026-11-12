package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiSearchTest {
    private fun inventory(hasGlyph: (String) -> Boolean = { true }) =
        File("src/main/assets/emoji_inventory.txt").bufferedReader().useLines { EmojiInventory.parse(it, hasGlyph) }

    private val strip = File("src/main/assets/emoji_en.txt").bufferedReader().useLines { EmojiSuggestions.parse(it) }.phrases
    private val shipped = EmojiSearch(inventory(), strip)

    private fun EmojiSearch.values(query: String) = search(query).map { it.value }

    /** A small inventory where each tier is easy to see. */
    private val small = EmojiInventory.parse(
        sequenceOf(
            "#groups\tSome",
            "0\t😺\tgrinning cat\tface|grin\t",
            "0\t🐈\tcat\tpet\t",
            "0\t📒\tledger\tcatalog|book\t",
            "0\t🐱\tcat face\tcat|face\t",
            "0\t🐶\tdog\tpet\t",
        ),
    )

    @Test
    fun `an exact keyword ranks before a keyword prefix, which ranks before a word of the name`() {
        // 🐈's name is a keyword too; 🐱 has "cat" as a keyword; 📒 has "catalog"; only 😺's name has a word starting "cat".
        assertEquals(listOf("🐈", "🐱", "📒", "😺"), EmojiSearch(small).values("cat"))
        assertEquals(listOf("📒"), EmojiSearch(small).values("catal"))
        assertEquals(listOf("🐈", "🐶"), EmojiSearch(small).values("pet"))
    }

    @Test
    fun `the strip's list adds its emoji and puts them first within a tier, in its order`() {
        val search = EmojiSearch(small, mapOf("cat" to listOf("🐱", "😺", "🦄"), "catnap" to listOf("🐶")))
        // 🐱 and 😺 are listed for "cat" itself (😺 rises from the name tier), then 🐈, an unlisted exact
        // keyword. "catnap" starts with the query, so 🐶 joins the prefix tier ahead of 📒. 🦄 isn't in
        // the inventory and is skipped.
        assertEquals(listOf("🐱", "😺", "🐈", "🐶", "📒"), search.values("cat"))
        assertEquals(listOf("🐶"), search.values("catn"))
    }

    @Test
    fun `the shipped lists find what people type`() {
        // No CLDR keyword says "lol"; the strip's list does.
        assertEquals(listOf("😂", "🤣", "😹", "😆", "😅"), shipped.values("lol").take(5))
        assertEquals(listOf("❤️", "💙", "💚", "💛", "💜", "🖤", "🤍", "🧡"), shipped.values("heart").take(8))
        assertEquals(listOf("🐱", "🐈", "😺", "😻"), shipped.values("cat").take(4))
        assertEquals("👍", shipped.values("thumbs up").first())
        assertEquals("👍", shipped.values("thumbs u").first())
        assertEquals("👍", shipped.values("+1").first())
        assertEquals(listOf("☕", "🍵", "🥤"), shipped.values("cof").take(3))
        // Every word of the query starts a word of the name.
        assertTrue("😊" in shipped.values("smiling eyes"))
        assertEquals(listOf("🇦🇪", "🇬🇧", "🇺🇳", "🇺🇸"), shipped.values("united"))
        assertEquals(listOf("👨‍🦰", "👩‍🦰", "🧑‍🦰"), shipped.values("red hair"))
    }

    @Test
    fun `case, accents, curly apostrophes and spaces don't matter`() {
        assertEquals(shipped.values("thumbs up"), shipped.values("  Thumbs   UP "))
        assertEquals(listOf("🪅"), shipped.values("pinata"))
        assertEquals(shipped.values("piñata"), shipped.values("PINATA"))
        assertEquals(shipped.values("o'clock"), shipped.values("o’clock"))
        assertTrue(shipped.values("o'clock").isNotEmpty())
        assertEquals("pinata o'clock", EmojiSearch.normalize(" Piñata  O’Clock "))
    }

    @Test
    fun `reaction aliases are searchable and unavailable new glyphs are skipped`() {
        assertEquals("🙄", shipped.values("rolling my eyes").first())
        assertEquals("🥹", shipped.values("happy tears").first())
        assertEquals("🫡", shipped.values("roger that").first())
        assertEquals("🩷", shipped.values("pink heart").first())
        assertEquals("🫩", shipped.values("exhausted").first())
        val olderFont = EmojiSearch(inventory { it != "🫩" }, strip)
        assertEquals("😫", olderFont.values("exhausted").first())
        assertTrue("🫩" !in olderFont.values("exhausted"))
    }

    @Test
    fun `results are base emoji the phone can draw, whatever the strip's list names`() {
        val noTearsOfJoy = EmojiSearch(inventory { it != "😂" }, strip)
        assertEquals(listOf("🤣", "😹", "😆", "😅"), noTearsOfJoy.values("lol").take(4))
        // Tones come from the panel's choice, not the search: one result per emoji.
        val thumbs = shipped.search("thumbs up").single()
        assertEquals("👍", thumbs.value)
        assertEquals(5, thumbs.tones.size)
    }

    @Test
    fun `nothing matches a blank or unknown query`() {
        assertEquals(emptyList<String>(), shipped.values(""))
        assertEquals(emptyList<String>(), shipped.values("   "))
        assertEquals(emptyList<String>(), shipped.values("zzqx"))
        assertEquals(emptyList<String>(), EmojiSearch(EmojiInventory.EMPTY, strip).values("cat"))
    }

    @Test
    fun `letters, space and delete edit the query, and nothing else does`() {
        assertEquals("c", EmojiSearch.edit("", KeyAction.Text("c")))
        assertEquals("red ", EmojiSearch.edit("red", KeyAction.Space))
        // A space never leads or doubles.
        assertEquals("", EmojiSearch.edit("", KeyAction.Space))
        assertEquals("red ", EmojiSearch.edit("red ", KeyAction.Space))
        assertEquals("re", EmojiSearch.edit("red", KeyAction.Backspace))
        assertEquals("", EmojiSearch.edit("", KeyAction.Backspace))
        // Delete takes a whole character: an accented letter typed as two code points goes at once.
        assertEquals("caf", EmojiSearch.edit("café", KeyAction.Backspace))
        assertNull(EmojiSearch.edit("cat", KeyAction.Enter))
        assertNull(EmojiSearch.edit("cat", KeyAction.Shift))
        assertNull(EmojiSearch.edit("cat", KeyAction.ToSymbols))
        assertNull(EmojiSearch.edit("cat", KeyAction.SwitchKeyboard))
    }

    @Test
    fun `the query stops growing at its cap`() {
        val full = "a".repeat(EmojiSearch.MAX_QUERY_CHARS)
        assertEquals(full, EmojiSearch.edit(full, KeyAction.Text("b")))
        assertEquals(full, EmojiSearch.edit(full, KeyAction.Space))
        assertEquals(full.dropLast(1), EmojiSearch.edit(full, KeyAction.Backspace))
    }

    @Test
    fun `the word at the cursor is the one to search for`() {
        assertEquals("pizza", EmojiSearch.wordAtCursor("want some pizza"))
        assertEquals("pizza", EmojiSearch.wordAtCursor("want some pizza "))
        assertEquals("pizza", EmojiSearch.wordAtCursor("Pizza"))
        assertEquals("don't", EmojiSearch.wordAtCursor("I don’t".replace('’', '\'')))
        // Not after two spaces, punctuation, a digit, an emoji, a lone letter or nothing at all.
        for (text in listOf("pizza  ", "pizza.", "pizza. ", "pizza 2", "pizza 🍕", "a", "I ", "", " ", "x".repeat(41))) {
            assertEquals(text, null, EmojiSearch.wordAtCursor(text))
        }
    }

    @Test
    fun `only a word that names an emoji opens the panel on a search`() {
        // By CLDR keyword, or by the strip's own list ("rofl" has no keyword).
        for (word in listOf("pizza", "love", "rofl", "Coffee", "dog", "lmao", "happy birthday")) assertTrue(word, shipped.names(word))
        // Words that only start a keyword ("the" starts "theater") would open a search after nearly every word.
        for (word in listOf("the", "to", "so", "and", "was", "going", "", "  ")) assertFalse(word, shipped.names(word))
        // And a word it names does find something.
        for (word in listOf("pizza", "rofl")) assertTrue(word, shipped.search(word).isNotEmpty())
    }
}
