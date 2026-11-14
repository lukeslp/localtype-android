package dev.localtype.keyboard

/** Combines the neural order with one established personal follower, then the ordinary fallback. */
object NeuralRanking {
    const val PERSONAL_POLICY_ID = "personal-learning-v3-context3-slots1-once2-backed"
    val PERSONAL_POLICY = PersonalRanking.Policy(maxContext = 3, reservedSlots = 1)
    data class Choice(val word: String, val neural: Boolean, val personalSource: PersonalRanking.Source? = null)

    /**
     * Neural words lead. The strongest qualifying personal follower ([PersonalFollower.qualifies]:
     * one deliberate choice, one use behind two or more own words, or two uses behind one) gets the
     * last available word slot if it is absent from the neural top three. From v3 ([backed]) a follower
     * of one own word alone that is one of the commonest words, typed and never chosen from the strip, takes
     * the next-word slot (no letter typed yet) only when the neural list holds the word somewhere: "one" typed twice after "the" no longer sits in the last slot after every
     * "the". Followers of two or three own words, a follower the person tapped, and everything when no
     * neural answer came are as before. An explicitly added
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
        backed: Boolean = true,
        common: (String) -> Boolean = { false },
    ): List<String> = rankWithSources(neural, fallback, learned, prefix, blocked, limit, added, shared,
        followers, personalPolicy, backed = backed, common = common).map { it.word }

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
        /**
         * Whether a typed follower of one own word needs the neural list's backing (the rule from v3). False is the
         * v2 rule, kept so the two can be scored side by side (CorpusStripRanker).
         */
        backed: Boolean = true,
        /**
         * Whether a word is one of the language's commonest ([COMMON_SCORE] in the dictionary). Only such a follower
         * needs backing: "one" after "the" is not a habit, "elephant" after "purple" is. Nothing is common by default.
         */
        common: (String) -> Boolean = { false },
    ): List<Choice> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val neuralWords = eligibleWords(neural, prefix, blocked, language)
        val neuralTopThree = neuralWords.take(3).toSet()
        // Only the next-word slot: once a letter is typed, the person's own word that starts with it is a completion,
        // and the neural list for a prefix is too short to say anything about a name it has never seen.
        val backing = backed && AdaptiveNgramModel.normalize(prefix).isEmpty()
        @Suppress("NAME_SHADOWING") val learned = if (backing) backedLearned(learned, neuralWords, common) else learned
        @Suppress("NAME_SHADOWING") val followers = if (backing) backedFollowers(followers, neuralWords, common) else followers
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

    /**
     * The n-gram model's learned followers of the last own word that the neural list also holds. Those pairs are
     * one-word evidence by construction. With no neural answer (Chime off, still loading, or a language without it)
     * every pair stays, since nothing is there to back or crowd out.
     */
    fun backedLearned(learned: List<Pair<String, Int>>, neuralWords: List<String>, common: (String) -> Boolean): List<Pair<String, Int>> {
        if (neuralWords.isEmpty()) return learned
        val held = neuralWords.toSet()
        return learned.filter { AdaptiveNgramModel.normalize(it.first).let { word -> word in held || !common(word) } }
    }

    /** The same for the suffix state's followers: two or three own words, or an explicit choice, need no backing. */
    fun backedFollowers(followers: List<PersonalFollower>, neuralWords: List<String>, common: (String) -> Boolean): List<PersonalFollower> {
        if (neuralWords.isEmpty()) return followers
        val held = neuralWords.toSet()
        return followers.filter { f ->
            f.contextLength >= 2 || f.explicitObservations > 0 || AdaptiveNgramModel.normalize(f.word).let { it in held || !common(it) }
        }
    }

    /** A dictionary score from which a word counts as common here: about the 200 commonest English words ("guy" is 76). */
    const val COMMON_SCORE = 76

    /** The same normalization and filters used in [rank], also for the strip's source labels. */
    fun eligibleWords(source: List<String>, prefix: String, blocked: Set<String>, language: LanguageProfile = LanguageProfile.ENGLISH): List<String> {
        val normalizedPrefix = AdaptiveNgramModel.normalize(prefix)
        val forbidden = blocked.mapTo(HashSet()) { AdaptiveNgramModel.normalize(it) }
        return source.asSequence().map(AdaptiveNgramModel::normalize)
            .filter { CifgModel.isOfferable(it, language.oneLetterWords) && it.startsWith(normalizedPrefix) && it !in forbidden }
            .filterNot { SuggestionWordRules.isExcluded(it, language) }.distinct().toList()
    }
}
