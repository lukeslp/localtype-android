package dev.localtype.keyboard

/**
 * One language's typing for a keyboard host other than the Android service: the iPhone keyboard calls it
 * from Swift. It holds the language's word lists, corpus prior, corrector and learned pairs, and answers in
 * plain strings. Learned pairs stay in memory and leave only through [journal], in the learned-pair journal
 * format, for the host to keep in its own sandbox. With [Options.personalLearning] it also keeps the phrases the
 * person typed here ([PersonalLearningState] behind [PersonalLearningCoordinator], as the Android service uses them),
 * which leave through [personal]. The Android keyboard has its own stores and doesn't use this.
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
    /** What the person switched on beyond the defaults; [Options.NONE] leaves everything as it was before them. */
    options: Options = Options.NONE,
) {
    /**
     * The opt-in parts of typing, as the Android keyboard's settings hold them. Every field's default changes nothing.
     */
    class Options(
        /** `spellings_en_gb.txt` when Spelling: British is on (English only), as [BritishSpelling]; null otherwise. */
        val british: String? = null,
        /** The text of each word pack switched on, in `WordPack`'s format; one that isn't this language's is left out. */
        val packs: List<String> = emptyList(),
        /** Blocked words: typed freely, never suggested, never a correction, never learned ([BlockedWords]). */
        val blocked: List<String> = emptyList(),
        /** `current_words.txt`, the reviewed current vocabulary (English only); null leaves it out. */
        val vocabulary: String? = null,
        /** The saved learned capitals ([spellings]), or null to start without any. */
        val spellings: String? = null,
        /** Today as days since 1970-01-01, which fades a pack's officeholders and the vocabulary's news words. */
        val epochDay: Long = 0,
        /**
         * Learn my phrases: the word put after the last one to three words completed here gets one strip slot the next
         * time, as Android's personal suffix learning. Off, nothing is kept and every answer is as it was.
         */
        val personalLearning: Boolean = false,
    ) {
        companion object {
            val NONE = Options()
        }
    }

    private val britishWords: BritishSpelling =
        options.british?.takeIf { language.english }?.let { BritishSpelling.parse(it.lineSequence()) } ?: BritishSpelling.EMPTY
    private val blockedWords: Set<String> = options.blocked.mapNotNullTo(HashSet()) { BlockedWords.normalize(it) }
    /** A bundled pack that fails its strict read is left out rather than failing the keyboard. */
    private val wordPacks: WordPacks = WordPacks.of(
        options.packs.mapNotNull { text -> runCatching { WordPack.strict(text) }.getOrNull() }.filter { it.language == language.code },
        options.epochDay,
    )
    private val vocabulary: CurrentVocabulary = options.vocabulary?.takeIf { language.english }
        ?.let { text -> runCatching { CurrentVocabulary.parse(text).at(options.epochDay) }.getOrNull() } ?: CurrentVocabulary.EMPTY
    /** The person's own capitals for words they typed ("iPhone", "LocalType"), newest last, as Android's `WordSpellingStore`. */
    private var learnedSpellings: Map<String, String> = options.spellings?.let { WordSpellings.decode(it) } ?: emptyMap()
    private var spellingsChanged = false

    private fun isBlocked(word: String): Boolean = blockedWords.isNotEmpty() && BlockedWords.contains(blockedWords, word)

    /**
     * The phrases typed here, and whose words the last few completions were; both null with Learn my phrases off.
     * Half the contexts the Android keyboard keeps ([PHRASE_CONTEXTS]), for the memory an iPhone keyboard is held to;
     * a snapshot is the same format either way, and a larger one loses its oldest contexts when read.
     */
    private val personal: PersonalLearningState? =
        if (options.personalLearning) PersonalLearningState(maxContexts = PHRASE_CONTEXTS, oneLetterWords = language.oneLetterWords) else null
    private val personalContext: PersonalLearningCoordinator? = personal?.let { state ->
        PersonalLearningCoordinator(
            { context, word, origin ->
                if (BlockedWords.mayLearn(blockedWords, context, word)) state.record(context, word, origin)?.also { phrasesChanged = true } else null
            },
            { receipt -> state.undo(receipt).also { if (it) phrasesChanged = true } },
        )
    }
    /** A phrase was added, taken back or dropped since the host last saved them ([personalSaved]) or read them. */
    private var phrasesChanged = false
    /** The phrase's word in the last [suggestions] answer, as shown there. */
    private var shownPhraseWord: String? = null
    /**
     * A copy of the coordinator's anchor: the end of the text through the last own completion. A strip lookup checks
     * it here, because asking the coordinator with text that doesn't match gives the context up, and the text a host
     * reads for the strip can lag behind what it just typed.
     */
    private var ownAnchor: String? = null
    /** What the last automatic fix added, until [takeBack] or the next completion. */
    private var lastRepair: PersonalLearningCoordinator.Contribution? = null

    /** Where the person's finger lands on each letter key, learned from real words they typed; scores slips only. */
    private val touchOffsets = TouchOffsets(language.geometry).apply { offsets?.let { load(TouchOffsets.decode(it)) } }
    private val emojiSuggestions: EmojiSuggestions? =
        emoji?.takeIf { language.english }?.let { EmojiSuggestions.parse(it.lineSequence()) }
    private val learned = MemoryLearningStore(journal?.let { LearnedPairJournal.decode(it).state.entries() }.orEmpty())
    private val swearWords: Set<String> = swears.orEmpty().lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
    /** What the ranking with Chime's words leaves out: nothing, or with Suggest swear words off the list and Chime's own compounds. */
    private val rankingSwears: Set<String> = if (suggestSwears) emptySet() else swearWords + NeuralRanking.SWEAR_COMPOUNDS
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
        if (language.english) AdaptiveNgramModel(learned, dictionary = dictionary, prior = corpusPrior, vocabulary = vocabulary,
            excluded = ::isBlocked, packs = wordPacks, unoffered = UnofferedWords.ENGLISH)
        else AdaptiveNgramModel(learned, seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
            prior = corpusPrior, priorWeights = AdaptiveNgramModel.PriorWeights(weight = language.priorWeight),
            openingMarks = language.openingMarks, oneLetterWords = language.oneLetterWords, excluded = ::isBlocked,
            wordRule = language.wordRule, packs = wordPacks)
    private val corrector = Corrector(
        dictionary = { dictionary },
        isKnown = { model.knows(it) || it in swearWords || it in vocabulary.words },
        lexicon = { known },
        contextCount = { previous, word -> model.pairCount(previous, word) },
        blocked = { isBlocked(it) || (!suggestSwears && it in swearWords) },
        // As on Android, a blocked word counts as neither learned (the model's knows) nor added, so its fix comes back.
        isKept = { model.isAddedWord(it) && !isBlocked(it) },
        touchOffsets = { touchOffsets },
        language = { language },
        packs = { wordPacks },
    ).also { if (!wordPacks.isEmpty) it.preparePacks(wordPacks) }

    /** Whether this language fixes typos by itself; Navajo only offers them ([LanguageProfile.autocorrect]). */
    val autocorrects: Boolean get() = language.autocorrect

    /** No Shift key and no capitals (Hebrew, Arabic, Persian). */
    val shiftless: Boolean get() = language.shiftless

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
     * and a slot for a word the person has typed after the previous one at least twice (one of the commonest
     * words only when Chime ranks it too). With [Options.personalLearning] that slot also goes to the word that
     * followed the same one to three words completed here, with or without [neural]. Without
     * [capitalizeSentences] (Capitalize automatically off) a sentence start doesn't capitalize them.
     */
    fun suggestions(beforeCursor: String, limit: Int, neural: List<String>?, capitalizeSentences: Boolean = true): List<String> {
        val prefix = model.activePrefix(beforeCursor)
        val sentenceStart = capitalizeSentences && model.isSentenceStart(beforeCursor)
        val ranked = model.predict(beforeCursor, limit + 4)
        val followers = ownFollowers(beforeCursor.dropLast(prefix.length))
        var phrase: String? = null
        // Without Chime's words the strip is the n-gram list as it always was, unless a phrase typed here has a word
        // for this place: then the same ranking runs with no neural words, as on Android.
        val words = if (neural == null && followers.isEmpty()) ranked.filter { offerable(it) } else {
            val learned = model.lastCompletedWord(beforeCursor.dropLast(prefix.length))?.let { model.learnedFollowers(it) }.orEmpty()
            val choices = NeuralRanking.rankWithSources(neural.orEmpty(), ranked, learned, prefix, rankingSwears, limit,
                added = model.addedWords(prefix), followers = followers, language = language,
                common = { model.dictionary.score(it) >= NeuralRanking.COMMON_SCORE })
            // The slot's word when a phrase typed here has it. The learned pairs may hold the same word with a higher
            // count, and the ranking then names them as its source, so the source alone doesn't say.
            phrase = choices.firstOrNull { choice ->
                choice.personalSource != null && choice.personalSource != PersonalRanking.Source.NEURAL &&
                    followers.any { AdaptiveNgramModel.normalize(it.word) == choice.word }
            }?.word
            choices.map { it.word }
        }
        // Blocked words are left out before and after spelling: British spelling can turn "color" into a blocked "colour".
        val shown = BritishSpelling.distinct(words.filterNot(::isBlocked).take(limit).map { display(it, prefix, sentenceStart) }.filterNot(::isBlocked))
        shownPhraseWord = phrase?.let { display(it, prefix, sentenceStart) }?.takeIf { it in shown }
        return shown
    }

    /**
     * The word in the last [suggestions] answer that is there because it followed the words typed here (Learn my
     * phrases), as that answer spelled it; null when the answer held none. A word only the learned pairs put there is
     * not one. The host asks right after [suggestions], to know whether a slot given to something else took the phrase's.
     */
    val phraseWord: String? get() = shownPhraseWord

    /**
     * [word] as the strip shows it, in Android's order: the person's own capitals first, then the British spelling
     * when that is on (never for an added word), then a pack's or the current vocabulary's spelling, then the case of
     * what is typed and the sentence.
     */
    private fun display(word: String, prefix: String, sentenceStart: Boolean): String {
        WordSpellings.display(learnedSpellings, word)?.let { return TextRules.applyCase(it, prefix, sentenceStart) }
        val normalized = AdaptiveNgramModel.normalize(word)
        val protected = model.isAddedWord(normalized) || model.isAddedWord(normalized.removeSuffix("'s").removeSuffix("'"))
        if (!protected) {
            val gb = britishWords.of(word)
            if (gb != word) return TextRules.applyCase(gb, prefix, sentenceStart)
            if (normalized !in vocabulary.words) wordPacks.preferred(normalized)?.let { return TextRules.applyCase(it, prefix, sentenceStart) }
        }
        // Without the current vocabulary (every language but English, or no file) the word is cased as it always was.
        return if (vocabulary === CurrentVocabulary.EMPTY) TextRules.applyCase(word, prefix, sentenceStart)
        else vocabulary.display(word, prefix, sentenceStart, protected = protected)
    }

    /** [word] spelled British when that is on, unless the person added it or gave it their own capitals. */
    private fun british(word: String): String =
        if (WordSpellings.display(learnedSpellings, word) != null || model.isAddedWord(AdaptiveNgramModel.normalize(word))) word
        else britishWords.of(word)

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
        val fix = if (language.autocorrect) corrector.correct(prefix, touches, previous, sentenceStart, afterComma) else null
        val answer = fix?.let { TextRules.applyCase(british(it), prefix, capitalizeSentences && sentenceStart) }?.takeUnless(::isBlocked)
            ?: if (capitalizeI && language.english && prefix == "i") "I" else null
        traced = touches?.let { TracedWord(prefix, it, corrected = answer != null && answer != prefix) }
        return answer
    }

    /**
     * A fix worth showing for the word that ends [beforeSeparator] when [correction] has none to apply: one the
     * corrector isn't sure enough of, a missing possessive apostrophe, a pack word, or any fix in a language that
     * never fixes by itself. As Android's strip: with [completionsAvailable] only a contraction or a possessive is
     * shown, so a longer word being typed isn't hidden behind a guess. Null when there is nothing, or it is blocked.
     */
    fun offer(beforeSeparator: String, completionsAvailable: Boolean): String? {
        val prefix = model.activePrefix(beforeSeparator)
        if (prefix.isEmpty()) return null
        val sentenceStart = model.isSentenceStart(beforeSeparator)
        val previous = model.lastCompletedWord(beforeSeparator)
        val afterComma = beforeSeparator.dropLast(prefix.length).trimEnd().endsWith(',')
        val fix = corrector.suggestForStrip(prefix, touches(prefix), previous, sentenceStart, afterComma, completionsAvailable = completionsAvailable)
        return fix?.let { TextRules.applyCase(british(it), prefix, sentenceStart = false) }?.takeUnless { it == prefix || isBlocked(it) }
    }

    /**
     * Learns the word that ends [beforeSeparator] after the word before it; true if it was learned. A word typed
     * whole by tapping and left as typed also teaches the tap offsets, as on Android. A learned word also teaches
     * its capitals, or forgets them when typed in lowercase mid-sentence; [capsLock] explains capitals away. With
     * [corrected], the word is the keyboard's fix, not what the person typed: its case says nothing about how they
     * spell it, so the capitals they saved for it stay as they are.
     */
    fun learn(beforeSeparator: String, capsLock: Boolean = false, corrected: Boolean = false): Boolean {
        val raw = model.activePrefix(beforeSeparator)
        // After a saved text's word the person didn't type here, the word teaches nothing; it is still their own word.
        val blocked = savedTextBlocks(beforeSeparator.dropLast(raw.length))
        val real = if (blocked) model.learnable(beforeSeparator) else model.learnTypedWord(beforeSeparator, persist = true)
        if (real) completedOwn(beforeSeparator.dropLast(raw.length), raw)
        if (blocked) {
            traced = null
            if (!corrected) completedHere(beforeSeparator, real)
            return false
        }
        val learned = real
        if (learned) {
            corrector.forgetRecentAnswers()
            traced?.takeIf { !corrected && !it.corrected && it.word == raw }?.let { touchOffsets.record(it.word, it.touches) }
            if (!corrected && !language.shiftless) learnSpelling(raw, model.isSentenceStart(beforeSeparator), capsLock)
        }
        traced = null
        // A fix's phrase was recorded by [repaired] when it was made.
        if (!corrected) completedHere(beforeSeparator, learned)
        return learned
    }

    // Saved text. With no saved texts given ([setSavedTexts]) nothing here changes any answer.

    /** Every word in the host's saved texts, as the word model spells words. */
    private var savedWords: Set<String> = emptySet()
    /** The last word completed here by the person's own typing or tap, and the end of the text through it. */
    private var typedLast: String? = null
    private var typedTail: String? = null

    /**
     * The saved texts now in the host's list. A saved text can be an address or an account name, so the word typed
     * after one of its words is not learned unless the person typed that word here too. This copies `SavedWords` in
     * the Android app (Expansions.kt), which the engine can't call: change the two together.
     */
    fun setSavedTexts(texts: List<String>) {
        savedWords = texts.flatMapTo(HashSet()) { text ->
            AdaptiveNgramModel.WORD_REGEX.findAll(text).map { AdaptiveNgramModel.normalize(it.value) }
        }
    }

    /**
     * Whether a word completed after [beforeWord] must teach nothing: the word before it stands in a saved text, or
     * is one with letters typed onto it, and is not the word the person last completed right there. False with no
     * saved texts, and at a sentence start.
     */
    fun savedTextBlocks(beforeWord: String): Boolean {
        if (savedWords.isEmpty()) return false
        val previous = model.lastCompletedWord(beforeWord)?.let { AdaptiveNgramModel.normalize(it) } ?: return false
        val tail = typedTail
        val own = typedLast?.takeIf { tail != null && beforeWord.trimEnd().endsWith(tail) }
        if (own != null && AdaptiveNgramModel.normalize(own) == previous) return false
        return previous in savedWords ||
            savedWords.any { it.length >= SAVED_GROWN_CHARS && it.length < previous.length && (previous.startsWith(it) || previous.endsWith(it)) }
    }

    /** [word] was completed after [beforeWord] by the person: typed and ended, fixed, or tapped in the strip. */
    private fun completedOwn(beforeWord: String, word: String) {
        typedLast = word
        typedTail = anchorOf(beforeWord, word)
    }

    // Learn my phrases. Every function here does nothing with the option off.

    private fun anchorOf(beforeWord: String, word: String): String = (beforeWord + word).trimEnd().takeLast(ANCHOR_CHARS)

    /** A new line between the last word and this place: the coordinator's anchor ignores white space, so it is checked here. */
    private fun newLine(beforeWord: String): Boolean = beforeWord.takeLastWhile { it.isWhitespace() }.any { it == '\n' || it == '\r' }

    /** The word that ends [beforeSeparator] was typed here and left as typed; [learnable] is whether it is a real word. */
    private fun completedHere(beforeSeparator: String, learnable: Boolean) {
        val coordinator = personalContext ?: return
        val word = model.activePrefix(beforeSeparator)
        if (word.isEmpty()) return
        // A word that isn't known breaks the chain, as on Android: nothing follows a typo.
        if (!learnable) return resetContext()
        val beforeWord = beforeSeparator.dropLast(word.length)
        if (newLine(beforeWord)) resetPhrase()
        lastRepair = null
        val done = coordinator.complete(beforeWord, word, word, LearningOrigin.TYPED, allowed = true, verified = true)
        ownAnchor = done?.let { anchorOf(beforeWord, word) }
    }

    /**
     * The host just replaced a word typed here with the keyboard's fix, and [beforeSeparator] now ends with the fix.
     * The fix joins the phrase at once, so the strip after it has its context; [takeBack] undoes exactly this.
     */
    fun repaired(beforeSeparator: String) {
        val coordinator = personalContext ?: return
        val fix = model.activePrefix(beforeSeparator)
        if (fix.isEmpty() || !model.learnable(beforeSeparator)) return resetContext()
        val beforeWord = beforeSeparator.dropLast(fix.length)
        if (newLine(beforeWord)) resetPhrase()
        lastRepair = coordinator.complete(beforeWord, fix, fix, LearningOrigin.VERIFIED_REPAIR, allowed = true, verified = true)
        ownAnchor = lastRepair?.let { anchorOf(beforeWord, fix) }
    }

    /** Delete put the typed word back in place of the last fix: what [repaired] added is taken out, and the phrase ends. */
    fun takeBack() {
        personalContext?.undo(lastRepair)
        resetContext()
    }

    /**
     * Something other than a word typed or tapped here changed the text or moved the caret (a new line, a paste, a
     * panel insert, a slide, another field): the words completed so far are no longer the phrase before the caret,
     * and the word before it is no longer known to be the person's own (Saved text).
     */
    fun resetContext() {
        typedLast = null
        typedTail = null
        resetPhrase()
    }

    /**
     * The phrase ends, and nothing else: a word completed at the start of a new line begins a new phrase, and is
     * still the person's own word there (Saved text), so the word after it is learned.
     */
    private fun resetPhrase() {
        personalContext?.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        ownAnchor = null
        lastRepair = null
    }

    /** The words that followed the own words ending [beforeWord], best first; empty when those words aren't known to be typed here. */
    private fun ownFollowers(beforeWord: String): List<PersonalFollower> {
        val state = personal ?: return emptyList()
        val anchor = ownAnchor ?: return emptyList()
        if (!beforeWord.trimEnd().endsWith(anchor) || newLine(beforeWord)) return emptyList()
        // The anchor matches, so this read cannot give the context up.
        val context = personalContext?.context(beforeWord).orEmpty()
        return state.followers(context, NeuralRanking.PERSONAL_POLICY.maxContext)
            .filter { !isBlocked(it.word) && (suggestSwears || it.word !in swearWords) }
    }

    /** Whether Learn my phrases is on for this engine. */
    val personalEnabled: Boolean get() = personal != null

    /** How many phrase rows are held (a context of one to three words and the word after it). Never a word. */
    val personalEntries: Int get() = personal?.entries()?.size ?: 0

    /**
     * The phrases, as Android's personal learning snapshot, for the host to save; an empty snapshot with the option off.
     * The bytes are [PersonalLearningCodec.encode]'s, written here without its check: the check rebuilds the whole store
     * a second time to compare, and these rows come straight from the store it would rebuild. On the iPhone that second
     * copy was most of a save's time and memory. `TypingEnginePhrasesTest` holds the two equal; change them together.
     */
    fun personal(): ByteArray {
        val entries = personal?.entries().orEmpty()
        val out = ByteWriter(64 + entries.size * 48)
        fun word(text: String) {
            val bytes = text.encodeToByteArray()
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        out.writeInt(SNAPSHOT_MAGIC)
        out.writeInt(SNAPSHOT_VERSION)
        out.writeInt(entries.size)
        for (entry in entries) {
            out.writeInt(entry.context.size)
            entry.context.forEach(::word)
            word(entry.word)
            out.writeInt(entry.observations)
            out.writeInt(entry.explicitObservations)
            out.writeLong(entry.lastObserved)
        }
        out.writeInt(crc32(out.toByteArray()).toInt())
        return out.toByteArray()
    }

    /**
     * Whether the phrases differ from what the host last read ([restorePersonal]) or saved ([personalSaved]). Writing
     * them checks the whole snapshot, so a host saves them only then. Always false with the option off.
     */
    val personalChanged: Boolean get() = phrasesChanged

    /** The host saved what [personal] gave. */
    fun personalSaved() {
        phrasesChanged = false
    }

    /**
     * Reads a snapshot [personal] gave before. A damaged one is refused whole and nothing is held, as on Android; a
     * row with a word blocked since is left out. False with the option off or when the snapshot was refused.
     */
    fun restorePersonal(bytes: ByteArray): Boolean {
        val state = personal ?: return false
        val entries = runCatching { PersonalLearningCodec.decode(bytes, language.oneLetterWords) }.getOrNull() ?: return false
        val kept = entries.filter { BlockedWords.mayLearn(blockedWords, it.context, it.word) }
        state.restore(kept)
        // What is held is the file, unless rows with a word blocked since were left out: those leave the file at the next save.
        phrasesChanged = kept.size != entries.size
        resetContext()
        return true
    }

    /** As Android's `learnJustCompletedWord`: a common word never records capitals; an added word records what was typed. */
    private fun learnSpelling(raw: String, sentenceStart: Boolean, capsLock: Boolean) {
        val word = AdaptiveNgramModel.normalize(raw)
        val change = if (model.isAddedWord(word)) {
            if (capsLock || raw == raw.lowercase()) WordSpellings.Change.Unchanged else WordSpellings.Change.Record(raw)
        } else WordSpellings.change(raw, sentenceStart, capsLock, common = dictionary.score(word) >= COMMON_SPELLING_SCORE)
        WordSpellings.apply(learnedSpellings, word, change, keep = { model.isAddedWord(it) })?.let {
            learnedSpellings = it
            spellingsChanged = true
        }
    }

    /** The learned capitals, as text for the host to save ([WordSpellings.encode]). */
    fun spellings(): String = WordSpellings.encode(learnedSpellings)

    /** How many words have capitals of their own. */
    val learnedSpellingCount: Int get() = learnedSpellings.size

    /** Whether [word] is one the person blocked: the host leaves it out of anything it offers itself. */
    fun blocks(word: String): Boolean = isBlocked(word)

    /**
     * A character a key typed. [dx] and [dy] are where the finger lifted from the center of the key's cell, in key
     * widths and rows; NaN when it wasn't a tap on that key (a hold's choice, VoiceOver). A character that can't be
     * part of a word starts the trace over.
     */
    fun typed(text: String, dx: Double, dy: Double) {
        if (text.isEmpty()) return resetTrace()
        // A hold can type two characters (Navajo's "ą" with its tone mark, Arabic's lam-alef): each joins the trace,
        // and none of them carries a touch, as on Android.
        val single = text.length == 1
        for (char in text) {
            if (!(char.isLetterOrDigit() || language.wordRule.continues(char))) return resetTrace()
            traceText.append(char)
            traceTouches.add(if (single && dx.isFinite() && dy.isFinite()) language.geometry.touchOn(char.lowercaseChar(), dx.toFloat(), dy.toFloat()) else null)
        }
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
            blocked = { isBlocked(it) || (!suggestSwears && it in swearWords) },
            repair = { part -> listOfNotNull(corrector.suggest(part)) },
        ) ?: return null
        if (wordPacks.contains(word) || word in vocabulary.words) return null
        return if (raw.first().isUpperCase()) offer.text.replaceFirstChar { it.uppercaseChar() } else offer.text
    }

    /** The tap offsets, as text for the host to save ([TouchOffsets.encode]). */
    fun offsets(): String = touchOffsets.encode()

    /** How many letter taps the offsets have learned from. */
    val tapSamples: Int get() = touchOffsets.entries().sumOf { it.count }

    /**
     * One letter key's learned mean lift, for a host that aims touches by it: [dx] to the right of the key's center
     * in tenths of the keys' width, [dy] below it in rows, as [typed] takes them.
     */
    class TapOffset(val letter: String, val count: Int, val dx: Float, val dy: Float)

    /** The keys that have samples, in character order. The host decides how many lifts are enough and how far to move. */
    fun tapOffsets(): List<TapOffset> = touchOffsets.entries().map { TapOffset(it.letter.toString(), it.count, it.dx, it.dy) }

    /** Changes whenever the offsets learn or forget, so a host reads [tapOffsets] again only then. */
    val tapRevision: Long get() = touchOffsets.revision

    /** Whether the word list has a word that starts with [prefix], which keeps a remembered site from taking a word's place. */
    fun completes(prefix: String): Boolean = prefix.isNotEmpty() && dictionary.completions(prefix.lowercase(), 1).isNotEmpty()

    /**
     * Remember sites I type, for a host other than the Android service: the site names (hosts) of web and mail
     * addresses the person typed in full, and the run of text that shows one was typed here. Hosts only
     * ([DomainHosts]): never a scheme, a user part, a port, a path or a query.
     *
     * The Android keyboard keeps the same rules in its app module (`RememberedDomains`, `DomainCodec`, `TypedRun`,
     * `Expansions.domain` and the service's `rememberTypedHost`), which this file may not reach. They are copied
     * here, and `TypingEngineSitesTest` runs both through the same cases; change them together.
     *
     * [tlds] is the bundled list of top-level domains, [saved] the text [text] gave before, or null. It belongs to
     * no language, so one serves every [TypingEngine]. Not thread-safe.
     */
    class Sites(tlds: String, saved: String?) {
        private class Entry(val host: String, val count: Int, val day: Long)

        /** A site to finish: [host] is what the strip shows, [text] what a tap appends to what is typed. */
        class Offer(val host: String, val text: String)

        private val endings: Set<String> = DomainHosts.parseTlds(tlds.lineSequence())
        private val entries = LinkedHashMap<String, Entry>()
        /** The hosts typed often enough to offer, best first; sorted once per change. */
        private var offeredNow: List<Entry>? = null
        /** The text before the caret that every key of was typed here since the last white space; null when unknown. */
        private var run: String? = null

        /** Something changed since [saved]: the host writes [text] to its file. */
        var changed: Boolean = false
            private set

        init {
            // A damaged or hand-edited file loses its bad lines, its duplicates and its overflow here.
            for (entry in decode(saved.orEmpty())) {
                if (entry.count >= 1 && entry.host !in entries && entries.size < MAX) {
                    entries[entry.host] = Entry(entry.host, entry.count.coerceAtMost(MAX_COUNT), entry.day)
                }
            }
        }

        /** How many sites are held, and how many of them are typed often enough to offer. Never a name. */
        val count: Int get() = entries.size
        val offeredCount: Int get() = offered().size

        private fun offered(): List<Entry> = offeredNow ?: entries.values.filter { it.count >= OFFER_AFTER }.sortedWith(ORDER).also { offeredNow = it }

        // The run: as Android's TypedRun.

        /** A plain append of [text] to a field whose text before the caret was [before]; null when that could not be read. */
        fun appended(before: String?, text: String) {
            if (before == null) return reset()
            val base = when {
                before.isEmpty() || before.last().isWhitespace() -> ""
                else -> run?.takeIf { it.isNotEmpty() && endsWith(before, it) }
            }
            val afterSpace = text.takeLastWhile { !it.isWhitespace() }
            run = when {
                // White space in what was typed ends any run; what follows it starts a new one.
                afterSpace.length < text.length -> afterSpace
                base == null -> null
                else -> (base + text).takeIf { it.length <= MAX_CHARS }
            }
        }

        /** Delete took the last [chars] of [before], the text that stood before the caret. */
        fun deleted(before: String, chars: Int) {
            val owned = run ?: return
            // A press somewhere else in the field, or one that took more than the run, leaves nothing to stand on.
            run = if (chars in 1..owned.length && endsWith(before, owned)) owned.dropLast(chars) else null
        }

        /** Anything else that changed the text or moved the caret. */
        fun reset() {
            run = null
        }

        /** The caret may have moved: the run is given up unless [beforeCursor] still ends with exactly it. */
        fun keep(beforeCursor: String) {
            if (current(beforeCursor) == null) run = null
        }

        private fun current(before: String): String? = run?.takeIf { it.isNotEmpty() && endsWith(before, it) }

        private fun endsWith(before: String, owned: String): Boolean =
            before.endsWith(owned) && (before.length == owned.length || before[before.length - owned.length - 1].isWhitespace())

        /**
         * Space or Return is about to end the run before the caret. When every character of it was typed here and it
         * is a web or mail address that ends at the caret, its host is counted once on [epochDay] (days since 1970).
         * True when it was, so the host saves. As the Android service's `rememberTypedHost`.
         */
        fun finished(beforeCursor: String, afterCursor: String?, epochDay: Long): Boolean {
            // An ordinary word costs nothing: only a run with a dot in it can be an address.
            if (run?.contains('.') != true) return false
            val typed = current(beforeCursor) ?: return false
            val host = DomainHosts.host(typed, endings) ?: return false
            // The run must end where the address does: text standing right after the caret is more of the same token.
            if (!afterCursor.isNullOrEmpty() && !afterCursor[0].isWhitespace()) return false
            val known = entries[host]
            if (known == null && entries.size >= MAX) entries.remove(entries.values.minWithOrNull(EVICTION)!!.host)
            entries[host] = Entry(host, ((known?.count ?: 0) + 1).coerceAtMost(MAX_COUNT), epochDay)
            // Counted once: Return pressed again on a search that keeps its text is not a second typing of it.
            run = null
            changed()
            return true
        }

        /**
         * The remembered site that the text before the caret is the start of, or null: as Android's
         * `Expansions.domain` without the common mail providers, which the host offers itself. [address] is a web
         * address field, [mail] a mail field. After an `@` one character is enough, and in a mail field nothing is
         * offered before it; in an address field or after a scheme, two; in ordinary text three, and then the run
         * must hold a dot or be a word nothing in the word list completes ([wordCompletes], asked only then).
         * Nothing is offered when [afterCursor] continues the address ("example|.com").
         */
        fun offer(beforeCursor: String, afterCursor: String?, address: Boolean, mail: Boolean, wordCompletes: () -> Boolean): Offer? {
            if (!afterCursor.isNullOrEmpty() && !afterCursor[0].isWhitespace() && afterCursor[0] !in CLOSERS) return null
            val offered = offered()
            if (offered.isEmpty()) return null
            val typedRun = beforeCursor.takeLastWhile { !it.isWhitespace() }
            val typed = DomainHosts.typing(typedRun) ?: return null
            val needed = when {
                // "@lu" opens a handle, which DomainHosts reads no host from; "name@" opens a mail address.
                '@' in typedRun -> MAIL_CHARS
                mail -> return null
                address || "://" in typedRun -> ADDRESS_CHARS
                else -> TEXT_CHARS
            }
            if (typed.length < needed) return null
            val match = offered.firstOrNull { it.host.length > typed.length && it.host.startsWith(typed) } ?: return null
            if (needed == TEXT_CHARS && '.' !in typed && wordCompletes()) return null
            return Offer(match.host, match.host.substring(typed.length))
        }

        /**
         * The person took [host] from the strip on [epochDay]: it was wanted, which keeps it from being the one that
         * makes room. Its count stays: what went in was not typed, so the run ends too.
         */
        fun taken(host: String, epochDay: Long) {
            run = null
            val known = entries[host] ?: return
            if (known.day == epochDay) return
            entries[host] = Entry(host, known.count, epochDay)
            changed()
        }

        /** Forget learned words. */
        fun forget() {
            run = null
            if (entries.isEmpty()) return
            entries.clear()
            changed()
        }

        private fun changed() {
            offeredNow = null
            changed = true
        }

        /** The list as text for the host to save, in the Android file's format: a version line, then host, count and day. */
        fun text(): String = buildString {
            append(HEADER).append('\n')
            for (entry in entries.values) append(entry.host).append('\t').append(entry.count).append('\t').append(entry.day).append('\n')
        }

        /** The host saved [text]. */
        fun saved() {
            changed = false
        }

        /** As Android's `DomainCodec.decode`: a line that does not parse is dropped; a file that is not this format reads as empty. */
        private fun decode(file: String): List<Entry> {
            val lines = file.split('\n').map { it.removeSuffix("\r") }
            if (lines.first() != HEADER) return emptyList()
            return lines.drop(1).mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 3) return@mapNotNull null
                val count = fields[1].toIntOrNull() ?: return@mapNotNull null
                val day = fields[2].toLongOrNull() ?: return@mapNotNull null
                // Only something DomainHosts could have produced: a hand-edited line cannot put other text in the strip.
                if (!HOST.matches(fields[0])) return@mapNotNull null
                Entry(fields[0], count, day)
            }
        }

        private companion object {
            const val HEADER = "localtype-domains 1"
            const val MAX = 64
            const val OFFER_AFTER = 2
            const val MAX_COUNT = 9_999
            /** A host name is at most 253 characters; a longer run is not one. */
            const val MAX_CHARS = 260
            const val MAIL_CHARS = 1
            const val ADDRESS_CHARS = 2
            const val TEXT_CHARS = 3
            /** What may stand right after the caret without continuing the address. */
            const val CLOSERS = ")]}>\"'”’,;!?"
            val HOST = Regex("[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63})+")
            /** Most typed first, then most recent, then by name so the order never depends on the file's. */
            val ORDER = compareByDescending<Entry> { it.count }.thenByDescending { it.day }.thenBy { it.host }
            /** The one to drop: used longest ago, then typed least, then last by name. */
            val EVICTION = compareBy<Entry> { it.day }.thenBy { it.count }.thenByDescending { it.host }
        }
    }

    // Tidy spacing and small slips: Android's small accommodations, spacing conveniences and contextual "its", for a
    // host that can only read the text before the caret and rewrite its end. The rules are [SmallAccommodations],
    // [OwnedSpacing] and [ContextualContractions], called as they are, in the order the Android service calls them
    // (`typeText`, `recordAppend`, `finishDotSpace`, `finishSmallAccommodation`, `finishContextualContraction`).
    // Nothing here runs unless the host calls it. The host reports every key it typed with the text it read around
    // it; the text before the caret is taken as the whole text with nothing after it, which the host checks first.

    /**
     * A small fix for text the keys just typed. The text before the caret ends with [original] + [delimiter], and the
     * fix puts [replacement] + [delimiter] there. [automatic] ones are applied at once; the others are only offered.
     * [kind] is the rule's name, for the host's log and tests.
     */
    class Convenience internal constructor(
        val original: String,
        val replacement: String,
        val delimiter: String,
        val automatic: Boolean,
        val kind: String,
        internal val offer: SmallAccommodations.Offer?,
    )

    /** Changes at every reset, so nothing remembered before one is used after it. */
    private var convGeneration = 0L
    private var convAnchor: SmallAccommodations.Anchor? = null
    private var convIts: ContextualContractions.Anchor? = null
    /** The last word completed here by a verified Space, in lowercase: the rules' proof that the word before is own. */
    private var convOwnPrevious: String? = null
    /** Made at the first use, so an engine whose host never asks holds none. */
    private var convSpacing: OwnedSpacing? = null

    private fun spacing(): OwnedSpacing = convSpacing ?: OwnedSpacing().also { convSpacing = it }
    private fun convIdentity() = RecentWordCorrection.Identity(convGeneration, 0, SmallAccommodations.VERSION)
    private fun convSnapshot(before: String) =
        VerifiedWordTransaction.Snapshot(before, "", EditorEditState.Selection(before.length, before.length))

    /** As the service's `accommodationProtected`; [host] is what only the host knows (a saved-text shortcut, a kept word). */
    private fun convProtected(word: String, host: Boolean): Boolean {
        val key = AdaptiveNgramModel.normalize(word)
        return host || model.isAddedWord(key) || key in vocabulary.words || wordPacks.contains(key) || (!suggestSwears && key in swearWords)
    }

    /** As the service's `ordinarySpacingWord`. */
    private fun convOrdinary(word: String, host: Boolean): Boolean =
        AdaptiveNgramModel.normalize(word) in dictionary && !convProtected(word, host) && !DomainTyping.protected(word, "")

    private fun convForget() {
        convAnchor = null
        convIts = null
        convOwnPrevious = null
    }

    /**
     * A blocked word is never put in. A fix applied at once ends what was remembered. An offer, as the service's
     * `offerSmallAccommodation`, ends only the "its" remembered: the word just finished stays, so a comma, ! or ?
     * typed right after an offer left alone still loses the space before it ("the the ," to "the the,").
     */
    private fun convenience(offer: SmallAccommodations.Offer?): Convenience? {
        offer ?: return null
        if (offer.replacement.split(' ').any { part -> isBlocked(part.trim { !it.isLetter() }) }) return null
        if (offer.automatic) convForget() else convIts = null
        return Convenience(offer.original, offer.replacement, offer.delimiter, offer.automatic, offer.kind.name, offer)
    }

    /**
     * Anything but a plain key typed here changed the text or moved the caret, or these fixes are not allowed now:
     * nothing remembered so far is used again.
     */
    fun conveniencesReset() {
        convGeneration++
        convForget()
        convSpacing?.reset()
    }

    /**
     * A key typed [text], which is not a space, a closing parenthesis or one of , ! ? : ; and a word's period, and
     * [beforeKey] stood before it. [allowed] is false when caps lock typed a letter of this word.
     */
    fun conveniencesAppended(beforeKey: String, text: String, now: Long, allowed: Boolean) {
        if (!allowed) return conveniencesReset()
        if (text == "(") spacing().opened(convIdentity(), convSnapshot(beforeKey + text), now)
        else convSpacing?.appended(convIdentity(), beforeKey, text, now, true)
    }

    /**
     * A closing parenthesis is about to be typed after [beforeKey]. The host types it, checks that it went in, and
     * then applies the answer: "(see below )" loses the space before it.
     */
    fun conveniencesClosing(beforeKey: String, now: Long, allowed: Boolean): Convenience? {
        val offer = if (allowed) convSpacing?.closing(convIdentity(), convSnapshot(beforeKey), now) { convOrdinary(it, false) } else null
        convSpacing?.reset()
        convForget()
        return convenience(offer)
    }

    /**
     * One of , ! ? : ; or a word's period was typed, and [before] ends with it. [word] is the word it ended (empty
     * after a space) and [verified] whether every letter of that word was typed here, the word was left as typed and
     * caps lock typed none of it. The answer, if any, is a space to take out before the mark ("word ," to "word,").
     */
    fun conveniencesMark(before: String, word: String, mark: String, verified: Boolean, hostProtected: Boolean, now: Long, allowed: Boolean): Convenience? {
        if (!allowed || mark.length != 1 || !before.endsWith(mark)) {
            conveniencesReset()
            return null
        }
        val spacing = spacing()
        val id = convIdentity()
        val s = convSnapshot(before)
        // A mark ends every run of letters; what follows starts the run that fits it.
        spacing.appended(id, before.dropLast(1), mark, now, true)
        val anchor = convAnchor
        convForget()
        when (mark) {
            "." -> spacing.dotTyped(id, s, word, now, verified && language.words && convOrdinary(word, hostProtected))
            ",", "!", "?" -> {
                SmallAccommodations.comma(anchor?.takeUnless { convProtected(it.original, false) }, id, s, now, mark)?.let { return convenience(it) }
                val ordinary = verified && convOrdinary(word, hostProtected)
                if (mark == ",") spacing.commaTyped(id, s, word, now, ordinary)
                else spacing.punctuationTyped(id, s, word, mark, now, ordinary, null)
            }
            ":", ";" -> spacing.punctuationTyped(id, s, word, mark, now, anchor != null && convOrdinary(anchor.original, false), anchor)
            else -> conveniencesReset()
        }
        return null
    }

    /**
     * Space was typed after [word], and [before] ends with that space. [verified] as in [conveniencesMark]. The answer
     * is a spacing fix to apply, or a word fix to offer. "its been" is always offered here, never applied: the rule
     * applies it only at a proven sentence start, and this host cannot prove that it read the whole text.
     */
    fun conveniencesSpace(before: String, word: String, verified: Boolean, hostProtected: Boolean, now: Long, allowed: Boolean): Convenience? {
        if (!allowed || !before.endsWith(' ')) {
            conveniencesReset()
            return null
        }
        val spacing = spacing()
        val id = convIdentity()
        val s = convSnapshot(before)
        spacing.appended(id, before.dropLast(1), " ", now, true)
        if (verified) {
            fun common(part: String) = convOrdinary(part, false) && dictionary.score(AdaptiveNgramModel.normalize(part)) >= DOT_SPACE_MIN_SCORE
            spacing.dotSpace(id, s, word, now) { first, second -> common(first) && common(second) }?.let { return convenience(it) }
        }
        val previous = convAnchor
        val its = convIts
        convAnchor = null
        convIts = null
        if (!verified) {
            convOwnPrevious = null
            return null
        }
        val ordinary = convOrdinary(word, hostProtected)
        spacing.completed(id, s, word, now, ordinary)?.let { return convenience(it) }
        spacing.missingCommaSpace(id, s, word, now, ordinary)?.let { return convenience(it) }
        // The word fixes (a/an, "your not", a repeated word, "its") are English; the spacing above is any language's.
        if (!language.english) return null
        val own = convOwnPrevious
        convOwnPrevious = word.lowercase()
        val current = SmallAccommodations.remember(id, s, word, now, true, protected = convProtected(word, hostProtected))
        convAnchor = current
        SmallAccommodations.word(previous, current, id, s, own, now, known = AdaptiveNgramModel.normalize(word) in dictionary)
            ?.let { return convenience(it) }
        if (!language.autocorrect || hostProtected || model.isAddedWord("its")) return null
        convIts = ContextualContractions.remember(id, s, word, now, verifiedOwn = true)
        val contraction = ContextualContractions.propose(its, id, s, word, true, own, now) ?: return null
        // The word just finished stays remembered through the offer, as on Android: "its been ," loses its space.
        if (isBlocked(contraction.replacement)) return null
        return Convenience(contraction.anchor.original, contraction.replacement, contraction.delimiter, automatic = false, kind = "ITS", offer = null)
    }

    /**
     * The host put [fix] in and read back [before], exactly the text it expected. Everything remembered ends, except
     * that "( word " made "(word " still lets the closing parenthesis be tidied later.
     */
    fun conveniencesApplied(fix: Convenience, before: String, now: Long) {
        conveniencesReset()
        fix.offer?.let { spacing().openingApplied(convIdentity(), it, convSnapshot(before), now) }
    }

    /**
     * As [accept], and with Learn my phrases the tapped word joins the phrase as a deliberate choice, which is offered
     * after one use. [owned] is whether the keys typed here whatever of the word stood before the tap (or nothing did);
     * otherwise nothing is kept for it and the phrase ends.
     */
    fun accept(beforeWord: String, word: String, owned: Boolean) {
        accept(beforeWord, word)
        // A word the keys did not type here is not the person's own for Saved text either.
        if (!owned) { typedLast = null; typedTail = null }
        val coordinator = personalContext ?: return
        if (isBlocked(word)) return resetContext()
        if (newLine(beforeWord)) resetPhrase()
        lastRepair = null
        val done = coordinator.complete(beforeWord, word, word, LearningOrigin.SUGGESTION_ACCEPT, allowed = true, verified = owned)
        ownAnchor = done?.let { anchorOf(beforeWord, word) }
    }

    /** Learns [word], tapped in the strip, after what stands before it in [beforeWord]. */
    fun accept(beforeWord: String, word: String) {
        if (isBlocked(word)) return
        val blocked = savedTextBlocks(beforeWord)
        completedOwn(beforeWord, word)
        if (blocked) return
        val previous = model.lastCompletedWord(beforeWord) ?: AdaptiveNgramModel.START.takeIf { model.isSentenceStart(beforeWord) }
        model.accept(previous, AdaptiveNgramModel.normalize(word))
        corrector.forgetRecentAnswers()
    }

    /** Everything learned, as the learned-pair journal's bytes, for the host to save. */
    fun journal(): ByteArray = LearnedPairJournal.compact(learned.entries())

    /** How many word pairs have been learned. */
    val learnedPairs: Int get() = learned.entries().size

    /** One learned pair for a list a person reads: [next] was typed after [previous] [uses] times. */
    class LearnedPair(val previous: String, val next: String, val uses: Int, val sentenceStart: Boolean)

    private fun shownPairs(): List<Triple<String, String, Int>> = learned.entries().filter { it.first != AddedWords.CONTEXT }

    /** The pairs [learnedList] draws from: [learnedPairs] without the added words, which have their own list. */
    val learnedPairCount: Int get() = shownPairs().size

    /** The learned pairs, most used first, at most [limit], as Android's Learned words list orders them. */
    fun learnedList(limit: Int): List<LearnedPair> = shownPairs().sortedByDescending { it.third }.take(limit.coerceAtLeast(0))
        .map { LearnedPair(it.first, it.second, it.third, it.first == AdaptiveNgramModel.START) }

    /**
     * Forgets one learned pair, as the remove button beside it on Android: the pair leaves the journal and, with
     * Learn my phrases on, every phrase that ends in it. Capitals and tap offsets stay. False when the pair isn't
     * held or is an added word, which only [syncAddedWords] removes. The host saves afterwards.
     */
    fun forgetPair(previous: String, next: String): Boolean {
        if (previous == AddedWords.CONTEXT || shownPairs().none { it.first == previous && it.second == next }) return false
        learned.remove(previous, next)
        model = buildModel()
        corrector.forgetRecentAnswers()
        resetTrace()
        conveniencesReset()
        personal?.let { state ->
            val kept = state.entries().filterNot { it.context.last() == previous && it.word == next }
            if (kept.size != state.entries().size) {
                state.restore(kept)
                phrasesChanged = true
            }
            resetContext()
        }
        return true
    }

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
        // A word added before capitals were kept ("LocalType" in the app, "localtype" in the journal) gets the
        // capitals typed into Settings once, while it has no spelling of its own; later typing may change it.
        val unspelled = words.map { AddedWords.normalize(it, language).orEmpty() to WordSpellings.added(it) }
            .filter { (word, change) -> word in wanted && word !in added && change is WordSpellings.Change.Record && word !in learnedSpellings }
        val spelled = WordSpellings.applyAll(learnedSpellings, unspelled, keep = { it in wanted })?.also {
            learnedSpellings = it
            spellingsChanged = true
        } != null
        if (removed.isEmpty() && added.isEmpty()) return spelled
        learned.entries().filter { it.second in removed }.forEach { learned.remove(it.first, it.second) }
        added.forEach { AddedWords.add(learned, it, language) }
        // An added word keeps the capitals typed into Settings; a removed one loses its spelling with its pairs.
        val changes = words.map { AddedWords.normalize(it, language).orEmpty() to WordSpellings.added(it) }.filter { it.first in added } +
            removed.map { it to WordSpellings.Change.Forget }
        WordSpellings.applyAll(learnedSpellings, changes, keep = { it in wanted })?.let {
            learnedSpellings = it
            spellingsChanged = true
        }
        model = buildModel()
        corrector.forgetRecentAnswers()
        conveniencesReset()
        // A removed word goes with every phrase that holds it, as Android's removeWord.
        if (removed.isNotEmpty()) personal?.let { state ->
            state.restore(state.entries().filterNot { it.word in removed || it.context.any { word -> word in removed } })
            phrasesChanged = true
            resetContext()
        }
        return true
    }

    /** Forgets everything learned; the host deletes its saved journal too. */
    fun forget() {
        model.clearLearned(if (language.english) AdaptiveNgramModel.DEFAULT_SEED_PHRASES else emptyList())
        touchOffsets.clear()
        learnedSpellings = emptyMap()
        resetTrace()
        corrector.forgetRecentAnswers()
        personal?.let {
            it.clear()
            phrasesChanged = true
        }
        resetContext()
        conveniencesReset()
    }

    // Proofreading a selection, and the rewrites (Shorter, More formal, Friendlier), for the iPhone keyboard. The
    // prompts and how a reply is read are [Rewriting], called as it is. Which selection can be worked on is a copy
    // of the Android app's `ProofreadRequest` (`sentenceSelection` and the selection branch of `capture`), which
    // lives in the app; `TypingEngineRewritesTest` runs both on the same cases. Pure: nothing here is kept.

    /** A selection to proofread or rewrite: [body] is worked on, and [leading] and [trailing] go back around the answer. */
    class Selected internal constructor(val leading: String, val body: String, val trailing: String)

    object Rewrites {
        /** The longest selection, in UTF-16 units: `ProofreadRequest.LIMIT`. */
        const val LIMIT = 600

        /** The styles' names, in the order the keys show them. */
        val styles: List<String> = listOf("shorter", "formal", "friendly")

        private fun style(name: String): RewriteStyle? = when (name) {
            "shorter" -> RewriteStyle.SHORTER
            "formal" -> RewriteStyle.FORMAL
            "friendly" -> RewriteStyle.FRIENDLY
            else -> null
        }

        /** A selection the strip offers to proofread: no longer than [LIMIT], two or more words that each hold a letter. */
        fun offered(text: String?): Boolean = text != null && text.length <= LIMIT &&
            text.trim().split(Regex("\\s+")).count { word -> word.any(Char::isLetter) } >= 2

        /** The selection [text] split for an explicit request, or null when it is longer than [LIMIT] or has fewer than two letters. */
        fun selected(text: String): Selected? {
            if (text.length > LIMIT || text.count(Char::isLetter) < 2) return null
            return Selected(
                leading = text.take(text.length - text.trimStart().length),
                body = text.trim(),
                trailing = text.takeLast(text.length - text.trimEnd().length),
            )
        }

        /** What to ask the model for [text] in [style] (one of [styles]); null for a style that isn't one. */
        fun prompt(style: String, text: String): String? = style(style)?.let { Rewriting.prompt(it, text) }

        /** The rewrite in the model's [reply], or null when it isn't a plausible rewrite of [original]: [Rewriting.clean]. */
        fun clean(original: String, reply: String, style: String): String? =
            style(style)?.let { Rewriting.clean(original, reply, it) }
    }

    /** A link that ends at the caret and what cleaning leaves: the last [replace] UTF-16 units before the caret, [original], become [text]. */
    class LinkAtCaret internal constructor(val replace: Int, val text: String, val host: String, val original: String)

    /**
     * Clean link for the iPhone: tracking parameters taken off `http` and `https` links. A copy of the Android
     * app's `LinkCleaner`, which is not in this module; `TypingEngineLinksTest` runs both on the same links, so
     * change them together. Pure: nothing is kept, and no engine needs to be open.
     */
    object Links {
        /** Longer text is left as it is: `LinkCleaner.MAX_CHARS`. */
        const val MAX_CHARS = 100_000

        /** How much text before the caret [atCaret] takes at most: `LinkCleaner.CARET_READ_CHARS`. */
        const val CARET_READ_CHARS = 4_096

        private val TRACKERS = setOf(
            "fbclid", "gclid", "gbraid", "wbraid", "dclid", "msclkid", "mc_eid", "mc_cid", "igshid", "igsh",
            "twclid", "ttclid", "yclid", "_hsenc", "_hsmi", "mkt_tok", "srsltid",
        )
        private const val CAMPAIGN_PREFIX = "utm_"
        private val AMAZON_HOSTS = listOf(
            "amazon.com", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.it", "amazon.es", "amazon.ca", "amazon.com.au",
            "amazon.co.jp", "amazon.in", "amazon.com.mx", "amazon.com.br", "amazon.nl", "amazon.se", "amazon.pl",
            "amazon.com.tr", "amazon.com.be", "amazon.sg", "amazon.ae", "amazon.sa", "amazon.eg",
        )
        private val EBAY_HOSTS = listOf(
            "ebay.com", "ebay.co.uk", "ebay.de", "ebay.fr", "ebay.it", "ebay.es", "ebay.ca", "ebay.com.au",
        )
        private val SITE_TRACKERS: List<Pair<List<String>, Set<String>>> = listOf(
            listOf("youtube.com", "youtu.be") to setOf("si", "pp", "feature"),
            listOf("spotify.com") to setOf("si"),
            AMAZON_HOSTS to setOf("ref_", "ascsubtag", "linkcode", "linkid", "creativeasin"),
            listOf("tiktok.com") to setOf("is_from_webapp", "sender_device"),
            listOf("x.com", "twitter.com") to setOf("s", "ref_src"),
            EBAY_HOSTS to setOf("_trkparms", "_trksid"),
            listOf("linkedin.com") to setOf("trk", "trkinfo"),
            listOf("reddit.com") to setOf("share_id"),
        )

        private class Wrapper(val hosts: List<String>, val path: String, val targets: List<String>)
        private val WRAPPERS = listOf(
            Wrapper(listOf("google.com", "www.google.com"), "/url", listOf("q", "url")),
            Wrapper(listOf("l.facebook.com", "lm.facebook.com"), "/l.php", listOf("u")),
            Wrapper(listOf("youtube.com", "www.youtube.com", "m.youtube.com"), "/redirect", listOf("q")),
        )

        private const val PATH_TOKEN_MARKS = "._~%-"
        private const val SEPARATOR = "://"
        private val SCHEMES = listOf("https", "http")
        private const val ENDS_LINK = "<>\"`"
        private const val TRAILING = ".,;:!?'"
        private val CLOSERS = mapOf(')' to '(', ']' to '[', '}' to '{')
        private const val TOKEN_MARKS = "._~%+-="
        private const val MAX_TAIL = 3

        /** [text] with tracking parameters removed from each link in it, or null when there is nothing to remove. */
        fun clean(text: String): String? {
            if (text.length > MAX_CHARS) return null
            var out: StringBuilder? = null
            var copied = 0
            var from = 0
            while (true) {
                val start = nextLink(text, from) ?: break
                val end = linkEnd(text, start)
                val link = text.substring(start, end)
                val cleaned = cleanLink(link)
                if (cleaned != link) {
                    val builder = out ?: StringBuilder(text.length).also { out = it }
                    builder.append(text, copied, start).append(cleaned)
                    copied = end
                }
                from = maxOf(end, start + 1)
            }
            return out?.append(text, copied, text.length)?.toString()
        }

        /**
         * The link that ends at the caret, when cleaning it would change it, or null. [before] is the text before the
         * caret and [whole] says it reaches the start of the field; [after] is the text after the caret, of which two
         * characters are enough. A caret inside a link, a link that runs on past it, and a link whose start may lie
         * before what was read get nothing.
         */
        fun atCaret(before: String, after: String, whole: Boolean): LinkAtCaret? {
            if (before.length > CARET_READ_CHARS || !endsAt(after)) return null
            val runStart = before.indexOfLast { it.isWhitespace() } + 1
            if (runStart == 0 && !whole) return null
            var start = nextLink(before, runStart) ?: return null
            var end = linkEnd(before, start)
            while (!reachesCaret(before.substring(end))) {
                start = nextLink(before, start + 1) ?: return null
                end = linkEnd(before, start)
            }
            val tail = before.substring(end)
            val link = before.substring(start, end)
            val cleaned = cleanLink(link)
            if (cleaned == link) return null
            return LinkAtCaret(
                replace = before.length - start,
                text = cleaned + tail,
                host = host(cleaned.substringBefore('?').substringBefore('#')),
                original = before.substring(start),
            )
        }

        private fun cleanLink(link: String): String = unwrap(link)?.let(::cleanParts) ?: cleanParts(link)

        private fun cleanParts(link: String): String {
            val hash = link.indexOf('#')
            val question = link.indexOf('?').takeUnless { hash in 0 until it } ?: -1
            val baseEnd = when {
                question >= 0 -> question
                hash >= 0 -> hash
                else -> link.length
            }
            val base = link.substring(0, baseEnd)
            val host = host(base)
            val cleanBase = if (onHosts(host, AMAZON_HOSTS)) withoutRefSegment(base) else base
            if (question < 0) return if (cleanBase.length == base.length) link else cleanBase + link.substring(baseEnd)
            val fragment = if (hash >= 0) link.substring(hash) else ""
            val query = link.substring(question + 1, if (hash >= 0) hash else link.length)
            val siteNames = SITE_TRACKERS.filter { onHosts(host, it.first) }.flatMap { it.second }.toSet()
            val parameters = query.split('&')
            val kept = parameters.filterNot { tracks(it, siteNames) }
            if (kept.size == parameters.size && cleanBase.length == base.length) return link
            return if (kept.all { it.isEmpty() }) cleanBase + fragment else cleanBase + "?" + kept.joinToString("&") + fragment
        }

        private fun tracks(parameter: String, siteNames: Set<String>): Boolean {
            val name = parameter.substringBefore('=').lowercase()
            val campaign = name.startsWith(CAMPAIGN_PREFIX) && name.all { it.isLetterOrDigit() || it == '_' }
            if (name !in TRACKERS && !campaign && name !in siteNames) return false
            return parameter.substringAfter('=', "").all { it.isLetterOrDigit() || it in TOKEN_MARKS }
        }

        private fun onHosts(host: String, hosts: List<String>): Boolean = hosts.any { host == it || host.endsWith(".$it") }

        private fun withoutRefSegment(base: String): String {
            val pathStart = base.indexOf('/', base.indexOf(SEPARATOR) + SEPARATOR.length).takeIf { it >= 0 } ?: return base
            val at = base.indexOf("/ref=", pathStart)
            if (at < 0) return base
            val end = base.indexOf('/', at + 1).takeIf { it >= 0 } ?: base.length
            val value = base.substring(at + "/ref=".length, end)
            if (value.isEmpty() || !value.all { it.isLetterOrDigit() || it in PATH_TOKEN_MARKS }) return base
            return base.substring(0, at) + base.substring(end)
        }

        private fun unwrap(link: String): String? {
            val hash = link.indexOf('#')
            val question = link.indexOf('?')
            if (question < 0 || hash in 0 until question) return null
            val base = link.substring(0, question)
            val host = host(base)
            val path = base.substringAfter(SEPARATOR).let { rest -> rest.indexOf('/').takeIf { it >= 0 }?.let { rest.substring(it) } ?: "" }
            val wrapper = WRAPPERS.firstOrNull { host in it.hosts && path == it.path } ?: return null
            val parameters = link.substring(question + 1, if (hash >= 0) hash else link.length).split('&')
            for (name in wrapper.targets) {
                val encoded = parameters.firstOrNull { it.substringBefore('=').lowercase() == name && '=' in it }
                    ?.substringAfter('=') ?: continue
                val target = percentDecoded(encoded) ?: return null
                val scheme = target.substringBefore(SEPARATOR, "").lowercase()
                if (scheme !in SCHEMES || target.length <= scheme.length + SEPARATOR.length) return null
                if (target.any { it.isWhitespace() || it in ENDS_LINK || it.isISOControl() }) return null
                return target
            }
            return null
        }

        /**
         * [text] with each `%XX` decoded as UTF-8, or null when an escape is broken or the bytes are not UTF-8. A `+`
         * stays. As in the app, the other characters are encoded whole, so a character outside the basic plane beside
         * an escape survives, and half of a surrogate pair on its own leaves the link alone.
         */
        private fun percentDecoded(text: String): String? {
            if ('%' !in text) return text
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val value = hex(text[i + 1], text[i + 2]) ?: return null
                    bytes.add(value.toByte())
                    i += 3
                } else {
                    val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
                    if (c.isSurrogate() && !pair) return null
                    val width = if (pair) 2 else 1
                    text.substring(i, i + width).encodeToByteArray().forEach(bytes::add)
                    i += width
                }
            }
            return try {
                bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (_: Exception) {
                null
            }
        }

        /** Two hex digits as a byte's value, read as `toIntOrNull(16)` reads them: a sign counts, so "+f" is 15. */
        private fun hex(high: Char, low: Char): Int? = "$high$low".toIntOrNull(16)

        private fun reachesCaret(tail: String): Boolean = tail.length <= MAX_TAIL && tail.all { it in TRAILING || it in CLOSERS.keys }

        private fun endsAt(after: String): Boolean {
            if (after.isEmpty()) return true
            val c = after[0]
            if (c.isWhitespace() || c in ENDS_LINK || c in CLOSERS.keys) return true
            return c in TRAILING && (after.length < 2 || after[1].isWhitespace() || after[1] in ENDS_LINK || after[1] in CLOSERS.keys)
        }

        private fun host(base: String): String = base.substringAfter(SEPARATOR).substringBefore('/')
            .substringAfterLast('@').substringBefore(':').lowercase()

        private fun nextLink(text: String, from: Int): Int? {
            var at = text.indexOf(SEPARATOR, from)
            while (at >= 0) {
                for (scheme in SCHEMES) {
                    val start = at - scheme.length
                    if (start >= from && text.regionMatches(start, scheme, 0, scheme.length, ignoreCase = true) &&
                        (start == 0 || !text[start - 1].isLetterOrDigit())
                    ) return start
                }
                at = text.indexOf(SEPARATOR, at + 1)
            }
            return null
        }

        private fun linkEnd(text: String, start: Int): Int {
            val open = HashMap<Char, Int>()
            var end = start
            while (end < text.length) {
                val c = text[end]
                if (c.isWhitespace() || c in ENDS_LINK) break
                val opener = CLOSERS[c]
                if (opener != null) {
                    val depth = open[opener] ?: 0
                    if (depth == 0) break
                    open[opener] = depth - 1
                } else if (c in CLOSERS.values) open[c] = (open[c] ?: 0) + 1
                end++
            }
            while (end > start && text[end - 1] in TRAILING) end--
            return end
        }
    }

    private companion object {
        /** Android's bar for "a common word": its capitals are emphasis or a name used once, never a spelling. */
        const val COMMON_SPELLING_SCORE = 40
        /** How much of the text through the last own completion must still stand: [PersonalLearningCoordinator]'s number. */
        const val ANCHOR_CHARS = 64
        /** The contexts Learn my phrases keeps, the one used longest ago leaving first: 512, where Android keeps 1,024. */
        const val PHRASE_CONTEXTS = 512
        /** [PersonalLearningCodec]'s header: "LTPL", version 1. */
        const val SNAPSHOT_MAGIC = 0x4c54504c
        const val SNAPSHOT_VERSION = 1
        /** The service's `DOT_SPACE_MIN_SCORE`: both words of "of.the" must be this common. */
        const val DOT_SPACE_MIN_SCORE = 40
        /** `SavedWords.GROWN_CHARS`: a saved word this long is also known at the start or end of a longer word. */
        const val SAVED_GROWN_CHARS = 4
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
