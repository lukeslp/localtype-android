package dev.localtype.keyboard

import java.text.Normalizer
import java.util.Locale

/**
 * Finds emoji by what they're called, for the emoji panel's search. Matches rank in three tiers: a
 * keyword equal to the query, then a keyword that starts with it, then emoji where every word of
 * the query starts a word of the name or a keyword ("smiling eyes" finds "smiling face with smiling
 * eyes"). CLDR's name counts as a keyword. The strip's own word list ([boosts], `emoji_en.txt`)
 * joins those tiers ("lol" has no CLDR keyword) and goes first within a tier, in its own order, so
 * "heart" starts with ❤️. Ties keep Unicode order. Case, accents and extra spaces don't matter.
 * Pure, and built once per inventory; every result is a base emoji the phone can draw.
 */
class EmojiSearch(inventory: EmojiInventory, boosts: Map<String, List<String>> = emptyMap()) {
    /** One emoji's [keywords] (its name first) and every word in them, normalized once. */
    private class Entry(val emoji: EmojiInventory.Emoji, val keywords: List<String>, val words: List<String>)

    /** Where the strip's list puts an entry: how the phrase matched, how long it is, and where it lists the entry. */
    private data class Lift(val tier: Int, val length: Int, val position: Int) : Comparable<Lift> {
        override fun compareTo(other: Lift): Int = compareValuesBy(this, other, Lift::tier, Lift::length, Lift::position)
    }

    private class Match(val index: Int, val tier: Int, val lift: Lift?)

    private val entries: List<Entry> = inventory.emoji.map { emoji ->
        val keywords = (listOf(emoji.name) + emoji.keywords).map(::normalize).filter { it.isNotEmpty() }.distinct()
        Entry(emoji, keywords, keywords.flatMap { it.split(WORD_BREAK) }.filter { it.isNotEmpty() }.distinct())
    }

    /** Each phrase of the strip's list with the entries it names, in its order; emoji the inventory left out are skipped. */
    private val boosted: List<Pair<String, List<Int>>>

    init {
        val entryOf = HashMap<String, Int>(entries.size * 2)
        entries.forEachIndexed { index, entry -> entryOf[entry.emoji.value] = index }
        boosted = boosts.mapNotNull { (phrase, emoji) ->
            val listed = emoji.mapNotNull { inventory.find(it)?.let { found -> entryOf[found.value] } }.distinct()
            normalize(phrase).takeIf { it.isNotEmpty() && listed.isNotEmpty() }?.let { it to listed }
        }
    }

    /** Every emoji matching [query], best first; nothing for a blank query. */
    fun search(query: String): List<EmojiInventory.Emoji> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()
        val terms = normalized.split(' ')
        val lifts = HashMap<Int, Lift>()
        for ((phrase, listed) in boosted) {
            val tier = when {
                phrase == normalized -> EXACT
                phrase.startsWith(normalized) -> PREFIX
                else -> continue
            }
            listed.forEachIndexed { position, index ->
                val lift = Lift(tier, phrase.length, position)
                if (lifts[index]?.let { it <= lift } != true) lifts[index] = lift
            }
        }
        val matches = ArrayList<Match>()
        entries.forEachIndexed { index, entry ->
            val lift = lifts[index]
            val tier = minOf(tierOf(entry, normalized, terms), lift?.tier ?: NONE)
            if (tier < NONE) matches += Match(index, tier, lift)
        }
        matches.sortWith(compareBy<Match>({ it.tier }, { it.lift?.tier ?: NONE }, { it.lift?.length ?: 0 }, { it.lift?.position ?: 0 }, { it.index }))
        return matches.map { entries[it.index].emoji }
    }

    /**
     * Whether [query] is a name for some emoji: a keyword or a phrase of the strip's list equal to it, not one
     * that merely starts with it. "pizza" and "rofl" are; "the" is not, though it starts "theater". This decides
     * whether the panel opens on a search for the word at the cursor, where a looser match would open one after
     * nearly every word.
     */
    fun names(query: String): Boolean {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return false
        return boosted.any { it.first == normalized } || entries.any { entry -> entry.keywords.any { it == normalized } }
    }

    private fun tierOf(entry: Entry, query: String, terms: List<String>): Int = when {
        entry.keywords.any { it == query } -> EXACT
        entry.keywords.any { it.startsWith(query) } -> PREFIX
        terms.all { term -> entry.words.any { it.startsWith(term) } } -> WORDS
        else -> NONE
    }

    companion object {
        /** The query stops growing here; no emoji name or keyword is longer. */
        const val MAX_QUERY_CHARS = 40

        private const val EXACT = 0
        private const val PREFIX = 1
        private const val WORDS = 2
        private const val NONE = 3

        private val WHITESPACE = Regex("\\s+")
        private val MARKS = Regex("\\p{Mn}+")
        /** Words in a name: "smiling face with heart-eyes" has "heart" and "eyes"; "o'clock" stays whole. */
        private val WORD_BREAK = Regex("[^\\p{L}\\p{N}']+")

        /** Lowercase, without accents, curly apostrophes or extra spaces: "  Piñata " and "pinata" match. */
        fun normalize(text: String): String =
            Normalizer.normalize(text.replace('’', '\''), Normalizer.Form.NFD).replace(MARKS, "")
                .lowercase(Locale.ROOT).trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

        /**
         * The word to search for when the emoji panel opens: the word the cursor sits right after, or right
         * after one space past it ("pizza|" and "pizza |"), lowercase; null with anything else there, with
         * a word of one letter, or with one too long to be a query. Only what is before the cursor counts.
         */
        fun wordAtCursor(beforeCursor: String): String? {
            val text = if (beforeCursor.endsWith(' ') && !beforeCursor.endsWith("  ")) beforeCursor.dropLast(1) else beforeCursor
            val word = text.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }.trim('\'', '’')
            if (word.length < 2 || word.length > MAX_QUERY_CHARS || !text.endsWith(word)) return null
            return word.lowercase(Locale.ROOT)
        }

        /**
         * The query after [action], or null for a key that doesn't edit it (Enter, shift, the layer
         * keys). Delete takes one whole character, as the Delete key does. A space never starts the
         * query or follows another, and the query stops at [MAX_QUERY_CHARS].
         */
        fun edit(query: String, action: KeyAction): String? = when (action) {
            is KeyAction.Text -> if (query.length + action.value.length > MAX_QUERY_CHARS) query else query + action.value
            KeyAction.Space -> if (query.isEmpty() || query.endsWith(' ') || query.length >= MAX_QUERY_CHARS) query else "$query "
            KeyAction.Backspace -> TypingRules.dropLastGrapheme(query)
            else -> null
        }
    }
}
