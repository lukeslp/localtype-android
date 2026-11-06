package dev.localtype.keyboard


/** Captured spelling evidence. Cost uses calibrated lifts; centerCost records the no-touch control. */
data class CorrectionCandidate(
    val word: String,
    val cost: Float,
    val centerCost: Float,
    val touchCount: Int,
    val frequency: Float,
    val learnedContext: Float,
    val baselineScore: Float,
    val lengthAllowed: Boolean,
    val rareTarget: Boolean,
)

/** Immutable owner-thread snapshot; unchanged is an explicit outcome, outside the 16 alternatives. */
class CorrectionSnapshot constructor(
    val typed: String,
    val normalized: String,
    candidates: List<CorrectionCandidate>,
    val legacy: Corrector.Correction?,
    val autoBudget: Float,
    val contextualEligible: Boolean,
    val guardReason: String?,
) {
    val candidates: List<CorrectionCandidate> = frozenList(ArrayList(candidates))
    val unchanged = CorrectionOutcome(typed, false)
    init {
        require(candidates.size <= MAX_CANDIDATES)
        require(candidates.map { it.word }.distinct().size == candidates.size)
    }
    companion object { const val MAX_CANDIDATES = 16 }
}

data class CorrectionOutcome(val word: String, val changed: Boolean)

/** Frozen model evidence. Absent/OOV/nonfinite evidence has zero contribution. No owner callbacks. */
class CorrectionContext private constructor(
    prior: Map<String, Float>, neural: Map<String, Float>,
) {
    private val prior = frozenMap(HashMap(prior))
    private val neural = frozenMap(HashMap(neural))
    fun priorScore(word: String): Float? = prior[word]
    fun neuralScore(word: String): Float? = neural[word]

    /** Positive relative evidence in score units, capped separately for each source. */
    fun contribution(word: String, cap: Float, neural: Boolean): Float {
        if (cap == 0f) return 0f
        val scores = if (neural) this.neural else prior
        val value = scores[word] ?: return 0f
        val floor = scores.values.minOrNull() ?: return 0f
        return (value - floor).coerceIn(0f, cap)
    }

    companion object {
        val EMPTY = CorrectionContext(emptyMap(), emptyMap())

        /**
         * Separate corpus-prior correction adapter: reads existing followers once, never changes
         * the asset, next-word weights, learned counts or snapshot tables. Only pool words survive.
         * [previous] uses AdaptiveNgramModel.START at a sentence boundary, null for unavailable context.
         */
        fun capture(
            snapshot: CorrectionSnapshot, prior: CorpusPrior? = null, previous: String? = null,
            neuralScores: List<CifgModel.Candidate> = emptyList(),
        ): CorrectionContext {
            val allowed = snapshot.candidates.map { it.word }.toSet()
            val p = if (prior != null && previous != null) prior.followers(previous)
                .filter { it.word in allowed && it.logProbability.isFinite() }
                .associate { it.word to it.logProbability } else emptyMap()
            val n = neuralScores.filter { it.word in allowed && it.logit.isFinite() }
                .associate { it.word to it.logit }
            return CorrectionContext(p, n)
        }
    }
}
