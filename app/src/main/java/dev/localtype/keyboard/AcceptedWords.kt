package dev.localtype.keyboard

import dev.localtype.keyboard.SpellCheckRules.Result
import dev.localtype.keyboard.SpellCheckRules.Verdict

/**
 * Words the spell checker takes as known beyond [SpellCheckRules]' lists: the keyboard's reviewed current words
 * (`current_words.txt`) and the words in Android's user dictionary, where the system's Add to dictionary puts them.
 * Pure and immutable; it holds only those words, in memory.
 *
 * Matching: an entry in lowercase ("gmail") accepts any capitalization of itself. An entry with capitals
 * ("iPhone", "LocalType") accepts that spelling, the word in all capitals and the spelling with a capital first
 * letter; typed all in lowercase ("iphone") it is not accepted, and the entry is offered as the first suggestion
 * instead. A possessive ('s, s') of an accepted word is accepted, as [SpellCheckRules] does for its own lists.
 */
class AcceptedWords private constructor(
    /** Entries stored in lowercase: any capitalization of them is the word. */
    private val anyCase: Set<String>,
    /** Entries with capitals, by their lowercase form. */
    private val cased: Map<String, List<String>>,
) {
    val size: Int get() = anyCase.size + cased.values.sumOf { it.size }

    fun knows(typed: String): Boolean {
        val word = clean(typed)
        if (matches(word)) return true
        val stem = possessiveStem(word) ?: return false
        return matches(stem)
    }

    /** The entry a word typed all in lowercase stands for, when the entry has capitals ("iphone" → "iPhone"). */
    fun spelling(typed: String): String? {
        val word = clean(typed)
        if (word.isEmpty() || word != word.lowercase() || word in anyCase) return null
        cased[word]?.let { return it.first() }
        val stem = possessiveStem(word) ?: return null
        if (stem in anyCase) return null
        return cased[stem]?.first()?.plus(word.substring(stem.length))
    }

    /**
     * [result] for [typed], with these words taken into account: an accepted word is known, whatever the lists said;
     * a misspelled word in lowercase that an entry spells with capitals gets that entry first. Anything else stays.
     */
    fun apply(typed: String, result: Result, limit: Int = SpellCheckRules.MAX_SUGGESTIONS): Result = when {
        result.verdict == Verdict.KNOWN -> result
        knows(typed) -> KNOWN
        result.verdict == Verdict.MISSPELLED -> spelling(typed)?.let { fix ->
            Result(Verdict.MISSPELLED, (listOf(fix) + result.suggestions.filter { it != fix }).take(limit.coerceIn(0, SpellCheckRules.MAX_SUGGESTIONS)))
        } ?: result
        else -> result
    }

    private fun matches(word: String): Boolean {
        if (word.isEmpty()) return false
        val lower = word.lowercase()
        if (lower in anyCase) return true
        val forms = cased[lower] ?: return false
        return forms.any { word == it || word == it.uppercase() || word == it.replaceFirstChar(Char::uppercaseChar) }
    }

    companion object {
        /** The most words read from one source; the rest are left out. */
        const val MAX_WORDS = 20_000
        /** Android's user dictionary takes words of up to 48 characters. */
        const val MAX_LENGTH = 48
        val EMPTY = AcceptedWords(emptySet(), emptyMap())
        private val KNOWN = Result(Verdict.KNOWN)

        /** Apostrophes straightened and a trailing apostrophe or hyphen dropped, as [SpellCheckRules] reads a word. */
        private fun clean(word: String) = word.trim().replace('’', '\'').trimEnd('\'', '-')

        private fun possessiveStem(word: String): String? = when {
            word.endsWith("'s") || word.endsWith("'S") -> word.dropLast(2)
            word.endsWith("s'") || word.endsWith("S'") -> word.dropLast(1)
            else -> null
        }?.takeIf { it.isNotEmpty() }

        /** Single words only: blank entries, phrases and over-long entries are left out. */
        fun of(words: Iterable<String>): AcceptedWords {
            val anyCase = HashSet<String>()
            val cased = HashMap<String, MutableList<String>>()
            var count = 0
            for (entry in words) {
                if (count >= MAX_WORDS) break
                val word = clean(entry)
                if (word.isEmpty() || word.length > MAX_LENGTH || word.any { it.isWhitespace() || it.isISOControl() }) continue
                count++
                val lower = word.lowercase()
                if (word == lower) anyCase += word else cased.getOrPut(lower) { ArrayList(1) }.let { if (word !in it) it += word }
            }
            return AcceptedWords(anyCase, cased)
        }

        /**
         * Whether a user-dictionary entry with [locale] applies to [language]: no locale (Android's "All languages")
         * applies to every language, and otherwise the locale's language code must be the language's ("en", "en_US",
         * "pt-BR").
         */
        fun appliesTo(locale: String?, language: LanguageProfile): Boolean {
            val code = locale?.trim()?.substringBefore('_')?.substringBefore('-')?.lowercase()
            return code.isNullOrEmpty() || code == language.code
        }

        /** The words of Android user-dictionary rows (word, locale) that apply to [language]. */
        fun forLanguage(rows: Iterable<UserWord>, language: LanguageProfile): AcceptedWords =
            of(rows.asSequence().filter { appliesTo(it.locale, language) }.mapNotNull { it.word }.asIterable())
    }
}

/** One row of Android's user dictionary, as read: the word and its locale (null for all languages). */
class UserWord(val word: String?, val locale: String?)
