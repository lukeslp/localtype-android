package dev.localtype.keyboard

/** Combines the neural order with one established personal follower, then the ordinary fallback. */
object NeuralRanking {
    const val PERSONAL_POLICY_ID = "personal-learning-v2-context3-slots1-once2"
    val PERSONAL_POLICY = PersonalRanking.Policy(maxContext = 3, reservedSlots = 1)
    data class Choice(val word: String, val neural: Boolean, val personalSource: PersonalRanking.Source? = null)

    /**
     * Neural words lead. The strongest qualifying personal follower ([PersonalFollower.qualifies]:
     * one deliberate choice, one use behind two or more own words, or two uses behind one) gets the
     * last available word slot if it is absent from the neural top three. An explicitly added
     * spelling matching a nonempty prefix takes that reservation first, without invented observations.
     * [limit] is the number
     * of word slots left after the strip has reserved any correction or emoji positions.
     * Capitalization is applied by the strip after this lowercase ranking, as for n-gram words.
     */
    fun rank(
        neural: List<String>,
        fallback: List<String>,
        learned: List<Pair<String, Int>>,
        prefix: String,
        blocked: Set<String>,
        limit: Int = 3,
        added: List<String> = emptyList(),
        shared: List<String> = emptyList(),
        followers: List<PersonalFollower> = emptyList(),
        personalPolicy: PersonalRanking.Policy = PERSONAL_POLICY,
    ): List<String> = rankWithSources(neural, fallback, learned, prefix, blocked, limit, added, shared,
        followers, personalPolicy).map { it.word }

    /** The selected words with their actual source, including a reserved personal slot. */
    fun rankWithSources(
        neural: List<String>,
        fallback: List<String>,
        learned: List<Pair<String, Int>>,
        prefix: String,
        blocked: Set<String>,
        limit: Int = 3,
        added: List<String> = emptyList(),
        shared: List<String> = emptyList(),
        followers: List<PersonalFollower> = emptyList(),
        personalPolicy: PersonalRanking.Policy = PERSONAL_POLICY,
        /** The language typed: its one-letter words and its own exclusions. English by default. */
        language: LanguageProfile = LanguageProfile.ENGLISH,
    ): List<Choice> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val neuralWords = eligibleWords(neural, prefix, blocked, language)
        val neuralTopThree = neuralWords.take(3).toSet()
        val eligibleLearned = eligibleWords(learned.map { it.first }, prefix, blocked, language).toSet()
        val explicit = if (prefix.isNotEmpty()) eligibleWords(added, prefix, blocked, language).firstOrNull { it !in neuralTopThree } else null
        val personal = explicit ?: learned.asSequence()
            .map { AdaptiveNgramModel.normalize(it.first) to it.second }
            .filter { (word, count) -> count >= 2 && word in eligibleLearned && word !in neuralTopThree }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .firstOrNull()?.first
        val reserved = personal ?: if (prefix.isNotEmpty()) eligibleWords(shared, prefix, blocked, language).firstOrNull { it !in neuralTopThree } else null
        val ranked = (neuralWords.map { Choice(it, true) } +
            eligibleWords(fallback, prefix, blocked, language).map { Choice(it, false) })
            .distinctBy { it.word }.take(limit).toMutableList()
        val eligibleFollowers = followers.filter { it.contextLength in 1..personalPolicy.maxContext &&
            PersonalFollower.qualifies(it.observations, it.explicitObservations, it.contextLength) &&
            eligibleWords(listOf(it.word), prefix, blocked, language).isNotEmpty() }
        if (eligibleFollowers.isNotEmpty() && explicit == null) {
            // Explicit added-prefix reservation keeps precedence. Merge evidence without summing it.
            val overlay = PersonalRanking.rank(
                ranked.map { it.word }, eligibleFollowers,
                learned.map { PersonalRanking.LegacyFollower(it.first, it.second) },
                prefix, minOf(limit, 3), personalPolicy, language.oneLetterWords,
            ) { eligibleWords(listOf(it), prefix, blocked, language).isNotEmpty() }
            return overlay.map { candidate ->
                if (candidate.source == PersonalRanking.Source.NEURAL)
                    ranked.first { it.word == candidate.word }
                else Choice(candidate.word, false, candidate.source)
            }
        }
        if (reserved != null) {
            val existing = ranked.indexOfFirst { it.word == reserved }
            if (existing >= 0) {
                ranked[existing] = Choice(reserved, false)
            } else {
                if (ranked.size == limit) ranked.removeAt(ranked.lastIndex)
                ranked.add(Choice(reserved, false))
            }
        }
        return ranked
    }

    /** The same normalization and filters used in [rank], also for the strip's source labels. */
    fun eligibleWords(source: List<String>, prefix: String, blocked: Set<String>, language: LanguageProfile = LanguageProfile.ENGLISH): List<String> {
        val normalizedPrefix = AdaptiveNgramModel.normalize(prefix)
        val forbidden = blocked.mapTo(HashSet()) { AdaptiveNgramModel.normalize(it) }
        return source.asSequence().map(AdaptiveNgramModel::normalize)
            .filter { CifgModel.isOfferable(it, language.oneLetterWords) && it.startsWith(normalizedPrefix) && it !in forbidden }
            .filterNot { SuggestionWordRules.isExcluded(it, language) }.distinct().toList()
    }
}
