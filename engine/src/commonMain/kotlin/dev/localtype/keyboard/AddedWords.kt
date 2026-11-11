package dev.localtype.keyboard

/** Explicit dictionary entries in a reserved journal row, separate from observed word pairs. */
object AddedWords {
    // A nonword context can never be inferred from editor text. Existing snapshots retain it.
    const val CONTEXT = AdaptiveNgramModel.ADDED_WORDS_CONTEXT
    const val VERSION = "added-words-v1"
    const val MAX_WORDS = LearnedPairState.MAX_FOLLOWERS
    // Letters, with the marks and the non-joiner some languages write inside a word ([WordRule]).
    private val WORD = Regex("\\p{L}[\\p{L}\\p{Mn}\u200C]*(?:['-]\\p{L}[\\p{L}\\p{Mn}\u200C]*)*'?")

    enum class Result { ADDED, EXISTS, INVALID, FULL }

    /** Whole-input validation: never save just the first word of a pasted phrase or URL. [language] sets the one-letter words and exclusions. */
    fun normalize(input: String, language: LanguageProfile = LanguageProfile.ENGLISH): String? {
        val word = AdaptiveNgramModel.normalize(input.trim())
        return word.takeIf {
            it.length in 1..80 && WORD.matches(it) &&
                (it.length > 1 || it in language.oneLetterWords) && !SuggestionWordRules.isExcluded(it, language)
        }
    }

    fun words(entries: List<Triple<String, String, Int>>): List<String> = entries.asSequence()
        .filter { it.first == CONTEXT && it.third > 0 }
        .map { it.second }.distinct().sorted().toList()

    /** Idempotent additions never inflate usage counts or evict another deliberately added word. */
    fun add(store: AdaptiveNgramModel.LearningStore, input: String, language: LanguageProfile = LanguageProfile.ENGLISH): Result {
        val word = normalize(input, language) ?: return Result.INVALID
        val existing = words(store.entries())
        if (word in existing) return Result.EXISTS
        if (existing.size >= MAX_WORDS) return Result.FULL
        store.record(CONTEXT, word)
        return Result.ADDED
    }
}
