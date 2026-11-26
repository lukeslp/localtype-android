package dev.localtype.keyboard

/**
 * Settings' Learned words list: narrowing the learned pairs by the start of either word, the learned words that
 * start that way, and what forgetting one word everywhere removes. The stores apply the plan; this decides it.
 */
object LearnedWords {
    /** The pairs to draw: the first of them in the order given, and how many matched before the limit. */
    data class Shown(val rows: List<Triple<String, String, Int>>, val matching: Int)

    /**
     * [pairs] (the journal without the added-words row, most used first) whose previous or next word starts with
     * [query], and the first [limit] of them. A blank query matches every pair. The sentence-start marker is not a
     * word and never matches, so "start" finds "started", not every pair that opens a sentence.
     */
    fun shown(pairs: List<Triple<String, String, Int>>, query: String, limit: Int): Shown {
        val start = start(query)
        val matching = if (start.isEmpty()) pairs
            else pairs.filter { (previous, next, _) -> next.startsWith(start) || (isWord(previous) && previous.startsWith(start)) }
        return Shown(matching.take(limit), matching.size)
    }

    /** A learned word and its uses: the counts of the pairs it ends, which are the word count the model replays. */
    data class Word(val word: String, val uses: Int)

    /**
     * The words in [pairs] that start with [query], most used first and then alphabetically, at most [limit]. None
     * for a blank query: the list is the way to find one word, not a second copy of the pairs. A word seen only
     * before another (its own pair gone to the store's limits) is listed with no uses, so it can still be forgotten.
     */
    fun words(pairs: List<Triple<String, String, Int>>, query: String, limit: Int): List<Word> {
        val start = start(query)
        if (start.isEmpty()) return emptyList()
        val uses = HashMap<String, Int>()
        for ((previous, next, count) in pairs) {
            if (previous == AddedWords.CONTEXT) continue
            if (next.startsWith(start)) uses[next] = (uses[next] ?: 0) + count.coerceAtLeast(0)
            if (isWord(previous) && previous.startsWith(start)) uses.getOrPut(previous) { 0 }
        }
        return uses.map { (word, count) -> Word(word, count) }
            .sortedWith(compareByDescending<Word> { it.uses }.thenBy { it.word })
            .take(limit)
    }

    /**
     * What forgetting [word] removes: [pairs], every learned pair it is in on either side, and its saved capitals
     * unless [keepsSpelling]. The personal phrases holding it go through `PersonalLearningStore.removeWord`.
     */
    data class Forget(val word: String, val pairs: List<Pair<String, String>>, val keepsSpelling: Boolean)

    /**
     * The plan for [word] over [entries], the whole journal. The added-words row is left alone: an added word stays
     * added, with the capitals typed into Settings, and Remove added word is how it goes.
     */
    fun forget(entries: List<Triple<String, String, Int>>, word: String): Forget {
        val key = AdaptiveNgramModel.normalize(word)
        val added = entries.any { (previous, next, count) -> previous == AddedWords.CONTEXT && next == key && count > 0 }
        val pairs = entries.filter { (previous, next, _) -> previous != AddedWords.CONTEXT && (previous == key || next == key) }
            .map { (previous, next, _) -> previous to next }
        return Forget(key, pairs, keepsSpelling = added)
    }

    private fun start(query: String) = AdaptiveNgramModel.normalize(query.trim())

    private fun isWord(previous: String) = previous != AdaptiveNgramModel.START && previous != AddedWords.CONTEXT
}
