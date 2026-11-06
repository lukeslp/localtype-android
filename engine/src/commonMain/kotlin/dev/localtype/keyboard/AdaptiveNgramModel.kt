package dev.localtype.keyboard

import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * A small, deterministic word-level model for the latency-critical typing path. It combines a
 * frequency dictionary with bigram context and never performs I/O while predicting. Only
 * candidates the person explicitly taps are persisted, through [LearningStore].
 *
 * With a [prior], followers of the previous word in the corpus prior get a bonus that fades as the
 * person's own pairs after that word accumulate, shaped by [priorWeights]. Without one, nothing
 * about ranking changes.
 */
class AdaptiveNgramModel(
    private val learningStore: LearningStore = NoOpLearningStore,
    seedPhrases: List<String> = DEFAULT_SEED_PHRASES,
    dictionary: WordDictionary = WordDictionary.EMPTY,
    sentenceStarters: List<String> = DEFAULT_SENTENCE_STARTERS,
    prior: CorpusPrior? = null,
    /** How much the prior counts; the defaults are the shipped constants. */
    val priorWeights: PriorWeights = PriorWeights.DEFAULT,
    /** Editorial prediction data; absent in legacy replay and frozen corpus comparisons. */
    var vocabulary: CurrentVocabulary = CurrentVocabulary.EMPTY,
    /** Marks that open a sentence and don't end one ("¿" and "¡" in Spanish); see [isSentenceStart]. Empty in English. */
    private val openingMarks: String = "",
    /** One-letter words that may be offered and learned ([LanguageProfile.oneLetterWords]); English's by default. */
    private val oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords,
) {
    private val unigrams = HashMap<String, Int>()
    private val bigrams = HashMap<String, MutableMap<String, Int>>()

    /** How much of each previous word's bigram count came from seed phrases and starters, not the person. */
    private val seeded = HashMap<String, Int>()

    /** The seed contribution to each pair, so neural blending can reserve only personal followers. */
    private val seededFollowers = HashMap<String, MutableMap<String, Int>>()

    /** Swapped in once the bundled word list finishes loading off the main thread. */
    @Volatile var dictionary: WordDictionary = dictionary

    /** Corpus pairs that give context before anything is learned; can be swapped in after an off-thread load, like [dictionary]. */
    @Volatile var prior: CorpusPrior? = prior

    private val starters = sentenceStarters

    init {
        seedPhrases.forEach { learnPhrase(it) }
        seedStarters()
        replayLearned()
    }

    /** Broadly useful first words ("I", "Hey", "Thanks"…) so a new message never starts with a random word. */
    private fun seedStarters() {
        starters.forEachIndexed { rank, word ->
            // Earlier entries get a little more weight; learned starts add on top.
            repeat(STARTER_WEIGHT + (starters.size - rank) / 6) { recordTransition(START, normalize(word), countWord = false) }
        }
    }

    /** Returns at most [limit] lowercase candidates ranked by context, learning, and frequency. */
    fun predict(beforeCursor: String, limit: Int = 3): List<String> = predictScored(beforeCursor, limit).map { it.first }

    /** [predict] with each candidate's score, in the same order, for the typing log and its replay. */
    fun predictScored(beforeCursor: String, limit: Int = 3): List<Pair<String, Int>> {
        require(limit > 0)
        val prefix = normalize(activePrefix(beforeCursor))
        val previous = lastCompletedWord(beforeCursor) ?: START.takeIf { isSentenceStart(beforeCursor) }
        val words = dictionary
        val scores = HashMap<String, Int>()
        // Empty without a prior, so the scores and the candidate set are exactly what they were before priors existed.
        val priorBonus = prior?.let { p -> previous?.let { priorBonuses(p, it) } }.orEmpty()

        fun add(word: String) {
            if (word.length < 2 && word !in oneLetterWords) return
            if (prefix.isNotEmpty() && !words.completesPrefix(word, prefix)) return
            if (scores.containsKey(word)) return
            val context = previous?.let { bigrams[it]?.get(word) } ?: 0
            scores[word] = words.score(word) + (unigrams[word] ?: 0) * UNIGRAM_WEIGHT + context * CONTEXT_WEIGHT +
                (priorBonus[word] ?: 0)
        }

        previous?.let { bigrams[it]?.keys?.forEach(::add) }
        priorBonus.keys.forEach(::add)
        words.completions(prefix, DICTIONARY_POOL).forEach { (word, _) -> add(word) }
        if (prefix.isNotEmpty()) unigrams.keys.forEach(::add)

        vocabulary.candidates(prefix, previous).forEach { (word, _) ->
            add(word)
            // Do not add editorial counts to learned tables or change dictionary/correction scores.
            val existing = scores.getValue(word)
            scores[word] = existing + vocabulary.predictionLift(word, words.score(word), previous)
        }

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }
    }

    /** Learns a candidate only after a deliberate tap, rather than retaining raw keystrokes. */
    fun accept(previousWord: String?, selectedWord: String, persist: Boolean = true) {
        val next = normalizeWord(selectedWord) ?: return
        val previous = previousWord?.let(::normalizeWord)
        recordTransition(previous ?: START, next)
        if (persist) learningStore.record(previous ?: START, next)
    }

    /**
     * Learns a word the person typed and finished with a space or punctuation. Only real words are
     * learned (dictionary, already learned, or explicitly kept) so typos never become suggestions.
     */
    fun learnTypedWord(beforeSeparator: String, persist: Boolean, isKept: (String) -> Boolean = { false }): Boolean {
        val word = learnableWord(beforeSeparator, isKept) ?: return false
        accept(lastCompletedWord(beforeSeparator), word, persist)
        return true
    }

    /** Whether [learnTypedWord] would learn the word that ends [beforeSeparator]: the same test, with nothing learned. */
    fun learnable(beforeSeparator: String, isKept: (String) -> Boolean = { false }): Boolean = learnableWord(beforeSeparator, isKept) != null

    private fun learnableWord(beforeSeparator: String, isKept: (String) -> Boolean): String? {
        val word = normalizeWord(activePrefix(beforeSeparator)) ?: return null
        if (word.length < 2 && word !in oneLetterWords) return null
        if (word !in dictionary && !knows(word) && !isKept(word)) return null
        return word
    }

    /** Forgets everything learned from taps, keeping the dictionary and starter phrases. */
    fun clearLearned(seedPhrases: List<String> = DEFAULT_SEED_PHRASES) {
        learningStore.clear()
        unigrams.clear()
        bigrams.clear()
        seeded.clear()
        seededFollowers.clear()
        seedPhrases.forEach { learnPhrase(it) }
        seedStarters()
    }

    /** How often [word] has followed [previous] in what the person typed or tapped. */
    fun pairCount(previous: String, word: String): Int = bigrams[normalize(previous)]?.get(normalize(word)) ?: 0

    /** Detached single-row evidence for the correction worker; never exposes mutable tables. */
    fun correctionCounts(previous: String?): Map<String, Int> = previous?.let { bigrams[normalize(it)]?.toMap() }.orEmpty()

    /** Personal followers only, strongest first; reads one in-memory row without snapshotting tables. */
    fun learnedFollowers(previous: String): List<Pair<String, Int>> {
        val normalized = normalize(previous)
        val seeds = seededFollowers[normalized]
        return bigrams[normalized].orEmpty().asSequence()
            .map { (word, count) -> word to (count - (seeds?.get(word) ?: 0)).coerceAtLeast(0) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .toList()
    }

    /**
     * How many words the person has typed or tapped after [previous] (normalized, or [START]):
     * every learned pair, but not the seed phrases or starters. The prior fades by this.
     */
    fun ownCountAfter(previous: String): Int =
        ((bigrams[previous]?.values?.sum() ?: 0) - (seeded[previous] ?: 0)).coerceAtLeast(0)

    /** The prior's bonus for each follower of [previous], faded by the person's own count after it. */
    private fun priorBonuses(prior: CorpusPrior, previous: String): Map<String, Int> {
        val followers = prior.followers(previous)
        if (followers.isEmpty()) return emptyMap()
        val own = ownCountAfter(previous)
        val bonuses = HashMap<String, Int>(followers.size * 2)
        for (follower in followers) {
            val bonus = priorBonus(follower.logProbability, own, priorWeights)
            if (bonus > 0) bonuses[follower.word] = bonus
        }
        return bonuses
    }

    /** True for words learned from taps, which spelling correction must respect. */
    fun knows(word: String): Boolean = unigrams.containsKey(word)

    /** An explicit dictionary spelling remains protected, including missing-apostrophe forms. */
    fun isAddedWord(word: String): Boolean = bigrams[ADDED_WORDS_CONTEXT]?.containsKey(normalize(word)) == true

    /** Only matching typed prefixes reserve a neural slot; additions are not sentence-start observations. */
    fun addedWords(prefix: String): List<String> {
        val normalized = normalize(prefix)
        if (normalized.isEmpty()) return emptyList()
        return bigrams[ADDED_WORDS_CONTEXT].orEmpty().keys.filter { it.startsWith(normalized) }
            .sortedWith(compareByDescending<String> { unigrams[it] ?: 0 }.thenBy { it })
    }

    /** Counts a word once without a previous word, for replaying a mutation whose context is unknown. */
    fun countWord(word: String) {
        val next = normalizeWord(word) ?: return
        unigrams[next] = (unigrams[next] ?: 0) + 1
    }

    /** Everything learned so far, in memory, so the typing log can snapshot it and a replay rebuild it. */
    fun tables(): Tables = Tables(
        unigrams = HashMap(unigrams),
        bigrams = bigrams.flatMap { (previous, choices) -> choices.map { (next, count) -> Triple(previous, next, count) } },
    )

    /** Empty field, or the word being typed follows a sentence end, with any opening marks between. */
    fun isSentenceStart(beforeCursor: String): Boolean = Companion.isSentenceStart(beforeCursor, openingMarks)

    fun activePrefix(beforeCursor: String): String = Companion.activePrefix(beforeCursor)

    fun lastCompletedWord(beforeCursor: String): String? = Companion.lastCompletedWord(beforeCursor)

    private fun replayLearned() {
        restoreLearned(learningStore.entries())
    }

    /** Adds a background-loaded baseline without recounting words learned while it was loading. */
    fun restoreLearned(entries: List<Triple<String, String, Int>>) {
        entries.forEach { (previous, next, count) ->
            val restored = count.coerceIn(0, MAX_REPLAY_COUNT)
            if (restored == 0) return@forEach
            unigrams[next] = (unigrams[next] ?: 0) + restored
            val choices = bigrams.getOrPut(previous) { HashMap() }
            choices[next] = (choices[next] ?: 0) + restored
        }
    }

    private fun learnPhrase(phrase: String) {
        val words = WORD_REGEX.findAll(phrase).map { normalize(it.value) }.toList()
        // Seeds teach context only; word frequency comes from the dictionary and from taps.
        words.zipWithNext().forEach { (previous, next) ->
            repeat(SEED_WEIGHT) { recordTransition(previous, next, countWord = false) }
        }
    }

    private fun recordTransition(previous: String?, next: String, countWord: Boolean = true) {
        if (countWord) unigrams[next] = (unigrams[next] ?: 0) + 1
        if (previous == null) return
        val choices = bigrams.getOrPut(previous) { HashMap() }
        choices[next] = (choices[next] ?: 0) + 1
        // Only seed phrases and starters skip the word count, so this tells their counts from the person's.
        if (!countWord) {
            seeded[previous] = (seeded[previous] ?: 0) + 1
            val followers = seededFollowers.getOrPut(previous) { HashMap() }
            followers[next] = (followers[next] ?: 0) + 1
        }
    }

    private fun normalizeWord(word: String): String? = WORD_REGEX.find(word)?.value?.let { normalize(it) }

    /**
     * The shape of the prior's bonus: [weight] points per nat of log-probability above [floor], so
     * at most `-floor × weight`, scaled by `fade / (fade + n)` where n is the person's own count
     * after the previous word. [fade] is a pseudo-count: at n = [fade] the bonus has halved.
     */
    data class PriorWeights(val weight: Float = 20f, val floor: Float = -10f, val fade: Float = 16f) {
        init {
            require(weight >= 0f && floor < 0f && fade > 0f) { "bad prior weights $this" }
        }

        companion object {
            /**
             * The setting that passed the preregistered corpus gate on 2026-10-04 (model/README.md,
             * "The corpus prior's gate"): weight 20, chosen on dev from six, then scored once on test.
             * The fade is untested. Nothing is learned from a corpus line, so the scorer never sees it
             * act, and it stays a guess until the typing log.
             */
            val DEFAULT = PriorWeights()
        }
    }

    /** A copy of the learned counts: every unigram, and every (previous, next, count) bigram. */
    data class Tables(val unigrams: Map<String, Int>, val bigrams: List<Triple<String, String, Int>>)

    interface LearningStore {
        fun entries(): List<Triple<String, String, Int>>
        fun record(previous: String, next: String)
        fun clear()
    }

    private object NoOpLearningStore : LearningStore {
        override fun entries() = emptyList<Triple<String, String, Int>>()
        override fun record(previous: String, next: String) = Unit
        override fun clear() = Unit
    }

    companion object {
        /** Stored in place of a previous word when a candidate starts a sentence or field. */
        const val START = "\u0002"
        /** Reserved dictionary row; never credited as a sentence-start or typed context. */
        const val ADDED_WORDS_CONTEXT = "\u0003"
        private const val CONTEXT_WEIGHT = 40
        private const val UNIGRAM_WEIGHT = 6
        private const val SEED_WEIGHT = 2
        private const val STARTER_WEIGHT = 2
        private const val MAX_REPLAY_COUNT = 12
        private const val DICTIONARY_POOL = 48

        /**
         * Points added for a prior follower with natural-log probability [logProbability] when the
         * person has [ownCount] words of their own after the same previous word. With the default
         * [weights] (20 points per nat above ln P = -10, about 1 in 22,000, and a fade of 16) that is
         * up to 200 for a certain follower, twice the dictionary score's range, nothing at or below
         * the floor, scaled by `16 / (16 + ownCount)`. The fade treats the prior like a pseudo-count
         * of 16 words, so the person's own pairs take over as they accumulate.
         */
        fun priorBonus(logProbability: Float, ownCount: Int, weights: PriorWeights = PriorWeights.DEFAULT): Int {
            val strength = (logProbability - weights.floor).coerceIn(0f, -weights.floor)
            return (strength * weights.weight * weights.fade / (weights.fade + ownCount.coerceAtLeast(0))).roundToInt()
        }
        val WORD_REGEX = Regex("[\\p{L}][\\p{L}'’-]*")
        private val PREFIX_REGEX = Regex("[\\p{L}][\\p{L}'’-]*\\z")

        // The context helpers live here so the typing log and its replay compute exactly what predict does.

        fun normalize(word: String) = word.replace('’', '\'').lowercase()

        fun activePrefix(beforeCursor: String): String = PREFIX_REGEX.find(beforeCursor)?.value.orEmpty()

        /**
         * Empty field, or the word being typed follows a sentence end. [openingMarks] stand between the two without
         * ending anything: with "¿", "Hola. ¿qué" starts a sentence and "Hola ¿qué" doesn't.
         */
        fun isSentenceStart(beforeCursor: String, openingMarks: String = ""): Boolean {
            // Trim spaces and tabs only: a newline is itself a sentence boundary.
            var tail = beforeCursor.dropLast(activePrefix(beforeCursor).length).trimEnd(' ', '\t')
            if (openingMarks.isNotEmpty() && tail.lastOrNull()?.let { it in openingMarks } == true) {
                tail = tail.trimEnd { it in openingMarks }.trimEnd(' ', '\t')
            }
            return tail.isEmpty() || tail.last() in ".!?\n"
        }

        fun lastCompletedWord(beforeCursor: String): String? {
            val withoutPrefix = beforeCursor.dropLast(activePrefix(beforeCursor).length)
            // Punctuation, numbers, and sentence ends reset context: "Done. The" should not predict from "done".
            val tail = withoutPrefix.trimEnd(' ', '\t')
            if (tail.isEmpty() || !tail.last().isLetter()) return null
            return WORD_REGEX.findAll(tail).lastOrNull()?.value?.let { normalize(it) }
        }

        /**
         * A model holding exactly [tables]: no seeds, no starters, no store. The replay starts from a snapshot this way.
         *
         * With a [prior], pass the [seedPhrases] and [sentenceStarters] of the model that wrote the tables. They add no
         * counts here (the tables already hold them); they only mark which counts weren't the person's, so the prior
         * fades exactly as it did in that model.
         */
        fun fromTables(
            tables: Tables,
            dictionary: WordDictionary,
            prior: CorpusPrior? = null,
            seedPhrases: List<String> = emptyList(),
            sentenceStarters: List<String> = emptyList(),
            priorWeights: PriorWeights = PriorWeights.DEFAULT,
        ): AdaptiveNgramModel {
            val model = AdaptiveNgramModel(NoOpLearningStore, emptyList(), dictionary, emptyList(), prior, priorWeights)
            model.unigrams.putAll(tables.unigrams)
            tables.bigrams.forEach { (previous, next, count) ->
                model.bigrams.getOrPut(previous) { HashMap() }[next] = count
            }
            if (seedPhrases.isNotEmpty() || sentenceStarters.isNotEmpty()) {
                val seeds = AdaptiveNgramModel(NoOpLearningStore, seedPhrases, WordDictionary.EMPTY, sentenceStarters)
                model.seeded.putAll(seeds.seeded)
                seeds.seededFollowers.forEach { (previous, choices) -> model.seededFollowers[previous] = HashMap(choices) }
            }
            return model
        }

        /** First words that suit almost any message, roughly in order of usefulness. */
        val DEFAULT_SENTENCE_STARTERS = listOf(
            "I", "Hey", "Thanks", "Yes", "Hi", "The", "Ok", "Sounds", "Just", "What",
            "Can", "No", "Sorry", "Good", "How", "We", "It", "Are", "Do", "Let's",
        )

        /** Common conversational pairs so context works before anything has been learned. */
        val DEFAULT_SEED_PHRASES = listOf(
            "thank you very much",
            "thank you so much",
            "let me know",
            "how are you doing",
            "I will be there",
            "I am on my way",
            "see you soon",
            "see you later",
            "good morning",
            "good night",
            "sounds good to me",
            "please let me know",
            "can we talk later",
            "I don't know",
            "that makes sense",
            "have a great day",
            "looking forward to it",
            "I will check and get back to you",
            "this looks great",
            "I think so",
            "no problem",
            "on my way",
            "running late",
            "happy birthday",
            "talk to you later",
            "what time is it",
            "are you free",
            "love you",
            "miss you",
            "I'm sorry",
            "just wanted to",
            "as soon as possible",
        )
    }
}
