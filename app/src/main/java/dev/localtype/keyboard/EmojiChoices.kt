package dev.localtype.keyboard

/** Where the emoji panel's recent list and skin-tone choices persist: SharedPreferences on the phone. */
interface EmojiChoiceStore {
    fun recent(): List<String>
    fun setRecent(recent: List<String>)

    /** Each emoji's own tone (0 none, 1 light … 5 dark), keyed by its base as the inventory has it. */
    fun tones(): Map<String, Int>
    fun defaultTone(): Int
    fun setTone(base: String, tone: Int, defaultTone: Int)
}

/**
 * The last [MAX] emoji inserted from the panel or the strip, newest first. One entry per emoji
 * whatever its tone or U+FE0F: inserting 👍🏽 after 👍 moves it to the front as 👍🏽. The caller
 * records nothing in a field that doesn't allow learning.
 */
class RecentEmoji(private val store: EmojiChoiceStore) {
    var items: List<String> = store.recent().take(MAX)
        private set

    fun record(emoji: String) {
        if (emoji.isBlank()) return
        val key = EmojiInventory.key(emoji)
        items = (listOf(emoji) + items.filter { EmojiInventory.key(it) != key }).take(MAX)
        store.setRecent(items)
    }

    /**
     * Reads the list from the store again. Recent emoji are history, so Forget learned words
     * empties them there, and a running keyboard reloads when it sees the learned words change.
     */
    fun reload() {
        items = store.recent().take(MAX)
    }

    companion object {
        const val MAX = 32

        /** The stored form: emoji never contain a space, so one separates them. */
        fun encode(items: List<String>): String = items.joinToString(" ")
        fun decode(stored: String?): List<String> = stored.orEmpty().split(' ').filter { it.isNotEmpty() }
    }
}

/**
 * Which skin tone an emoji with tone variants shows and inserts: the tone last picked for it, else
 * the tone last picked for any emoji, else none. Most people use one tone, so picking a tone for
 * one emoji also makes it the default for every emoji without its own choice. These are
 * preferences, not content.
 */
class EmojiTones(private val store: EmojiChoiceStore) {
    private val chosen = HashMap(store.tones())

    var defaultTone: Int = valid(store.defaultTone())
        private set

    /** Reads the choices again, after a copy from another phone put some in. */
    fun reload() {
        chosen.clear()
        chosen.putAll(store.tones())
        defaultTone = valid(store.defaultTone())
    }

    fun toneFor(emoji: EmojiInventory.Emoji): Int =
        if (emoji.tones.isEmpty()) 0 else valid(chosen[emoji.value] ?: defaultTone)

    /** A stored value outside 0..5 (a damaged file) means no tone. */
    private fun valid(tone: Int): Int = if (tone in 0..EmojiInventory.TONE_COUNT) tone else 0

    /** What the grid shows for [emoji]: its effective variant. */
    fun display(emoji: EmojiInventory.Emoji): String = emoji.variant(toneFor(emoji))

    /** Remembers [tone] for [emoji] and makes it the default for the rest. */
    fun choose(emoji: EmojiInventory.Emoji, tone: Int) {
        if (emoji.tones.isEmpty() || tone !in 0..EmojiInventory.TONE_COUNT) return
        chosen[emoji.value] = tone
        defaultTone = tone
        store.setTone(emoji.value, tone, tone)
    }
}
