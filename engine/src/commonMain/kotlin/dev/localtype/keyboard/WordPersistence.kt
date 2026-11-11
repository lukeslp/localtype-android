package dev.localtype.keyboard

/** Persistence follows verified keyboard events, never ownership inferred from editor text. */
object WordPersistence {
    // Ranking and its slot allocation are unchanged; append must still name the new learning contract.
    const val LOG_POLICY_ID = NeuralRanking.PERSONAL_POLICY_ID + "-owned-context-v1"

    fun verified(allowed: Boolean, verified: Boolean): Boolean = allowed && verified

    /** A first word can learn; an existing previous word must be the verified own completion here. */
    fun pair(allowed: Boolean, verified: Boolean, previous: String?, ownPrevious: String?): Boolean =
        WordPersistence.verified(allowed, verified) && (previous == null ||
            (ownPrevious != null && AdaptiveNgramModel.normalize(previous) == AdaptiveNgramModel.normalize(ownPrevious)))

    /** Learning prose in a URI field does not turn on that field's autocorrection or logging. */
    fun field(allowed: Boolean, suggestions: Boolean, requestedSuggestions: Boolean, addressBar: Boolean): Boolean =
        allowed && suggestions && (requestedSuggestions || addressBar)

    /**
     * The entire model input must be the known own completion tail, not just its shorter cache key.
     * More than the three remembered words, punctuation, prefilled text and speculative lookahead
     * conservatively serve from memory. This never stores another copy of pasted text.
     */
    fun modelContext(context: String, owned: List<String>, allowed: Boolean): Boolean {
        if (!allowed) return false
        val words = context.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > owned.size || words.any { !WordRule.STORED.matches(it) }) return false
        return words.map(AdaptiveNgramModel::normalize) == owned.takeLast(words.size).map(AdaptiveNgramModel::normalize)
    }

    /** A later ownership reset can only withdraw permission, never upgrade an unsafe request. */
    data class Request(val mayPersist: Boolean, val generation: Long) {
        fun persists(currentGeneration: Long): Boolean = mayPersist && generation == currentGeneration
    }
}
