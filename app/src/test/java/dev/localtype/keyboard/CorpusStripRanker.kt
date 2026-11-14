package dev.localtype.keyboard

import java.io.File

/**
 * The strip a person sees with Chime on, scored on corpus text while it learns from that text.
 *
 * The other corpus rankers are one model asked cold. The keyboard's three words come from more than that:
 * [NeuralRanking.rankWithSources] takes Chime's twelve, the n-gram model's list as the fallback, and the person's
 * own followers of the words just typed, and gives the last slot to one of those followers when it isn't already
 * among Chime's first three. Nothing else scores that merge, and the one comparison with another keyboard was lost
 * in that last slot (docs/next-word-quality-2026-11-12.md). This ranker runs the same call the service makes, and
 * learns each word after its prefixes are scored, as the keyboard learns a word at its space: the n-gram model through
 * [AdaptiveNgramModel.learnTypedWord], the suffix state through [PersonalLearningState.record] with the sentence's
 * own words, three at most.
 *
 * What it can't be: one person. A column's lines come from many writers, typed here as if by one, in file order.
 * It never taps a suggestion, so no follower is an explicit choice. It has no corrections, emoji or added words.
 * [policy] decides which personal evidence reaches the merge; [SHIPPED] passes all of it, as the service does.
 */
class CorpusStripRanker(
    override val name: String,
    override val description: String,
    private val policy: Policy,
    /** A dictionary score: pass everything and let [NeuralRanking]'s own rule decide, with words at or above it common. Null leaves that rule off. */
    private val engine: Int? = null,
) : CorpusRanker {
    /** What of the person's evidence the merge is given for one strip. */
    fun interface Policy {
        fun evidence(strip: Strip): Evidence
    }

    class Strip(
        /** Chime's twelve for this context and prefix, best first. */
        val neural: List<String>,
        /** The suffix state's followers for the own words before the caret: all of one context length, the longest that has any. */
        val followers: List<PersonalFollower>,
        /** The n-gram model's learned followers of the last own word, with counts. */
        val learned: List<Pair<String, Int>>,
        /** How many words the person has typed after that last own word. */
        val ownCountAfter: Int,
    )

    class Evidence(val followers: List<PersonalFollower>, val learned: List<Pair<String, Int>>)

    private val chime by lazy { File("src/main/assets/${NeuralAsset.FILE_NAME}").inputStream().use { CifgModel.load(it) } }
    private val session by lazy { chime.newSession() }
    private val model = AdaptiveNgramModel(dictionary = CorpusDeviceRanker.shippedDictionary, prior = CorpusDeviceRanker.bundledPrior)
    private val state = PersonalLearningState()
    private val own = ArrayList<String>()

    override val vocabulary: Set<String> by lazy {
        (0 until chime.vocabSize).map(chime::token).filterTo(HashSet()) { CifgModel.isOfferable(it) } +
            model.dictionary.allWords().filter { CorpusText.offerable(it, LanguageProfile.ENGLISH.oneLetterWords) }
    }

    override fun rank(context: String, prefix: String, limit: Int): List<String> {
        if (context.isBlank()) own.clear()
        val before = context + prefix
        val neural = session.predict(before, NEURAL_WORDS).map { it.word }
        val fallback = model.predict(before, FALLBACK_WORDS)
        val last = own.lastOrNull()
        val strip = Strip(
            neural,
            if (last == null) emptyList() else state.followers(own, NeuralRanking.PERSONAL_POLICY.maxContext),
            if (last == null) emptyList() else model.learnedFollowers(last),
            if (last == null) 0 else model.ownCountAfter(last),
        )
        val evidence = policy.evidence(strip)
        // The strip has three word slots whatever the scorer asks for; the next-word question's slots four to six stay empty.
        // The policies here choose the evidence themselves, so the engine's own v3 filter is off unless [engine] asks for it.
        return NeuralRanking.rank(neural, fallback, evidence.learned, prefix, emptySet(), SLOTS, followers = evidence.followers, backed = engine != null,
            common = { engine != null && model.dictionary.score(it) >= engine })
    }

    override fun typed(context: String, word: String) {
        if (context.isBlank()) own.clear()
        if (!model.learnTypedWord(context + word, persist = false)) {
            // An unknown word is typed but not learned, and the own context does not run through it.
            own.clear()
            return
        }
        if (own.isNotEmpty()) state.record(own.toList(), word, LearningOrigin.TYPED)
        own += AdaptiveNgramModel.normalize(word)
        if (own.size > MAX_OWN) own.removeAt(0)
    }

    companion object {
        /** What NeuralPredictionWorker asks Chime for, and what refreshCandidates asks the n-gram model for. */
        const val NEURAL_WORDS = 12
        const val FALLBACK_WORDS = 6
        const val SLOTS = 3
        const val MAX_OWN = 3

        /** Nothing personal reaches the merge: Chime's three, filled from the n-gram list. */
        val NONE = Policy { Evidence(emptyList(), emptyList()) }

        /** Everything, as the service passes it. */
        val SHIPPED = Policy { Evidence(it.followers, it.learned) }

        /** Only followers of two or three own words. A follower of one word alone never takes the slot. */
        val LONGER_CONTEXT = Policy { Evidence(it.followers.filter { f -> f.contextLength >= 2 }, emptyList()) }

        /** A follower of one word alone takes the slot only when Chime ranks it in its twelve. */
        val IN_CHIMES_TWELVE = Policy { strip ->
            val twelve = strip.neural.toSet()
            Evidence(strip.followers.filter { it.contextLength >= 2 || it.word in twelve }, strip.learned.filter { it.first in twelve })
        }

        /**
         * A follower of one word alone takes the slot only when it is at least [share] of everything typed after that
         * word: "one" twice after a hundred uses of "the" is not a habit, "regards" twice after three "kind" is.
         */
        fun share(share: Double) = Policy { strip ->
            fun habit(count: Int) = strip.ownCountAfter > 0 && count >= share * strip.ownCountAfter
            Evidence(strip.followers.filter { it.contextLength >= 2 || habit(it.observations) }, strip.learned.filter { habit(it.second) })
        }

        /** The policies compared on dev on 2026-11-12, written before any was scored. */
        fun candidates(): List<CorpusStripRanker> = listOf(
            CorpusStripRanker("strip-none", "Chime's three, n-gram fill, nothing personal", NONE),
            CorpusStripRanker("strip-shipped", "the merge as the service calls it: every qualifying follower may take the last slot", SHIPPED),
            CorpusStripRanker("strip-longer", "only followers of two or three own words take the slot", LONGER_CONTEXT),
            CorpusStripRanker("strip-in12", "a one-word follower takes the slot only from Chime's twelve", IN_CHIMES_TWELVE),
            // Added after the first scoring, before the second (the doc's amendment): the same rule for common words only,
            // through the engine's own code, at the next-word slot only and never for a follower chosen from the strip.
            CorpusStripRanker("strip-common200", "NeuralRanking's rule: a typed one-word follower among about the 200 commonest words needs Chime's twelve", SHIPPED, engine = 76),
            CorpusStripRanker("strip-common1000", "the same for about the 1,000 commonest words", SHIPPED, engine = 63),
            CorpusStripRanker("strip-commonall", "the same for every word: strip-in12 at the next-word slot only", SHIPPED, engine = 0),
            CorpusStripRanker("strip-share10", "a one-word follower takes the slot only at 10% of what followed that word", share(0.10)),
            CorpusStripRanker("strip-share25", "a one-word follower takes the slot only at 25% of what followed that word", share(0.25)),
        )
    }
}
