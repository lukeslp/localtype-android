package dev.localtype.keyboard

/** Explicit dictionary entries in a reserved journal row, separate from observed word pairs. */
object AddedWords {
    // A nonword context can never be inferred from editor text. Existing snapshots retain it.
    const val CONTEXT = AdaptiveNgramModel.ADDED_WORDS_CONTEXT
    const val VERSION = "added-words-v1"
    /**
     * Its own bound, above an ordinary row's [LearnedPairState.MAX_FOLLOWERS], so a personal word list can be
     * imported whole (64 through 0.5.48). [LearnedPairState] holds the reserved row to this instead.
     */
    const val MAX_WORDS = 1_000
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

    /** What an import did with each line it was given; [added] pairs each new word with the spelling it came in. */
    data class Import(val added: List<Pair<String, String>>, val existing: Int, val invalid: Int, val blocked: Int, val full: Int) {
        val skipped: Int get() = existing + invalid + blocked + full
    }

    /**
     * [add] for a whole list, in its order: the same validation word by word, one read of the store, and nothing
     * past [MAX_WORDS]. A word [isBlocked] names stays out, as the Add button keeps it out, and a spelling given
     * twice counts once as added and then as existing.
     */
    fun addAll(
        store: AdaptiveNgramModel.LearningStore,
        inputs: List<String>,
        language: LanguageProfile = LanguageProfile.ENGLISH,
        isBlocked: (String) -> Boolean = { false },
    ): Import {
        val known = words(store.entries()).toHashSet()
        val added = ArrayList<Pair<String, String>>()
        var existing = 0
        var invalid = 0
        var blocked = 0
        var full = 0
        for (input in inputs) {
            val word = normalize(input, language)
            when {
                word == null -> invalid++
                word in known -> existing++
                isBlocked(word) -> blocked++
                known.size >= MAX_WORDS -> full++
                else -> {
                    store.record(CONTEXT, word)
                    known += word
                    added += word to input.trim()
                }
            }
        }
        return Import(added, existing, invalid, blocked, full)
    }
}
