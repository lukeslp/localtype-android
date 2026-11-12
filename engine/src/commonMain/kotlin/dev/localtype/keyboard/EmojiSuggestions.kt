package dev.localtype.keyboard


/**
 * Suggests an emoji for the word or short phrase being written or just finished ("coffee" → ☕,
 * "thumbs up" → 👍). Tapping one swaps that text for the emoji. Pure and small: a curated list, no
 * model and no network.
 */
class EmojiSuggestions private constructor(private val byPhrase: Map<String, List<String>>) {
    val size: Int get() = byPhrase.size

    /** Every phrase with its emoji, best first, which the emoji panel's search ranks first. */
    val phrases: Map<String, List<String>> get() = byPhrase

    /**
     * Emoji for the text before the cursor, and how much of it they replace: the last [replaceLength]
     * characters before any [trailing] spaces. The spaces stay after the emoji.
     */
    data class Match(val options: List<String>, val replaceLength: Int, val trailing: String)

    /**
     * Matches the longest phrase (up to [MAX_PHRASE_WORDS] words) ending at the word being typed
     * ([prefix]) or, with nothing being typed, at the word just finished by a space. [lookupAs] also
     * reads the current word as another spelling, so a misspelling can offer the emoji of its
     * correction; the word as typed is tried first.
     */
    fun match(beforeCursor: String, prefix: String, limit: Int = 2, lookupAs: String? = null): Match? {
        val trailing = if (prefix.isEmpty()) beforeCursor.takeLastWhile { it == ' ' } else ""
        // Only right after finishing a word: "coffee " suggests, "coffee is " does not.
        if (prefix.isEmpty() && trailing.isEmpty()) return null
        val text = beforeCursor.dropLast(trailing.length)
        val words = WORD.findAll(text.takeLast(TAIL_CHARS)).toList()
        if (words.isEmpty()) return null
        val offset = text.length - minOf(text.length, TAIL_CHARS)
        val last = words.last()
        if (offset + last.range.last + 1 != text.length) return null
        // Walk back over words separated only by spaces.
        var first = words.lastIndex
        while (first > 0 && words.lastIndex - first + 1 < MAX_PHRASE_WORDS &&
            text.substring(offset + words[first - 1].range.last + 1, offset + words[first].range.first).let { gap -> gap.isNotEmpty() && gap.all { it == ' ' } }
        ) first--
        for (start in first..words.lastIndex) {
            val parts = words.subList(start, words.size).map { it.value }
            // The word as typed first: "rofl" has an emoji of its own, whatever the corrector would make of it
            // (through 0.5.53 only the correction was looked up, so "rofl", offered "of", showed nothing).
            val spellings = if (lookupAs == null) listOf(parts) else listOf(parts, parts.dropLast(1) + lookupAs)
            for (spelling in spellings) {
                val key = spelling.joinToString(" ") { normalize(it) }
                if (spelling.size == 1 && key.length < MIN_WORD_LENGTH) continue
                val options = byPhrase[key] ?: continue
                return Match(options.take(limit), text.length - (offset + words[start].range.first), trailing)
            }
        }
        return null
    }

    /** Just the emoji for [beforeCursor], or empty. */
    fun forContext(beforeCursor: String, prefix: String, limit: Int = 2): List<String> =
        match(beforeCursor, prefix, limit)?.options.orEmpty()

    companion object {
        val EMPTY = EmojiSuggestions(emptyMap())
        private const val MIN_WORD_LENGTH = 2
        private const val MAX_PHRASE_WORDS = 3
        private const val TAIL_CHARS = 80
        private val WORD = Regex("[\\p{L}][\\p{L}'’-]*")

        private fun normalize(word: String) = word.replace('’', '\'').lowercase().trim('\'')

        /** Parses `word or phrase<TAB>emoji emoji` lines; blanks and `#` comments are ignored. */
        fun parse(lines: Sequence<String>): EmojiSuggestions = EmojiSuggestions(
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@mapNotNull null
                    val phrase = line.substring(0, tab).trim().split(Regex("\\s+")).joinToString(" ") { normalize(it) }
                    val emoji = line.substring(tab + 1).trim().split(' ').filter { it.isNotBlank() }
                    if (emoji.isEmpty()) null else phrase to emoji
                }
                .toMap(),
        )
    }
}
