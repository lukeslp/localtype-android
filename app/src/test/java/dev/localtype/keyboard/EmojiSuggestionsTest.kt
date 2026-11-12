package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiSuggestionsTest {
    private val emoji = File("src/main/assets/emoji_en.txt").bufferedReader().useLines { EmojiSuggestions.parse(it) }

    @Test
    fun `suggests for the word being typed and the word just finished`() {
        assertEquals("☕", emoji.forContext("coffee", "coffee").first())
        assertEquals("🎂", emoji.forContext("happy birthday ", "").first())
        assertEquals("😂", emoji.forContext("haha ", "").first())
        assertEquals("🙌", emoji.forContext("jazz hands ", "").first())
    }

    @Test
    fun `stays quiet away from a matching word`() {
        assertTrue(emoji.forContext("see you ", "").isEmpty())
        // Only right after the word: once another word follows, the emoji is gone.
        assertTrue(emoji.forContext("coffee is ", "").isEmpty())
        assertTrue(emoji.forContext("co", "co").isEmpty())
        assertTrue(emoji.forContext("", "").isEmpty())
    }

    @Test
    fun `words with variations offer several, in order`() {
        assertEquals(listOf("👋", "🤚", "🖐️", "✋", "🫡"), emoji.forContext("wave ", "", limit = 8))
        assertEquals(listOf("🙌", "👐", "🤲", "👏", "🫶"), emoji.forContext("hands ", "", limit = 8))
        assertEquals(8, emoji.forContext("heart", "heart", limit = 8).size)
        assertEquals(listOf("☕"), emoji.forContext("coffee ", "", limit = 1))
    }

    @Test
    fun `bundled list is parsed and case-insensitive`() {
        assertTrue(emoji.size > 250)
        assertEquals(listOf("🎉", "👏"), emoji.forContext("Congrats", "Congrats", limit = 2))
    }

    @Test
    fun `a tap replaces the word, keeping the space after it`() {
        assertEquals(EmojiSuggestions.Match(listOf("☕"), 6, " "), emoji.match("I need coffee ", "", limit = 1))
        assertEquals(EmojiSuggestions.Match(listOf("☕"), 6, ""), emoji.match("I need coffee", "coffee", limit = 1))
    }

    @Test
    fun `phrases are matched whole, longest first`() {
        assertEquals(EmojiSuggestions.Match(listOf("👍"), 9, " "), emoji.match("sounds good thumbs up ", "", limit = 1))
        assertEquals(EmojiSuggestions.Match(listOf("🎂"), 14, ""), emoji.match("happy birthday", "birthday", limit = 1))
        // Only words separated by spaces form a phrase: after a comma, just the last word counts.
        assertEquals(8, emoji.match("happy, birthday ", "")?.replaceLength)
        assertEquals("🚀", emoji.match("ok let’s go ", "")?.options?.first())
    }

    @Test
    fun `a misspelling can offer its correction's emoji`() {
        assertEquals(EmojiSuggestions.Match(listOf("☕"), 5, ""), emoji.match("more cofee", "cofee", limit = 1, lookupAs = "coffee"))
    }

    @Test
    fun `a word with an emoji of its own keeps it when the corrector offers something else`() {
        // Through 0.5.53 only the correction was looked up: "rofl", offered "of", showed no emoji.
        assertEquals(EmojiSuggestions.Match(listOf("🤣"), 4, ""), emoji.match("rofl", "rofl", limit = 1, lookupAs = "of"))
        assertEquals("🎆", emoji.match("newyear", "newyear", lookupAs = "near")?.options?.first())
        assertEquals("🙄", emoji.match("eyeroll", "eyeroll", lookupAs = "yell")?.options?.first())
        // A phrase too: its last word as typed, then as corrected.
        assertEquals(EmojiSuggestions.Match(listOf("🙄"), 8, ""), emoji.match("eye roll", "roll", limit = 1, lookupAs = "role"))
        assertEquals(EmojiSuggestions.Match(listOf("🙄"), 7, ""), emoji.match("eye rol", "rol", limit = 1, lookupAs = "roll"))
        // Whatever the corrector says, no word on the list loses its emoji.
        for ((phrase, options) in emoji.phrases) {
            val last = phrase.substringAfterLast(' ')
            if (!phrase.contains(' ') && last.length < 2) continue
            assertEquals(phrase, options.first(), emoji.match(phrase, last, lookupAs = "zzzzzz")?.options?.first())
        }
    }

    @Test
    fun `chat shorthand has emoji`() {
        for ((word, first) in listOf("rofl" to "🤣", "lmao" to "😂", "lmfao" to "🤣", "omg" to "😱", "smh" to "🤦", "idk" to "🤷",
            "brb" to "🏃", "ily" to "❤️", "thx" to "🙏", "hbd" to "🎂", "gg" to "🏆", "ugh" to "😩", "hmm" to "🤔", "yikes" to "😬")) {
            assertEquals(word, first, emoji.forContext(word, word).firstOrNull())
            assertEquals(word, first, emoji.forContext("$word ", "").firstOrNull())
        }
    }

    @Test
    fun `every emoji on the list is one the panel can draw`() {
        val inventory = File("src/main/assets/emoji_inventory.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t')[1].replace("\uFE0F", "") }.toSet()
        for ((phrase, options) in emoji.phrases) for (option in options) {
            assertTrue("$phrase: $option", option.replace("\uFE0F", "") in inventory)
        }
    }

    @Test
    fun `reaction phrases replace only the matching phrase and keep spacing`() {
        assertEquals(EmojiSuggestions.Match(listOf("🙄"), 15, "  "),
            emoji.match("I am rolling my eyes  ", "", limit = 1))
        assertEquals(EmojiSuggestions.Match(listOf("🙄"), 8, ""),
            emoji.match("another EYE ROLL", "ROLL", limit = 1))
        assertEquals(EmojiSuggestions.Match(listOf("💀"), 14, " "),
            emoji.match("I'm dying laughing ", "", limit = 1))
        assertTrue(emoji.forContext("dying ", "").isEmpty())
        assertTrue(emoji.forContext("rolling my eyes again ", "").isEmpty())
        assertTrue(emoji.forContext("happy, tears ", "").isEmpty())
    }
}
