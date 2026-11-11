package dev.localtype.keyboard

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A read-only frequency dictionary. Words are kept in two orders: alphabetical, so a typed prefix
 * resolves to a contiguous range by binary search, and by frequency, for suggestions with no prefix.
 * A language with accents also keeps the words by their bare spelling, so a prefix typed without an
 * accent still finds them ("manan" completes to "mañana").
 */
class WordDictionary private constructor(
    private val sortedWords: Array<String>,
    private val sortedScores: IntArray,
    private val byFrequency: List<String>,
    private val folded: Folded? = null,
) {
    /** Bare spellings in order, each with its word's position in [sortedWords]. */
    private class Folded(val language: LanguageProfile, val keys: Array<String>, val positions: IntArray)

    val size: Int get() = sortedWords.size

    /** Frequency score in 0..[MAX_SCORE], log-scaled so common words don't drown out context. */
    fun score(word: String): Int {
        val index = sortedWords.asList().binarySearch(word)
        return if (index >= 0) sortedScores[index] else 0
    }

    operator fun contains(word: String): Boolean = sortedWords.asList().binarySearch(word) >= 0

    /** Every word, alphabetically. */
    fun allWords(): List<String> = sortedWords.asList()

    /**
     * Up to [limit] words starting with [prefix], most frequent first. In a language with accents a
     * bare letter in [prefix] also matches its accented forms, while an accent typed is kept.
     */
    fun completions(prefix: String, limit: Int): List<Pair<String, Int>> {
        if (prefix.isEmpty()) return byFrequency.take(limit).map { it to score(it) }
        if (folded != null) return foldedCompletions(folded, prefix, limit)
        var low = sortedWords.asList().binarySearch(prefix).let { if (it >= 0) it else -it - 1 }
        val matches = ArrayList<Pair<String, Int>>()
        while (low < sortedWords.size && sortedWords[low].startsWith(prefix)) {
            matches += sortedWords[low] to sortedScores[low]
            low++
        }
        return matches.sortedByDescending { it.second }.take(limit)
    }

    /**
     * Whether [word] completes [prefix] the way [completions] matches: literally, or in a language with accents with
     * each bare letter of [prefix] also standing for its accented forms ("fail" for "fáilte"), an accent typed kept.
     */
    fun completesPrefix(word: String, prefix: String): Boolean {
        if (word.startsWith(prefix)) return true
        val language = folded?.language ?: return false
        return foldedMatch(language, word, prefix)
    }

    /**
     * [word] begins the way [prefix] was typed: each typed character is the word's own, or a bare letter standing for
     * its accented form, and a mark the language's lists spell with ([LanguageProfile.dropped]: the non-joiner in
     * "می‌رود") may sit in the word where nothing was typed. An accent or a mark that was typed must be there.
     */
    private fun foldedMatch(language: LanguageProfile, word: String, prefix: String): Boolean {
        var j = 0
        for (p in prefix) {
            while (j < word.length && word[j] != p && word[j] in language.dropped) j++
            if (j >= word.length) return false
            val w = word[j]
            if (w != p && !(language.fold(p) == p && language.fold(w) == p)) return false
            j++
        }
        return true
    }

    private fun foldedCompletions(folded: Folded, prefix: String, limit: Int): List<Pair<String, Int>> {
        val language = folded.language
        val bare = language.fold(prefix)
        var low = folded.keys.asList().binarySearch(bare).let { if (it >= 0) it else -it - 1 }
        val matches = ArrayList<Pair<String, Int>>()
        while (low < folded.keys.size && folded.keys[low].startsWith(bare)) {
            val position = folded.positions[low]
            val word = sortedWords[position]
            if (foldedMatch(language, word, prefix)) {
                matches += word to sortedScores[position]
            }
            low++
        }
        return matches.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).take(limit)
    }

    companion object {
        const val MAX_SCORE = 100
        val EMPTY = WordDictionary(emptyArray(), IntArray(0), emptyList())

        /**
         * Parses `word<TAB>count` lines. Blank lines and `#` comments are ignored. [language]'s
         * accents decide whether bare prefixes find accented words; English has none.
         */
        fun parse(lines: Sequence<String>, language: LanguageProfile = LanguageProfile.ENGLISH): WordDictionary {
            val entries = lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@mapNotNull null
                    val count = line.substring(tab + 1).trim().toLongOrNull() ?: return@mapNotNull null
                    line.substring(0, tab).lowercase() to count
                }
                .distinctBy { it.first }
                .toList()
            if (entries.isEmpty()) return EMPTY
            val maxLog = ln(entries.maxOf { it.second }.toDouble() + 1.0)
            val scored = entries.map { (word, count) ->
                word to (ln(count.toDouble() + 1.0) / maxLog * MAX_SCORE).roundToInt()
            }
            val sorted = scored.sortedBy { it.first }
            val sortedWords = sorted.map { it.first }.toTypedArray()
            val folded = if (!language.folding) null else {
                val order = sortedWords.indices.sortedWith(compareBy<Int> { language.fold(sortedWords[it]) }.thenBy { it })
                Folded(language, Array(order.size) { language.fold(sortedWords[order[it]]) }, order.toIntArray())
            }
            return WordDictionary(
                sortedWords = sortedWords,
                sortedScores = sorted.map { it.second }.toIntArray(),
                byFrequency = scored.sortedByDescending { it.second }.map { it.first },
                folded = folded,
            )
        }
    }
}
