package dev.localtype.keyboard

/** Worker-only, bounded missed-space search. Every result is an explicit offer, never an automatic edit. */
class MultiwordCorrection(private val dictionary: WordDictionary) {
    /**
     * [exact] means both words were typed as they are spelled; [separatorLetter] is the bottom-row letter that
     * stood where the space belongs ("ofbthe"), or null when the space was simply left out.
     */
    data class Offer(
        val text: String, val unusualPairs: Set<String>, val separatorLetter: Char?,
        val exact: Boolean = false, val knownPair: Boolean = false,
    ) {
        /**
         * One slip explains it: a letter beside the space bar typed in place of the space, between two words of
         * at least two letters each, both spelled as typed, that the corpus prior has seen together ([knownPair]).
         * That beats the corrector's single-word guess, which would need several edits ("ofbthe" is "of the",
         * not "other"). Leaving a space out with nothing in its place is not enough ("goin" may be "going"),
         * nor a one-letter word ("inot" may be "into"), nor a pair the prior doesn't know ("batbbet" is not
         * "bat bet": the list has many short rare entries).
         */
        val oneSlip: Boolean get() = exact && knownPair && separatorLetter != null && text.split(' ').all { it.length >= 2 }
    }

    /**
     * Every pair of neighboring letters in the dictionary's words, kept without a String per pair: making one for each
     * of English's 114k pairs, all at once, to find its 562 distinct ones cost the iPhone keyboard extension about
     * 6 MB at its first space.
     */
    private val asciiPairs = BooleanArray(ASCII * ASCII)
    private val otherPairs = HashSet<Int>()

    init {
        for (word in dictionary.allWords()) for (i in 1 until word.length) remember(word[i - 1], word[i])
    }

    /** A rare pair is a search hint, not proof of a typo. Protected/real spellings are checked by the caller. */
    fun unusualPairs(word: String): Set<String> =
        (1 until word.length).filterNot { known(word[it - 1], word[it]) }.mapTo(HashSet()) { word.substring(it - 1, it + 1) }

    private fun remember(a: Char, b: Char) {
        if (a.code < ASCII && b.code < ASCII) asciiPairs[a.code * ASCII + b.code] = true else otherPairs.add((a.code shl 16) or b.code)
    }

    private fun known(a: Char, b: Char): Boolean =
        if (a.code < ASCII && b.code < ASCII) asciiPairs[a.code * ASCII + b.code] else ((a.code shl 16) or b.code) in otherPairs

    /** [exactOnly] skips the spelling repairs, for when only a [Offer.oneSlip] split could be used. */
    fun suggest(
        raw: String, protected: Boolean, context: (String, String) -> Float? = { _, _ -> null },
        blocked: (String) -> Boolean = { false }, exactOnly: Boolean = false, repair: (String) -> List<String>,
    ): Offer? {
        // Keep acronyms, mixed case, identifiers and likely names out of this initial experiment.
        if (protected || raw.length !in 5..24 || raw.any { it !in 'a'..'z' }) return null
        if (strong(raw)) return null
        data class Candidate(val text: String, val score: Float, val separator: Char?, val exact: Boolean, val knownPair: Boolean)
        val candidates = ArrayList<Candidate>()
        var repairs = 0
        fun allowed(word: String) = !blocked(word) && !SuggestionWordRules.isExcluded(word)
        fun resolve(part: String): List<Pair<String, Boolean>> {
            if (strong(part)) {
                // Listed contractions can be real dictionary entries too.
                val contraction = Contractions.propose(part, null, false)?.takeIf { it.automatic }?.word
                val word = contraction ?: part
                return if (allowed(word)) listOf(word to (contraction != null)) else emptyList()
            }
            if (exactOnly || part.length < 3 || repairs >= 8) return emptyList()
            repairs++
            return repair(part).take(3).distinct().filter { strong(it) && allowed(it) }.map { it to true }
        }
        for (cut in 1 until raw.length) {
            for (skip in 0..1) {
                if (skip == 1 && raw[cut] !in "zxcvbnm") continue
                val left = raw.substring(0, cut)
                val right = raw.substring(cut + skip)
                if (right.isEmpty() || (!strong(left) && !strong(right))) continue
                val leftChoices = resolve(left)
                val rightChoices = resolve(right)
                for (a in leftChoices) for (b in rightChoices) {
                    val changes = (if (a.second) 1 else 0) + (if (b.second) 1 else 0)
                    // Corpus evidence only ranks explicit offers; never treat it as user intent.
                    val evidence = context(a.first, b.first)?.takeIf { it.isFinite() }
                    val contextual = (evidence ?: -12f).coerceIn(-12f, 0f) * 8f
                    val score = dictionary.score(a.first) + dictionary.score(b.first) - changes * 40 - skip * 20 + contextual
                    // Exact splits cost no repair calls, so every one is kept; repaired ones stay capped.
                    if (changes == 0 || candidates.count { !it.exact } < 16) {
                        candidates += Candidate("${a.first} ${b.first}", score, raw[cut].takeIf { skip == 1 }, changes == 0, evidence != null)
                    }
                }
            }
        }
        // Words spelled as typed explain the slip with no second typo, so they win over repaired ones.
        // Duplicates keep their best score: the same phrase can come from a repair and from an exact cut.
        val pool = candidates.filter { it.exact }.ifEmpty { candidates }
        val winner = pool.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.text }) ?: return null
        return Offer(winner.text, unusualPairs(raw), winner.separator, winner.exact, winner.knownPair)
    }

    private fun strong(word: String) = dictionary.score(word) >= 40

    companion object {
        /**
         * Whether the missed-space [split] is the offer after a word. It is when the corrector found no single
         * word ([single] is null), and over a single-word offer only when one slip explains it. Never call this
         * for an automatic correction: those stay the corrector's.
         */
        fun preferred(split: Offer?, single: String?): Boolean = split != null && (single == null || split.oneSlip)

        private const val ASCII = 128
    }
}
