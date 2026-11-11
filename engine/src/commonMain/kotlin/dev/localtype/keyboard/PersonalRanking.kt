package dev.localtype.keyboard

/** Pure strip allocation. Caller supplies eligible own context, privacy gates and all word filters. */
object PersonalRanking {
    enum class Source { NEURAL, PERSONAL, LEGACY }
    data class Candidate(val word: String, val source: Source)
    data class LegacyFollower(val word: String, val observations: Int, val lastObserved: Long = 0)
    data class Policy(val maxContext: Int = 1, val reservedSlots: Int = 1) {
        init { require(maxContext == 1 || maxContext == 3); require(reservedSlots in 1..2) }
    }
    private data class Evidence(val word: String, val count: Int, val recent: Long, val source: Source)

    /**
     * followers should come from state.followers(ownContext, policy.maxContext). Legacy evidence
     * belongs to the same verified last-word context. Duplicate sources use strongest evidence,
     * never summed counts or invented explicit intent. Neural order is preserved.
     */
    fun rank(
        neural: List<String>, followers: List<PersonalFollower>, legacy: List<LegacyFollower>,
        prefix: String = "", wordSlots: Int = 3, policy: Policy = Policy(),
        oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords,
        allowed: (String) -> Boolean,
    ): List<Candidate> {
        require(wordSlots in 0..3)
        val normalizedPrefix = AdaptiveNgramModel.normalize(prefix)
        fun normalized(raw: String): String? {
            val word = AdaptiveNgramModel.normalize(raw)
            return word.takeIf { it.length in 1..128 && it.encodeToByteArray().size <= 512 &&
                WordRule.STORED.matches(it) &&
                (it.length >= 2 || it in oneLetterWords) &&
                it.startsWith(normalizedPrefix) && allowed(it) }
        }
        val base = neural.mapNotNull(::normalized).distinct().take(wordSlots)
        val evidence = followers.filter {
            it.contextLength in 1..policy.maxContext &&
                PersonalFollower.qualifies(it.observations, it.explicitObservations, it.contextLength)
        }.mapNotNull { f -> normalized(f.word)?.let {
            Evidence(it, f.observations.coerceAtMost(100), f.lastObserved, Source.PERSONAL)
        } } + legacy.filter { it.observations >= 2 }.mapNotNull { f -> normalized(f.word)?.let {
            Evidence(it, f.observations.coerceAtMost(100), f.lastObserved, Source.LEGACY)
        } }
        val order = compareByDescending<Evidence> { it.count }.thenByDescending { it.recent }
            .thenBy { it.word }.thenBy { it.source.ordinal }
        val merged = evidence.sortedWith(order).distinctBy { it.word }
        // Already displayed personal words use the allowance and retain their neural identity.
        val limit = if (wordSlots == 3) policy.reservedSlots else minOf(1, wordSlots)
        val displayedPersonal = merged.count { it.word in base }
        val additions = merged.filter { it.word !in base }.take((limit - displayedPersonal).coerceAtLeast(0))
        val retained = base.toMutableList()
        val personalWords = merged.map { it.word }.toSet()
        while (retained.size > wordSlots - additions.size) {
            // Preserve personal matches already visible and the leading useful neural word.
            val removable = retained.indexOfLast { it !in personalWords }
            if (removable < 0) break
            retained.removeAt(removable)
        }
        return retained.map { Candidate(it, Source.NEURAL) } +
            additions.map { Candidate(it.word, it.source) }
    }
}
