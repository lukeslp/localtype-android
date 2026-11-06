package dev.localtype.keyboard


/** An apostrophe offer for an unrecognized -s spelling, never a claim about intended grammar. */
object PossessiveSuggestion {
    fun suggest(
        typed: String,
        dictionary: WordDictionary,
        lexicon: Lexicon,
        known: (String) -> Boolean = { false },
        kept: (String) -> Boolean = { false },
        blocked: (String) -> Boolean = { false },
    ): String? {
        // Existing plurals, apostrophes, identifiers, acronyms and deliberately saved forms stay theirs.
        if (typed.length !in 4..32 || typed.any { !it.isLetter() } || typed.drop(1).any { it.isUpperCase() }) return null
        val word = typed.lowercase()
        if (!word.endsWith('s') || word in dictionary || word in lexicon || known(word) || kept(word) || blocked(word)) return null
        val stem = word.dropLast(1)
        if (stem.endsWith('s') || blocked(stem) || SuggestionWordRules.isExcluded(stem)) return null
        if (dictionary.score(stem) < 40 && stem !in lexicon && !known(stem)) return null
        val offer = "$stem's"
        return offer.takeUnless { kept(it) || blocked(it) || SuggestionWordRules.isExcluded(it) }
    }
}
