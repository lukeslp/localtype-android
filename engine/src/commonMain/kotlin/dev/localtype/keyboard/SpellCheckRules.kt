package dev.localtype.keyboard

/**
 * What the keyboard says about spelling when Android asks it to check text in another app (the red underline).
 * It uses the typing engine's own lists and corrector, and it is deliberately timid: a word is called misspelled
 * only when the keyboard would not accept it, and anything it cannot judge is left alone, because a wrong
 * underline is worse than a missed one.
 *
 * The text belongs to another app. Nothing here learns from it, logs it or keeps it: an instance holds the word
 * lists it was built from and nothing else, and the corrector's small memo of recent answers is emptied after
 * every word. Everything is read-only after construction, so one instance may be shared by any number of threads.
 */
class SpellCheckRules(
    val language: LanguageProfile,
    /** The suggestion list without the swear words, as the keyboard loads it with Suggest swear words off. */
    private val words: WordDictionary,
    private val lexicon: Lexicon,
    swears: Collection<String>,
) {
    /** Recognized when typed, never offered. */
    private val swears: Set<String> = swears.map { AdaptiveNgramModel.normalize(it) }.toSet()

    private val corrector = Corrector(
        dictionary = { words },
        isKnown = { it in this.swears },
        lexicon = { lexicon },
        blocked = { it in this.swears },
        language = { language },
    )

    init {
        // Built here, on the loading thread, so the first checked word does not pay for the index.
        corrector.prepare(words, lexicon)
    }

    enum class Verdict {
        /** In the lists: the keyboard accepts it. */
        KNOWN,
        /** Not judged: a name, an identifier, another language's letters, or anything else left alone. */
        UNSURE,
        MISSPELLED,
    }

    /** Where a word sits, as far as the handed text shows. */
    enum class Position {
        SENTENCE_START,
        MID_SENTENCE,
        /** The first word of the text: what came before it in the app is not known. */
        UNKNOWN,
    }

    class Result(val verdict: Verdict, val suggestions: List<String> = emptyList())

    /** One word of a checked text: [start] and [length] in UTF-16 units of that text. */
    class Word(val start: Int, val length: Int, val result: Result)

    /**
     * One word on its own. A capitalized word is called misspelled only where a sentence starts or [position] is
     * unknown, and only when the corrector has a fix for it; with no fix it is taken for a name.
     */
    fun checkWord(typed: String, position: Position = Position.UNKNOWN, limit: Int = MAX_SUGGESTIONS): Result {
        val token = typed.trim().trimEnd('\'', '’', '-')
        if (token.length < 2 || token.length > MAX_WORD_LENGTH) return UNSURE
        if (token.any { it.isWhitespace() || it.isDigit() || it == '-' } || DomainTyping.protected(token, null)) return UNSURE
        val word = AdaptiveNgramModel.normalize(token)
        if (!word.first().isLetter() || word.any { it != '\'' && it !in language.letters }) return UNSURE
        // All caps and capitals inside a word ("NASA", "McCoy") are acronyms and names.
        if (token.drop(1).any { it.isUpperCase() }) return UNSURE
        val capitalized = token.first().isUpperCase()
        val named = capitalized && position == Position.MID_SENTENCE
        if (language.english && !named) {
            // "dont" is in the lists as a word, but the keyboard always types "don't" for it.
            Contractions.propose(word, previous = null, sentenceStart = false)?.takeIf { it.automatic }?.let { fix ->
                return Result(Verdict.MISSPELLED, listOf(shaped(fix.word, capitalized)).take(limit.coerceAtLeast(0)))
            }
        }
        if (known(word)) return KNOWN
        if (named) return UNSURE
        // A slur is in no list. Leave it be instead of offering its neighbors.
        if (SuggestionWordRules.isExcluded(word, language)) return UNSURE
        val found = try {
            val snapshot = corrector.candidates(token, sentenceStart = position == Position.SENTENCE_START)
            (listOfNotNull(snapshot.legacy?.word) + snapshot.candidates.map { it.word }).distinct()
        } finally {
            corrector.forgetRecentAnswers()
        }
        val offered = found.filter { it != word && it !in swears && !SuggestionWordRules.isExcluded(it, language) }
        if (capitalized && offered.isEmpty()) return UNSURE
        return Result(Verdict.MISSPELLED, offered.take(limit.coerceIn(0, MAX_SUGGESTIONS)).map { shaped(it, capitalized) })
    }

    /**
     * Every word of [text] that was looked at, in order. A run of text that is a link, an email address, a handle,
     * a hashtag, an identifier or holds a digit is skipped whole, and so is a hyphenated word. Text past
     * [MAX_TEXT_LENGTH] is not checked.
     */
    fun check(text: String, limit: Int = MAX_SUGGESTIONS): List<Word> {
        val found = ArrayList<Word>()
        // Before this point nothing says whether a sentence is starting.
        val firstContent = text.indexOfFirst { it.isLetterOrDigit() || it in SENTENCE_ENDS }
        var at = 0
        while (at < text.length) {
            if (text[at].isWhitespace()) { at++; continue }
            var end = at
            while (end < text.length && !text[end].isWhitespace()) end++
            if (end > MAX_TEXT_LENGTH) break
            val run = text.substring(at, end)
            if (run.none { it.isDigit() } && !DomainTyping.protected(run, null)) {
                for (match in language.wordRule.word.findAll(run)) {
                    val token = match.value.trimEnd('\'', '’', '-')
                    if (token.isEmpty() || '-' in token) continue
                    val start = at + match.range.first
                    val position = if (start <= firstContent) Position.UNKNOWN else positionOf(text, start)
                    found += Word(start, token.length, checkWord(token, position, limit))
                }
            }
            at = end
        }
        return found
    }

    /** As the keyboard decides it: a sentence starts after . ! ? or a line break, with spaces and opening marks between. */
    private fun positionOf(text: String, start: Int): Position {
        var i = start - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        while (i >= 0 && text[i] in language.openingMarks) i--
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return when {
            i < 0 -> Position.UNKNOWN
            text[i] in SENTENCE_ENDS -> Position.SENTENCE_START
            else -> Position.MID_SENTENCE
        }
    }

    private fun known(word: String): Boolean {
        if (listed(word)) return true
        // The lists leave out 's forms, as the corrector knows: "friend's" and "parents'" are their stems' words.
        val stem = when {
            word.endsWith("'s") -> word.dropLast(2)
            word.endsWith("s'") -> word.dropLast(1)
            else -> return false
        }
        return stem.isNotEmpty() && listed(stem)
    }

    private fun listed(word: String) = word in words || word in lexicon || word in swears

    /** A suggestion in the typed word's shape: a capital first letter is kept, and English "i" is always "I". */
    private fun shaped(suggestion: String, capitalized: Boolean): String {
        val base = if (language.english && (suggestion == "i" || suggestion.startsWith("i'"))) "I" + suggestion.drop(1) else suggestion
        return if (capitalized) base.replaceFirstChar { it.uppercaseChar() } else base
    }

    companion object {
        const val MAX_SUGGESTIONS = 5
        const val MAX_WORD_LENGTH = 32
        const val MAX_TEXT_LENGTH = 4_000
        private const val SENTENCE_ENDS = ".!?\n"
        private val KNOWN = Result(Verdict.KNOWN)
        private val UNSURE = Result(Verdict.UNSURE)

        /** The languages the spell checker answers for. Any other locale gets no answer at all, never English's. */
        val LANGUAGES: List<LanguageProfile> = listOf(LanguageProfile.ENGLISH, LanguageProfile.SPANISH, LanguageProfile.PORTUGUESE)

        /** The language of an Android locale string: "en_US", "pt-BR" and "es" all name one; "fr", "" and null name none. */
        fun languageFor(locale: String?): LanguageProfile? {
            val code = locale?.trim()?.substringBefore('_')?.substringBefore('-')?.lowercase() ?: return null
            return LANGUAGES.firstOrNull { it.code == code }
        }

        /**
         * Builds the rules from the bundled lists' lines, the way the keyboard loads them: [words] is the
         * suggestion list (`word<TAB>count`), [modern] English's later additions (empty elsewhere), [known] every
         * recognized word, and [swears] the swear-word list, which is recognized and never suggested.
         */
        fun fromLines(
            language: LanguageProfile,
            words: List<String>,
            modern: List<String>,
            known: List<String>,
            swears: List<String>,
        ): SpellCheckRules = SpellCheckRules(
            language,
            WordDictionary.parse((words + modern).asSequence(), language),
            Lexicon.parse(known.asSequence() + modern.asSequence().map { it.substringBefore('\t') }),
            swears.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t').trim() },
        )
    }
}
