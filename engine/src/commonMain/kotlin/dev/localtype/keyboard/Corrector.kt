package dev.localtype.keyboard

import kotlin.concurrent.Volatile


/**
 * Spelling correction that knows where fingers land. Each candidate word is scored by how common it
 * is, how well it fits after the previous word, and how far the taps were from its letters: a tap on
 * the edge of "k" toward "u" is a cheap slip, a tap across the keyboard is not. Unknown words and
 * listed missing-apostrophe forms are corrected. A clear winner is applied on space; a closer call
 * is only suggested. Learned lift offsets adjust costs, never the letters the keyboard types.
 */
class Corrector(
    private val dictionary: () -> WordDictionary,
    private val isKnown: (String) -> Boolean = { false },
    /** Uncommon but real words: never corrected, and allowed as one-edit correction targets. */
    private val lexicon: () -> Lexicon = { Lexicon.EMPTY },
    /** How often [word] has followed the previous word, from what the person has typed. */
    private val contextCount: (previous: String, word: String) -> Int = { _, _ -> 0 },
    /** Never offered as a correction target, such as swear words while they're switched off. */
    private val blocked: (String) -> Boolean = { false },
    /** Undo and the quoted literal keep a word even when the contraction table recognizes it. */
    private val isKept: (String) -> Boolean = { false },
    private val touchOffsets: () -> TouchOffsets? = { null },
    /** The language whose letters, keys and accents the costs use; English unless told otherwise. */
    private val language: () -> LanguageProfile = { LanguageProfile.ENGLISH },
) {
    /** A proposed fix. Only [automatic] ones replace the word on space; others are just suggested. */
    data class Correction(val word: String, val automatic: Boolean)

    /** The best fix to apply automatically, or null. */
    fun correct(
        typed: String,
        touches: List<Touch?>? = null,
        previous: String? = null,
        sentenceStart: Boolean = false,
        afterComma: Boolean = false,
    ): String? = propose(typed, touches, previous, sentenceStart, afterComma)?.takeIf { it.automatic }?.word

    /** The best fix to show, including ones not certain enough to apply. */
    fun suggest(
        typed: String,
        touches: List<Touch?>? = null,
        previous: String? = null,
        sentenceStart: Boolean = false,
        afterComma: Boolean = false,
    ): String? {
        val legacy = propose(typed, touches, previous, sentenceStart, afterComma)
        // The boundary decision is unchanged. A missing possessive apostrophe needs an explicit tap.
        return if (legacy?.automatic == true) legacy.word else possessiveSuggestion(typed) ?: legacy?.word
    }

    /** English only: an apostrophe-s possessive ("friends" → "friend's"). */
    fun possessiveSuggestion(typed: String): String? = if (!language().english) null else PossessiveSuggestion.suggest(
        typed, dictionary(), lexicon(), known = isKnown, kept = isKept, blocked = blocked,
    )

    /** The missing-apostrophe table ("dont" → "don't") is English, so other languages never consult it. */
    private fun contraction(word: String, previous: String?, sentenceStart: Boolean, afterComma: Boolean): Correction? =
        if (language().english) Contractions.propose(word, previous, sentenceStart, afterComma) else null

    /** A selected word is complete: an unrelated longer completion must not hide its spelling offer. */
    fun suggestForStrip(
        typed: String,
        touches: List<Touch?>? = null,
        previous: String? = null,
        sentenceStart: Boolean = false,
        afterComma: Boolean = false,
        explicitSelection: Boolean = false,
        completionsAvailable: Boolean = false,
    ): String? {
        if (!explicitSelection && completionsAvailable &&
            contraction(typed.lowercase(), previous, sentenceStart, afterComma) == null &&
            possessiveSuggestion(typed) == null) return null
        return suggest(typed, touches, previous, sentenceStart, afterComma)
    }

    private data class MemoKey(val typed: String, val touches: List<Touch?>?, val previous: String?, val sentenceStart: Boolean, val afterComma: Boolean, val policyVersion: String = CorrectionPolicy.LEGACY_VERSION)

    /** Refresh and space both ask about the same word; remember recent answers per dictionary. */
    private val memo = LruCache<MemoKey, Correction?>(64)
    private val memoLock = EngineLock()

    /** A remembered answer, which may itself be "no correction". */
    private class Remembered(val answer: Correction?)
    private var memoDictionary: WordDictionary? = null
    private var memoOffsets: TouchOffsets? = null
    private var memoOffsetsRevision = 0L
    private var memoLanguage: LanguageProfile? = null

    fun propose(typed: String, touches: List<Touch?>? = null, previous: String? = null, sentenceStart: Boolean = false, afterComma: Boolean = false): Correction? {
        val words = dictionary()
        val offsets = touchOffsets()
        val lang = language()
        // Touches only help when there is one per letter; otherwise fall back to key centers.
        val aligned = touches?.takeIf { it.size == typed.length }
        val key = MemoKey(typed, aligned?.toList(), previous, sentenceStart, afterComma)
        val remembered = memoLock.withLock {
            if (memoDictionary !== words || memoOffsets !== offsets || memoOffsetsRevision != (offsets?.revision ?: 0L) || memoLanguage !== lang) {
                memo.clear()
                memoDictionary = words
                memoOffsets = offsets
                memoOffsetsRevision = offsets?.revision ?: 0L
                memoLanguage = lang
            }
            if (memo.containsKey(key)) Remembered(memo[key]) else null
        }
        if (remembered != null) return remembered.answer
        return computeProposal(typed, compensatedTouches(typed, aligned, offsets), previous?.lowercase(), sentenceStart, afterComma, keysFor(lang))
            .also { result -> memoLock.withLock { memo[key] = result } }
    }

    /** Words learned or kept since the last answer can change what's correct, so they reset the memo. */
    fun forgetRecentAnswers() = memoLock.withLock { memo.clear() }

    /** A correction target with what the scan needs precomputed: its frequency and which letters it has. */
    private class Target(val word: String, val base: Float, keys: Keys) {
        val letters: Long = keys.mask(word)
    }

    /**
     * Builds the lookup index for word lists about to be swapped in, off the main thread, so the first
     * typo after loading isn't slowed down by it.
     */
    fun prepare(words: WordDictionary, known: Lexicon) {
        targetsByLength(words, known, keysFor(language()))
    }

    /**
     * Dictionary words grouped by length, most frequent first, without the weak ones; rebuilt when
     * the dictionary or lexicon changes.
     */
    private class Index(val words: WordDictionary, val known: Lexicon, val keys: Keys, val targets: Map<Int, List<Target>>)

    @Volatile private var index: Index? = null

    private fun targetsByLength(words: WordDictionary, known: Lexicon, keys: Keys): Map<Int, List<Target>> {
        index?.takeIf { it.words === words && it.known === known && it.keys === keys }?.let { return it.targets }
        val built = words.allWords()
            .filter { !isWeak(it, words, known) }
            .sortedByDescending(words::score)
            .map { Target(it, words.score(it).toFloat(), keys) }
            .groupBy { it.word.length }
        index = Index(words, known, keys, built)
        return built
    }

    private fun computeProposal(typed: String, touches: List<Touch?>?, previous: String?, sentenceStart: Boolean, afterComma: Boolean, keys: Keys): Correction? {
        val word = typed.replace('’', '\'').lowercase()
        if (word.length < 2 || word.any { !it.isLetter() && it != '\'' }) return null
        // Leave acronyms and mid-word capitals ("NASA", "iPhone") alone.
        if (typed.drop(1).any { it.isUpperCase() }) return null
        if (isKept(word)) return null
        contraction(word, previous, sentenceStart, afterComma)?.let { fix ->
            return fix.takeUnless { blocked(it.word) }
        }
        val words = dictionary()
        val known = lexicon()
        if (isKnown(word) || word in known) return null
        // SCOWL drops 's forms, so possessives and 's contractions of real words ("friend's", "here's",
        // "parents'") are in neither list. Leave them as typed; a weak or unknown stem ("ther's") is still a typo,
        // and so is a swapped apostrophe whose 's form is a dictionary word ("its'" for "it's").
        val stem = when {
            word.endsWith("'s") -> word.dropLast(2)
            word.endsWith("s'") && word.dropLast(2) + "'s" !in words -> word.dropLast(1)
            else -> null
        }
        if (!stem.isNullOrEmpty() && (isKnown(stem) || stem in known || (stem in words && !isWeak(stem, words, known)))) return null
        // Subtitle fragments ("fo", "ot") are in the frequency list but not the lexicon; they stay
        // correctable, and only a much more common word replaces them.
        val weak = word in words && isWeak(word, words, known)
        if (word in words && !weak) return null
        // A capital mid-sentence is probably a name: only a single slip is fixed.
        val likelyName = typed.first().isUpperCase() && !sentenceStart
        val autoBudget = if (likelyName || weak) ONE_EDIT_COST else autoBudget(word.length)
        val searchBudget = if (likelyName || weak) ONE_EDIT_COST else autoBudget + SUGGEST_EXTRA_COST

        val contextFor: (String) -> Float = { candidate ->
            previous?.let { minOf(contextCount(it, candidate) * CONTEXT_WEIGHT, CONTEXT_CAP).toFloat() } ?: 0f
        }
        val ranked = rank(word, touches, searchBudget, words, known, contextFor, contextBound = if (previous != null) CONTEXT_CAP.toFloat() else 0f, keys = keys)
        if (ranked.isEmpty()) return null
        val (best, cost, score) = ranked.first()
        if (score < SUGGEST_MIN_SCORE) return null
        val runnerUp = ranked.getOrNull(1)?.score ?: Float.NEGATIVE_INFINITY
        val beatsTyped = !weak || baseScore(best, words, known) - words.score(word) >= WEAK_MARGIN
        // Dropping letters to reach a much shorter word is how slang turns into nonsense ("zoomer" → "some").
        val lengthOk = word.length - best.count { it != '\'' } <= if (word.length >= 8) 2 else 1
        val automatic = cost <= autoBudget && score >= AUTO_MIN_SCORE && score - runnerUp >= AUTO_MARGIN && beatsTyped && lengthOk
        if (weak && !automatic) return null
        return Correction(best, automatic)
    }

    /**
     * Owner-thread capture for contextual correction. No learned/kept callbacks escape this call.
     * The expanded pool is separate from the legacy two-winner scan and never changes its answer.
     * Guards and contractions retain their legacy decisions. Field/editor eligibility is external.
     */
    fun candidates(
        typed: String, touches: List<Touch?>? = null, previous: String? = null,
        sentenceStart: Boolean = false, afterComma: Boolean = false,
    ): CorrectionSnapshot {
        val safeTouches = touches?.takeIf { list -> list.size == typed.length &&
            list.all { it == null || (it.x.isFinite() && it.y.isFinite()) } }?.toList()
        val baseline = propose(typed, safeTouches, previous, sentenceStart, afterComma)
        val word = typed.replace('’', '\'').lowercase()
        val words = dictionary()
        val known = lexicon()
        val weak = word in words && isWeak(word, words, known)
        val name = typed.firstOrNull()?.isUpperCase() == true && !sentenceStart
        val stem = when {
            word.endsWith("'s") -> word.dropLast(2)
            word.endsWith("s'") && word.dropLast(2) + "'s" !in words -> word.dropLast(1)
            else -> null
        }
        val guard = when {
            word.length < 2 || word.any { !it.isLetter() && it != '\'' } -> "invalid-word"
            typed.drop(1).any { it.isUpperCase() } -> "capitalization"
            isKept(word) -> "kept"
            blocked(word) -> "excluded"
            contraction(word, previous?.lowercase(), sentenceStart, afterComma) != null -> "contraction"
            isKnown(word) || word in known || (word in words && !weak) -> "known-or-learned"
            !stem.isNullOrEmpty() && (isKnown(stem) || stem in known || (stem in words && !isWeak(stem, words, known))) -> "possessive"
            weak -> "weak-word"
            name -> "likely-name"
            else -> null
        }
        val budget = if (name || weak) ONE_EDIT_COST else autoBudget(word.length)
        if (guard != null) return CorrectionSnapshot(typed, word, emptyList(), baseline, budget, false, guard)
        val compensated = compensatedTouches(word, safeTouches, touchOffsets())
        val keys = keysFor(language())
        val prev = previous?.lowercase()
        val contextFor: (String) -> Float = { candidate ->
            prev?.let { minOf(contextCount(it, candidate) * CONTEXT_WEIGHT, CONTEXT_CAP).toFloat() } ?: 0f
        }
        val pool = rank(word, compensated, budget + SUGGEST_EXTRA_COST, words, known, contextFor,
            if (prev != null) CONTEXT_CAP.toFloat() else 0f, keys, CorrectionSnapshot.MAX_CANDIDATES)
        val evidence = pool.map { candidate ->
            val frequency = baseScore(candidate.word, words, known)
            CorrectionCandidate(candidate.word, candidate.cost, cost(word, candidate.word, null, Float.MAX_VALUE, keys),
                safeTouches?.count { it != null } ?: 0, frequency, contextFor(candidate.word), candidate.score,
                word.length - candidate.word.count { it != '\'' } <= if (word.length >= 8) 2 else 1,
                candidate.word !in words)
        }
        return CorrectionSnapshot(typed, word, evidence, baseline, budget, true, null)
    }

    private data class Scored(val word: String, val cost: Float, val score: Float)

    /**
     * The two best targets within [budget]. Words are scanned most frequent first, and each one only
     * gets the cost it could afford while still beating the current runner-up, so the scan narrows fast.
     */
    private fun rank(
        word: String,
        touches: List<Touch?>?,
        budget: Float,
        words: WordDictionary,
        known: Lexicon,
        contextFor: (String) -> Float,
        contextBound: Float,
        keys: Keys,
        poolSize: Int = 2,
    ): List<Scored> {
        val top = ArrayList<Scored>(3)
        // Each typed letter a candidate lacks entirely must be replaced or dropped; this is the least
        // that can cost, so most words are ruled out without the full comparison.
        val leastFix = FloatArray(word.length) { i ->
            val c = word[i]
            val touch = touches?.getOrNull(i)
            var cheapest = extraKey(word, i, keys)
            for (other in keys.letters) if (other != c) cheapest = minOf(cheapest, substitution(c, touch, other, keys))
            cheapest
        }
        val typedBits = LongArray(word.length) { keys.bit(word[it]) }
        fun lowerBound(letters: Long): Float {
            var total = 0f
            for (i in word.indices) {
                val bit = typedBits[i]
                if (bit != 0L && letters and bit == 0L) total += leastFix[i]
            }
            return total
        }
        fun offer(candidate: String, base: Float, limit: Float) {
            if (blocked(candidate) || top.any { it.word == candidate }) return
            val cost = cost(word, candidate, touches, limit, keys)
            if (cost > limit) return
            val scored = Scored(candidate, cost, base + contextFor(candidate) - cost * COST_WEIGHT)
            top += scored
            top.sortWith(compareByDescending<Scored> { it.score }.thenBy { it.word })
            if (top.size > poolSize) top.removeAt(top.lastIndex)
        }
        fun affordable(base: Float): Float {
            val floor = if (top.size < poolSize) SUGGEST_MIN_SCORE else top[poolSize - 1].score
            return minOf(budget, (base + contextBound - floor) / COST_WEIGHT)
        }
        val maxLengthChange = (budget / MIN_INSERT_OR_DELETE).toInt().coerceAtMost(3)
        val byLength = targetsByLength(words, known, keys)
        for (length in (word.length - maxLengthChange)..(word.length + maxLengthChange)) {
            for (target in byLength[length].orEmpty()) {
                val limit = affordable(target.base)
                // Sorted by frequency, so nothing later in this length can do better.
                if (limit < 0f) break
                if (target.word == word || !couldStartAs(word, target.word, keys)) continue
                if (lowerBound(target.letters) > limit) continue
                offer(target.word, target.base, limit)
            }
        }
        // Rare words from the lexicon, reached by a single slip ("labyrnith" → "labyrinth").
        if (known.size > 0) {
            for (candidate in oneEditNeighbors(word, keys)) {
                if (candidate !in known || candidate in words) continue
                val limit = minOf(affordable(RARE_WORD_BASE), ONE_EDIT_COST)
                if (limit >= 0f) offer(candidate, RARE_WORD_BASE, limit)
            }
        }
        return top
    }

    private fun isWeak(word: String, words: WordDictionary, known: Lexicon): Boolean =
        known.size > 0 && word !in known && words.score(word) < WEAK_SCORE

    private fun baseScore(candidate: String, words: WordDictionary, known: Lexicon): Float {
        val score = words.score(candidate)
        return if (score > 0) score.toFloat() else if (candidate in known) RARE_WORD_BASE else 0f
    }

    /**
     * Cheap filter before the full cost: the first letter matches, sits beside, or swapped with the
     * second, or differs only by an accent ("angel" for "ángel").
     */
    private fun couldStartAs(word: String, candidate: String, keys: Keys): Boolean {
        val a = word[0]
        val b = candidate[0]
        return a == b || keys.areNeighbors(a, b) ||
            (word.length > 1 && word[1] == b) || (candidate.length > 1 && candidate[1] == a) ||
            (keys.folds && keys.language.fold(a) == keys.language.fold(b))
    }

    private fun oneEditNeighbors(word: String, keys: Keys): Sequence<String> = sequence {
        for (i in 1 until word.length) yield(word.substring(0, i) + "'" + word.substring(i))
        for (i in word.indices) {
            yield(word.removeRange(i, i + 1))
            for (c in keys.letters) if (c != word[i]) yield(word.substring(0, i) + c + word.substring(i + 1))
            if (i < word.lastIndex) {
                val chars = word.toCharArray()
                chars[i] = word[i + 1]
                chars[i + 1] = word[i]
                yield(chars.concatToString())
            }
        }
        for (i in 0..word.length) for (c in keys.letters) yield(word.substring(0, i) + c + word.substring(i))
    }

    companion object {
        /** Cost of one ordinary slip; also the ceiling for names, weak words and rare targets. */
        private const val ONE_EDIT_COST = 1.0f
        private const val SUGGEST_EXTRA_COST = 0.6f
        private const val MIN_INSERT_OR_DELETE = 0.5f

        /** Up to one slip on short words, two from four letters, three from eight. */
        fun autoBudget(length: Int): Float = when {
            length <= 3 -> 1.2f
            length <= 7 -> 2.4f
            else -> 3.4f
        }

        private const val COST_WEIGHT = 28f
        private const val CONTEXT_WEIGHT = 4
        private const val CONTEXT_CAP = 16
        private const val RARE_WORD_BASE = 36f
        private const val SUGGEST_MIN_SCORE = -15f
        private const val AUTO_MIN_SCORE = 0f
        private const val AUTO_MARGIN = 6f
        private const val WEAK_SCORE = 50
        private const val WEAK_MARGIN = 20f

        // Costs of each kind of slip, in "ordinary slips".
        private const val SUBSTITUTE_BASE = 0.3f
        private const val SUBSTITUTE_PER_SQUARED_KEY = 0.4f
        private const val SUBSTITUTE_MAX = 1.5f
        private const val EXTRA_KEY = 1.0f
        private const val EXTRA_REPEAT = 0.5f
        private const val EXTRA_NEIGHBOR = 0.7f
        private const val MISSED_KEY = 1.0f
        private const val MISSED_DOUBLE = 0.6f
        private const val MISSED_APOSTROPHE = 0.1f
        private const val SWAP = 0.6f

        /** A missing or extra accent ("tambien" for "también"): as cheap as a missing apostrophe. */
        private const val ACCENT = 0.1f

        /**
         * Cost of meaning [intended] where [touch] landed (or the typed key's center when unknown).
         * Letters that differ only by an accent cost [ACCENT]. Meaning a held letter ("é") from
         * another key costs the slip to its key ("e") plus the accent.
         */
        private fun substitution(typed: Char, touch: Touch?, intended: Char, keys: Keys): Float {
            if (typed == intended) return 0f
            // Kept small: this runs for every cell of every candidate, so the accent path is separate.
            if (keys.folds) return accentedSubstitution(typed, touch, intended, keys)
            val from = touch ?: keys.geometry.center(typed) ?: return ONE_EDIT_COST
            val to = keys.geometry.center(intended) ?: return ONE_EDIT_COST
            return slip(from.x - to.x, from.y - to.y)
        }

        private fun accentedSubstitution(typed: Char, touch: Touch?, intended: Char, keys: Keys): Float {
            val language = keys.language
            if (language.fold(typed) == language.fold(intended)) return ACCENT
            val target = keys.keyOf(intended)
            val from = touch ?: keys.geometry.center(keys.keyOf(typed)) ?: return ONE_EDIT_COST
            val to = keys.geometry.center(target) ?: return ONE_EDIT_COST
            return slip(from.x - to.x, from.y - to.y) + if (target != intended) ACCENT else 0f
        }

        /** An extra key: a double tap, or brushing the key beside the one before or after it. */
        private fun extraKey(typed: String, i: Int, keys: Keys): Float {
            val c = typed[i]
            if (i > 0 && typed[i - 1] == c) return EXTRA_REPEAT
            val beside = (i > 0 && keys.areNeighbors(c, typed[i - 1])) || (i < typed.lastIndex && keys.areNeighbors(c, typed[i + 1]))
            return if (beside) EXTRA_NEIGHBOR else EXTRA_KEY
        }

        private fun missedKey(candidate: String, j: Int): Float = when {
            candidate[j] == '\'' -> MISSED_APOSTROPHE
            j > 0 && candidate[j - 1] == candidate[j] -> MISSED_DOUBLE
            else -> MISSED_KEY
        }

        /**
         * Weighted edit distance from what was typed to [candidate]: near-miss substitutions,
         * double taps and apostrophes are cheap. Stops early once every path costs more than [limit],
         * allowing for a swap, which reaches back two rows and skips the one in between.
         */
        fun typingCost(
            typed: String,
            candidate: String,
            touches: List<Touch?>? = null,
            limit: Float = Float.MAX_VALUE,
            touchOffsets: TouchOffsets? = null,
            language: LanguageProfile = LanguageProfile.ENGLISH,
        ): Float = cost(typed, candidate, compensatedTouches(typed, touches?.takeIf { it.size == typed.length }, touchOffsets), limit, keysFor(language))

        /**
         * [typingCost] with the touches already calibrated and the language's keys already looked up.
         * Everything that depends on one letter (its key's center, its bare form, an extra or missed
         * key) is worked out once per letter, so each cell of the table is arithmetic; the costs are
         * the ones [substitution], [extraKey] and [missedKey] give.
         */
        private fun cost(typed: String, candidate: String, calibrated: List<Touch?>?, limit: Float, keys: Keys): Float {
            val n = typed.length
            val m = candidate.length
            if (kotlin.math.abs(n - m) * MIN_INSERT_OR_DELETE > limit) return Float.MAX_VALUE
            val s = scratch.get()!!.fit(n + 1, m + 1)
            val language = keys.language
            for (i in 0 until n) {
                val c = typed[i]
                s.extra[i] = extraKey(typed, i, keys)
                s.typedBare[i] = language.fold(c)
                val from = calibrated?.getOrNull(i) ?: keys.geometry.center(keys.keyOf(c))
                s.fromKnown[i] = from != null
                if (from != null) { s.fromX[i] = from.x; s.fromY[i] = from.y }
            }
            for (j in 0 until m) {
                val c = candidate[j]
                s.missed[j] = missedKey(candidate, j)
                s.candidateBare[j] = language.fold(c)
                val target = keys.keyOf(c)
                val center = keys.geometry.center(target)
                s.toKnown[j] = center != null
                if (center != null) { s.toX[j] = center.x; s.toY[j] = center.y }
                s.toAccent[j] = if (target != c) ACCENT else 0f
            }
            val d = s.table
            d[0][0] = 0f
            for (j in 1..m) d[0][j] = d[0][j - 1] + s.missed[j - 1]
            var previousRowMin = 0f
            for (i in 1..n) {
                val extra = s.extra[i - 1]
                d[i][0] = d[i - 1][0] + extra
                var rowMin = d[i][0]
                for (j in 1..m) {
                    val substitute = when {
                        typed[i - 1] == candidate[j - 1] -> 0f
                        s.typedBare[i - 1] == s.candidateBare[j - 1] -> ACCENT
                        !s.fromKnown[i - 1] || !s.toKnown[j - 1] -> ONE_EDIT_COST
                        else -> slip(s.fromX[i - 1] - s.toX[j - 1], s.fromY[i - 1] - s.toY[j - 1]) + s.toAccent[j - 1]
                    }
                    var value = minOf(d[i - 1][j] + extra, d[i][j - 1] + s.missed[j - 1], d[i - 1][j - 1] + substitute)
                    if (i > 1 && j > 1 && typed[i - 1] == candidate[j - 2] && typed[i - 2] == candidate[j - 1] && typed[i - 1] != typed[i - 2]) {
                        value = minOf(value, d[i - 2][j - 2] + SWAP)
                    }
                    d[i][j] = value
                    if (value < rowMin) rowMin = value
                }
                if (rowMin > limit && previousRowMin + SWAP > limit) return Float.MAX_VALUE
                previousRowMin = rowMin
            }
            return d[n][m]
        }

        /** Only measured lifts are calibrated; a missing touch still means the typed key's center. */
        private fun compensatedTouches(typed: String, touches: List<Touch?>?, offsets: TouchOffsets?): List<Touch?>? {
            if (offsets == null || touches == null) return touches
            return touches.mapIndexed { i, touch -> touch?.let { offsets.correctedTouch(typed[i], it) } }
        }

        /** Reusable working arrays; scanning thousands of words allocates nothing. */
        private class Scratch {
            var table = Array(24) { FloatArray(24) }
            var extra = FloatArray(24)
            var typedBare = CharArray(24)
            var fromKnown = BooleanArray(24)
            var fromX = FloatArray(24)
            var fromY = FloatArray(24)
            var missed = FloatArray(24)
            var candidateBare = CharArray(24)
            var toKnown = BooleanArray(24)
            var toX = FloatArray(24)
            var toY = FloatArray(24)
            var toAccent = FloatArray(24)

            /** Grows to hold a cost table of at least [rows] × [columns]. */
            fun fit(rows: Int, columns: Int): Scratch {
                if (table.size < rows || table[0].size < columns) table = Array(maxOf(rows, table.size)) { FloatArray(maxOf(columns, table[0].size)) }
                val size = maxOf(rows, columns)
                if (extra.size < size) {
                    extra = FloatArray(size); typedBare = CharArray(size); fromKnown = BooleanArray(size); fromX = FloatArray(size); fromY = FloatArray(size)
                    missed = FloatArray(size); candidateBare = CharArray(size); toKnown = BooleanArray(size); toX = FloatArray(size); toY = FloatArray(size)
                    toAccent = FloatArray(size)
                }
                return this
            }
        }

        private val scratch = ThreadLocalValue { Scratch() }

        /** A slip [dx] key widths across and [dy] rows down from the intended key's center. */
        private fun slip(dx: Float, dy: Float): Float = minOf(SUBSTITUTE_BASE + SUBSTITUTE_PER_SQUARED_KEY * (dx * dx + dy * dy), SUBSTITUTE_MAX)

        /** True when two letter keys touch, including the rows above and below. */
        fun areNeighbors(a: Char, b: Char, language: LanguageProfile = LanguageProfile.ENGLISH): Boolean =
            keysFor(language).areNeighbors(a, b)

        /** The key beside is 1.0 away, the keys above and below 1.1 to 1.4; two keys over is 2.0. */
        private const val NEIGHBOR_DISTANCE = 1.5f

        private val keysByLanguage = HashMap<LanguageProfile, Keys>()
        private val keysLock = EngineLock()

        private val englishKeys by lazy { Keys(LanguageProfile.ENGLISH) }

        private fun keysFor(language: LanguageProfile): Keys =
            if (language === LanguageProfile.ENGLISH) englishKeys else keysLock.withLock { keysByLanguage.getOrPut(language) { Keys(language) } }
    }

    /**
     * One language's letters as the cost model uses them: a bit each for the letter masks, which keys
     * touch, and where the keys are. A held letter ("é") sits on its key ("e") for both.
     */
    private class Keys(val language: LanguageProfile) {
        val letters: CharArray = language.letters
        val geometry: LetterGeometry = if (language === LanguageProfile.ENGLISH) LetterGeometry.QWERTY else language.geometry
        val folds: Boolean = language.folds.isNotEmpty()
        private val slot = IntArray(letters.maxOf { it.code } + 1) { -1 }.also { slots ->
            require(letters.size <= Long.SIZE_BITS) { "${language.code}: more than ${Long.SIZE_BITS} letters" }
            letters.forEachIndexed { i, c -> slots[c.code] = i }
        }
        private val neighbors: Array<BooleanArray> = Array(letters.size) { i ->
            BooleanArray(letters.size) { j ->
                (geometry.distance(keyOf(letters[i]), keyOf(letters[j])) ?: Float.MAX_VALUE) <= NEIGHBOR_DISTANCE
            }
        }

        private fun slot(c: Char): Int = if (c.code < slot.size) slot[c.code] else -1

        /** The key that types [c]: its own, or the key whose hold offers it. */
        fun keyOf(c: Char): Char = if (geometry.center(c) != null) c else language.folds[c] ?: c

        /** This letter's bit, or 0 for anything that isn't one of the language's letters. */
        fun bit(c: Char): Long = slot(c).let { if (it < 0) 0L else 1L shl it }

        fun mask(word: String): Long = word.fold(0L) { mask, c -> mask or bit(c) }

        fun areNeighbors(a: Char, b: Char): Boolean {
            val i = slot(a)
            val j = slot(b)
            return i >= 0 && j >= 0 && neighbors[i][j]
        }
    }
}
