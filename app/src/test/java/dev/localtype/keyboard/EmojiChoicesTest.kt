package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiChoicesTest {
    private class MemoryStore(
        var savedRecent: List<String> = emptyList(),
        val savedTones: MutableMap<String, Int> = HashMap(),
        var savedDefault: Int = 0,
    ) : EmojiChoiceStore {
        var writes = 0
        override fun recent() = savedRecent
        override fun setRecent(recent: List<String>) {
            savedRecent = recent
            writes++
        }
        override fun tones(): Map<String, Int> = savedTones
        override fun defaultTone() = savedDefault
        override fun setTone(base: String, tone: Int, defaultTone: Int) {
            savedTones[base] = tone
            savedDefault = defaultTone
        }
    }

    private val thumbsUp = EmojiInventory.Emoji("👍", "thumbs up", emptyList(), listOf("👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"), 1)
    private val wave = EmojiInventory.Emoji("👋", "waving hand", emptyList(), listOf("👋🏻", "👋🏼", "👋🏽", "👋🏾", "👋🏿"), 1)
    private val coffee = EmojiInventory.Emoji("☕", "hot beverage", emptyList(), emptyList(), 3)

    @Test
    fun `recent is newest first and persisted`() {
        val store = MemoryStore()
        val recent = RecentEmoji(store)
        recent.record("😀")
        recent.record("☕")
        recent.record("🎉")
        assertEquals(listOf("🎉", "☕", "😀"), recent.items)
        assertEquals(recent.items, store.savedRecent)
        assertEquals(listOf("🎉", "☕", "😀"), RecentEmoji(store).items)
    }

    @Test
    fun `using an emoji again moves it to the front, whatever its tone or variation selector`() {
        val recent = RecentEmoji(MemoryStore())
        listOf("👍", "☕", "❤", "👍🏽", "❤️").forEach(recent::record)
        assertEquals(listOf("❤️", "👍🏽", "☕"), recent.items)
    }

    @Test
    fun `recent keeps the last 32`() {
        val store = MemoryStore()
        val recent = RecentEmoji(store)
        val emoji = (0 until 40).map { String(Character.toChars(0x1F600 + it)) }
        emoji.forEach(recent::record)
        assertEquals(RecentEmoji.MAX, recent.items.size)
        assertEquals(emoji.takeLast(32).reversed(), recent.items)
        // A store holding more (an older build, an edited file) is cut on load.
        assertEquals(32, RecentEmoji(MemoryStore(savedRecent = emoji)).items.size)
    }

    @Test
    fun `blank inserts are not recorded`() {
        val store = MemoryStore()
        val recent = RecentEmoji(store)
        recent.record("")
        recent.record(" ")
        assertEquals(emptyList<String>(), recent.items)
        assertEquals(0, store.writes)
    }

    @Test
    fun `forget learned words empties the recent list but leaves skin tones`() {
        val store = MemoryStore()
        val recent = RecentEmoji(store)
        val tones = EmojiTones(store)
        recent.record("☕")
        recent.record("👍🏾")
        tones.choose(thumbsUp, 4)
        // Forgetting one learned pair leaves the stored list, so reloading changes nothing.
        recent.reload()
        assertEquals(listOf("👍🏾", "☕"), recent.items)
        // Forget learned words empties the stored list; the running keyboard reloads when it sees that.
        store.savedRecent = emptyList()
        recent.reload()
        assertEquals(emptyList<String>(), recent.items)
        recent.record("🎉")
        assertEquals(listOf("🎉"), store.savedRecent)
        val reopened = EmojiTones(store)
        assertEquals("👍🏾", reopened.display(thumbsUp))
        assertEquals(4, reopened.defaultTone)
    }

    @Test
    fun `recent survives its stored form`() {
        val items = listOf("👍🏽", "🇺🇸", "👨‍👩‍👧", "❤️", "🧑🏿‍💻")
        assertEquals(items, RecentEmoji.decode(RecentEmoji.encode(items)))
        assertEquals(emptyList<String>(), RecentEmoji.decode(null))
        assertEquals(emptyList<String>(), RecentEmoji.decode(""))
    }

    @Test
    fun `a tone picked for one emoji becomes the default for the rest`() {
        val store = MemoryStore()
        val tones = EmojiTones(store)
        assertEquals("👍", tones.display(thumbsUp))
        tones.choose(thumbsUp, 4)
        assertEquals("👍🏾", tones.display(thumbsUp))
        // No choice of its own: the new default.
        assertEquals("👋🏾", tones.display(wave))
        tones.choose(wave, 1)
        assertEquals("👋🏻", tones.display(wave))
        // Thumbs up keeps its own choice; the default moved to light.
        assertEquals("👍🏾", tones.display(thumbsUp))
        assertEquals(1, tones.defaultTone)
        assertEquals(mapOf("👍" to 4, "👋" to 1), store.savedTones)
        assertEquals(1, store.savedDefault)
    }

    @Test
    fun `choosing no tone is remembered too`() {
        val tones = EmojiTones(MemoryStore(savedTones = hashMapOf("👋" to 3), savedDefault = 2))
        assertEquals("👋🏽", tones.display(wave))
        assertEquals("👍🏼", tones.display(thumbsUp))
        tones.choose(wave, 0)
        assertEquals("👋", tones.display(wave))
        assertEquals("👍", tones.display(thumbsUp))
    }

    @Test
    fun `emoji without tones ignore them`() {
        val store = MemoryStore(savedDefault = 3)
        val tones = EmojiTones(store)
        assertEquals("☕", tones.display(coffee))
        tones.choose(coffee, 2)
        assertEquals(emptyMap<String, Int>(), store.savedTones)
        assertEquals(3, tones.defaultTone)
    }

    @Test
    fun `damaged stored tones mean no tone`() {
        val tones = EmojiTones(MemoryStore(savedTones = hashMapOf("👋" to -1), savedDefault = 9))
        assertEquals(0, tones.defaultTone)
        assertEquals("👍", tones.display(thumbsUp))
        assertEquals("👋", tones.display(wave))
    }
}
