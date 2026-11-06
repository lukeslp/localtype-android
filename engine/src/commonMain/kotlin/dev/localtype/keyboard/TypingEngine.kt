package dev.localtype.keyboard

/**
 * One language's typing for a keyboard host other than the Android service: the iPhone keyboard calls it
 * from Swift. It holds the language's word lists, corpus prior, corrector and learned pairs, and answers in
 * plain strings. Learned pairs stay in memory and leave only through [journal], in the learned-pair journal
 * format, for the host to keep in its own sandbox. The Android keyboard has its own stores and doesn't use this.
 *
 * Not thread-safe: build it anywhere, then use it from one thread.
 */
class TypingEngine(
    val language: LanguageProfile,
    words: String,
    swears: String?,
    lexicon: String,
    modern: String?,
    prior: ByteArray?,
    journal: ByteArray?,
    /** Suggest swear words; off, they are still never corrected away. As Suggest swear words on Android. */
    private val suggestSwears: Boolean,
    /** `emoji_en.txt`, for an emoji in the strip's last slot; null offers none. English only, as on Android. */
    emoji: String? = null,
    /** Saved tap offsets ([offsets]), or null to start without any. */
    offsets: String? = null,
) {
    /** Where the person's finger lands on each letter key, learned from real words they typed; scores slips only. */
    private val touchOffsets = TouchOffsets(language.geometry).apply { offsets?.let { load(TouchOffsets.decode(it)) } }
    private val emojiSuggestions: EmojiSuggestions? =
        emoji?.takeIf { language.english }?.let { EmojiSuggestions.parse(it.lineSequence()) }
    private val learned = MemoryLearningStore(journal?.let { LearnedPairJournal.decode(it).state.entries() }.orEmpty())
    private val swearWords: Set<String> = swears.orEmpty().lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
    private val dictionary: WordDictionary = WordDictionary.parse(
        (words.lineSequence() + modern.orEmpty().lineSequence() + (if (suggestSwears) swears.orEmpty().lineSequence() else emptySequence())),
        language,
    )
    private val known: Lexicon = Lexicon.parse(
        lexicon.lineSequence() + modern.orEmpty().lineSequence().map { it.substringBefore('\t') },
        expectedChars = lexicon.length + (modern?.length ?: 0),
    )
    private val corpusPrior: CorpusPrior? = prior?.let { CorpusPrior.read(it) }
    private var model: AdaptiveNgramModel = buildModel()

    /** The ranker over the learned pairs, rebuilt when Added words changes them other than by typing. */
    private fun buildModel(): AdaptiveNgramModel =
        if (language.english) AdaptiveNgramModel(learned, dictionary = dictionary, prior = corpusPrior)
        else AdaptiveNgramModel(learned, seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
            prior = corpusPrior, priorWeights = AdaptiveNgramModel.PriorWeights(weight = language.priorWeight),
            openingMarks = language.openingMarks, oneLetterWords = language.oneLetterWords)
    private val corrector = Corrector(
        dictionary = { dictionary },
        isKnown = { model.knows(it) || it in swearWords },
        lexicon = { known },
        contextCount = { previous, word -> model.pairCount(previous, word) },
        blocked = { !suggestSwears && it in swearWords },
        isKept = { model.isAddedWord(it) },
        touchOffsets = { touchOffsets },
        language = { language },
    )

    /** The word being typed as the keys typed it, with where each finger lifted, as Android's word trace. */
    private val traceText = StringBuilder()
    private val traceTouches = ArrayList<Touch?>()

    /** The last word [correction] looked at with a whole trace, and whether it changed it; tap offsets learn from it. */
    private var traced: TracedWord? = null
    private class TracedWord(val word: String, val touches: List<Touch?>, val corrected: Boolean)

    /** The language's name in itself ("English", "Español") and its letter rows, top first. */
    val nativeName: String get() = language.nativeName
    val keyRows: List<String> get() = language.keyRows

    /** What a hold on [letter] offers, as on Android: the language's own accents first ("é" on e in Spanish). */
    fun holds(letter: String): List<String> = letter.singleOrNull()?.let { LetterAccents.holds(language, it.lowercaseChar()) }.orEmpty()

    /** The word being typed: the letters right before the caret. */
    fun activePrefix(beforeCursor: String): String = model.activePrefix(beforeCursor)

    /** The caret starts a sentence: an empty field, or after a sentence end (and ¿ or ¡ in Spanish). */
    fun isSentenceStart(beforeCursor: String): Boolean = model.isSentenceStart(beforeCursor)

    /** Up to [limit] words for the strip, best first, cased to match what is typed and the sentence. */
    fun suggestions(beforeCursor: String, limit: Int = 3): List<String> = suggestions(beforeCursor, limit, null)

    /**
     * As [suggestions], with Chime's words for the same text ([ChimePredictor.predict]) leading when
     * [neural] is given: the Android keyboard's [NeuralRanking], which keeps the n-gram words behind them
     * and a slot for a word the person has typed after the previous one at least twice. Without
     * [capitalizeSentences] (Capitalize automatically off) a sentence start doesn't capitalize them.
     */
    fun suggestions(beforeCursor: String, limit: Int, neural: List<String>?, capitalizeSentences: Boolean = true): List<String> {
        val prefix = model.activePrefix(beforeCursor)
        val sentenceStart = capitalizeSentences && model.isSentenceStart(beforeCursor)
        val ranked = model.predict(beforeCursor, limit + 4)
        val words = if (neural == null) ranked.filter { offerable(it) } else {
            val learned = model.lastCompletedWord(beforeCursor.dropLast(prefix.length))?.let { model.learnedFollowers(it) }.orEmpty()
            NeuralRanking.rankWithSources(neural, ranked, learned, prefix, if (suggestSwears) emptySet() else swearWords, limit,
                added = model.addedWords(prefix), language = language).map { it.word }
        }
        return words.take(limit).map { TextRules.applyCase(it, prefix, sentenceStart) }
    }

    /** An emoji for the word or short phrase ending at the caret, and how many characters before any trailing spaces it replaces. */
    class EmojiOffer(val emoji: String, val replaceLength: Int, val trailing: String)

    /**
     * The emoji the strip's last slot offers for [beforeCursor] ("coffee" → ☕), or null. [lookupAs] reads the
     * word being typed as its correction, so a misspelling offers the emoji of the fix.
     */
    fun emoji(beforeCursor: String, lookupAs: String? = null): EmojiOffer? {
        val match = emojiSuggestions?.match(beforeCursor, model.activePrefix(beforeCursor), limit = 1, lookupAs = lookupAs) ?: return null
        return EmojiOffer(match.options.firstOrNull() ?: return null, match.replaceLength, match.trailing)
    }

    /**
     * The fix for the word that ends [beforeSeparator] when a space or punctuation follows it, cased as it was
     * typed, or null to keep the word. Only clear winners: the corrector's automatic answer. Otherwise, with
     * [capitalizeI], a lone English "i" becomes "I", as on Android when automatic capitalization is on.
     * Without [capitalizeSentences] a fix at a sentence start keeps the typed word's case.
     */
    fun correction(beforeSeparator: String, capitalizeI: Boolean = true, capitalizeSentences: Boolean = true): String? {
        val prefix = model.activePrefix(beforeSeparator)
        if (prefix.isEmpty()) return null
        val sentenceStart = model.isSentenceStart(beforeSeparator)
        val previous = model.lastCompletedWord(beforeSeparator)
        val afterComma = beforeSeparator.dropLast(prefix.length).trimEnd().endsWith(',')
        val touches = touches(prefix)
        val fix = corrector.correct(prefix, touches, previous, sentenceStart, afterComma)
        val answer = fix?.let { TextRules.applyCase(it, prefix, capitalizeSentences && sentenceStart) }
            ?: if (capitalizeI && language.english && prefix == "i") "I" else null
        traced = touches?.let { TracedWord(prefix, it, corrected = answer != null && answer != prefix) }
        return answer
    }

    /**
     * Learns the word that ends [beforeSeparator] after the word before it; true if it was learned. A word typed
     * whole by tapping and left as typed also teaches the tap offsets, as on Android.
     */
    fun learn(beforeSeparator: String): Boolean {
        val learned = model.learnTypedWord(beforeSeparator, persist = true)
        if (learned) {
            corrector.forgetRecentAnswers()
            traced?.takeIf { !it.corrected && it.word == model.activePrefix(beforeSeparator) }?.let { touchOffsets.record(it.word, it.touches) }
        }
        traced = null
        return learned
    }

    /**
     * A character a key typed. [dx] and [dy] are where the finger lifted from the center of the key's cell, in key
     * widths and rows; NaN when it wasn't a tap on that key (a hold's choice, VoiceOver). A character that can't be
     * part of a word starts the trace over.
     */
    fun typed(text: String, dx: Double, dy: Double) {
        val char = text.singleOrNull()
        if (char == null || !(char.isLetterOrDigit() || char == '\'' || char == '’' || char == '-')) return resetTrace()
        traceText.append(char)
        traceTouches.add(if (dx.isFinite() && dy.isFinite()) language.geometry.touchOn(char.lowercaseChar(), dx.toFloat(), dy.toFloat()) else null)
    }

    /** Delete took the last character typed. */
    fun deleted() {
        if (traceText.isEmpty()) return resetTrace()
        traceText.deleteAt(traceText.length - 1)
        traceTouches.removeAt(traceTouches.size - 1)
    }

    /** Anything else changed the text (a strip choice, a correction, a new line): the next word's trace starts empty. */
    fun resetTrace() {
        traceText.clear()
        traceTouches.clear()
        traced = null
    }

    /**
     * The keys typed [prefix] here, in this word: text the keyboard didn't type (saved text, a paste, a word the
     * caret landed after) is never corrected or learned, as on Android.
     */
    fun typedHere(prefix: String): Boolean = prefix.isNotEmpty() && traceText.endsWith(prefix)

    /** The caret moved: keeps the trace only while the text before the caret still ends with it. */
    fun keepTrace(beforeCursor: String) {
        if (!beforeCursor.endsWith(traceText)) resetTrace()
    }

    /** The touches of [prefix] when the trace typed exactly it, every letter by a tap; null otherwise. */
    private fun touches(prefix: String): List<Touch?>? {
        if (!traceText.endsWith(prefix) || prefix.isEmpty()) return null
        return traceTouches.subList(traceTouches.size - prefix.length, traceTouches.size).toList()
    }

    private val multiword: MultiwordCorrection by lazy { MultiwordCorrection(dictionary) }

    /**
     * Two words for the one that ends [beforeSeparator] when a space was missed or hit as a nearby letter
     * ("ofbthe" → "of the"), as Android's missed-space offer: something to show with Apply, never applied by itself.
     * English only, and null for a known, added or kept word.
     */
    fun split(beforeSeparator: String): String? {
        if (!language.english) return null
        val raw = model.activePrefix(beforeSeparator)
        if (raw.isEmpty()) return null
        val word = raw.lowercase()
        // A capital only at the start (a sentence start) is fine; any other capital marks a name or an acronym.
        val protected = raw.drop(1) != word.drop(1) || word in known || model.knows(word) || model.isAddedWord(word) || word in swearWords
        val offer = multiword.suggest(
            word, protected,
            context = { previous, next -> corpusPrior?.followers(previous)?.firstOrNull { it.word == next }?.logProbability },
            blocked = { !suggestSwears && it in swearWords },
            repair = { part -> listOfNotNull(corrector.suggest(part)) },
        ) ?: return null
        return if (raw.first().isUpperCase()) offer.text.replaceFirstChar { it.uppercaseChar() } else offer.text
    }

    /** The tap offsets, as text for the host to save ([TouchOffsets.encode]). */
    fun offsets(): String = touchOffsets.encode()

    /** How many letter taps the offsets have learned from. */
    val tapSamples: Int get() = touchOffsets.entries().sumOf { it.count }

    /** Learns [word], tapped in the strip, after what stands before it in [beforeWord]. */
    fun accept(beforeWord: String, word: String) {
        val previous = model.lastCompletedWord(beforeWord) ?: AdaptiveNgramModel.START.takeIf { model.isSentenceStart(beforeWord) }
        model.accept(previous, AdaptiveNgramModel.normalize(word))
        corrector.forgetRecentAnswers()
    }

    /** Everything learned, as the learned-pair journal's bytes, for the host to save. */
    fun journal(): ByteArray = LearnedPairJournal.compact(learned.entries())

    /** How many word pairs have been learned. */
    val learnedPairs: Int get() = learned.entries().size

    /** Settings' Added words as this language keeps them: normalized and sorted. */
    val addedWords: List<String> get() = AddedWords.words(learned.entries())

    /**
     * Makes this language's added words exactly [words], as Android's Added words: a new one is added once (one use,
     * never a pair), so suggestions offer it and corrections leave it alone, and a removed one goes with every pair
     * that has it as the next word. A word that isn't one word of letters in this language, or that suggestions
     * leave out, is skipped. True when anything changed, so the host saves.
     */
    fun syncAddedWords(words: List<String>): Boolean {
        val wanted = words.mapNotNull { AddedWords.normalize(it, language) }.distinct().take(AddedWords.MAX_WORDS)
        val current = addedWords
        val removed = current.filter { it !in wanted }.toSet()
        val added = wanted.filter { it !in current }
        if (removed.isEmpty() && added.isEmpty()) return false
        learned.entries().filter { it.second in removed }.forEach { learned.remove(it.first, it.second) }
        added.forEach { AddedWords.add(learned, it, language) }
        model = buildModel()
        corrector.forgetRecentAnswers()
        return true
    }

    /** Forgets everything learned; the host deletes its saved journal too. */
    fun forget() {
        model.clearLearned(if (language.english) AdaptiveNgramModel.DEFAULT_SEED_PHRASES else emptyList())
        touchOffsets.clear()
        resetTrace()
        corrector.forgetRecentAnswers()
    }

    private fun offerable(word: String): Boolean =
        (suggestSwears || word !in swearWords) && !SuggestionWordRules.isExcluded(word, language)

    /** Learned pairs in memory, saved by the host through [journal]. */
    private class MemoryLearningStore(entries: List<Triple<String, String, Int>>) : AdaptiveNgramModel.LearningStore {
        private val state = LearnedPairState(entries)
        override fun entries(): List<Triple<String, String, Int>> = state.entries()
        override fun record(previous: String, next: String) = state.record(previous, next)
        override fun clear() = state.clear()
        fun remove(previous: String, next: String) = state.remove(previous, next)
    }
}
