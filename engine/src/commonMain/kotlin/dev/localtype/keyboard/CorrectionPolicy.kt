package dev.localtype.keyboard

import kotlin.math.sqrt

/** Versioned, pure policy. Production defaults retain legacy automatic replacements. */
data class CorrectionPolicy(
    val priorCap: Float = 0f,
    val neuralCap: Float = 0f,
    val winnerMargin: Float = 6f,
    val extraBudget: Float = 0f,
    val contextualAutomatic: Boolean = false,
    val reservedApplications: Int = 0,
    val reservedCorrect: Int = 0,
) {
    init {
        require(priorCap in CAPS && neuralCap in CAPS)
        require(winnerMargin in MARGINS && extraBudget in BUDGETS)
        require(reservedApplications >= 0 && reservedCorrect in 0..reservedApplications)
    }
    /** Include this whole identity in asynchronous request/cache/log compatibility keys. */
    val version: String = "$VERSION:$priorCap:$neuralCap:$winnerMargin:$extraBudget:$contextualAutomatic:$reservedApplications:$reservedCorrect"
    val lateAutomaticEligible: Boolean get() = contextualAutomatic && reservedApplications >= 500 &&
        wilsonLower(reservedCorrect, reservedApplications) >= 0.99

    fun decide(snapshot: CorrectionSnapshot, context: CorrectionContext = CorrectionContext.EMPTY): CorrectionDecision {
        val ranked = snapshot.candidates.map { candidate ->
            val p = context.contribution(candidate.word, priorCap, false)
            val n = context.contribution(candidate.word, neuralCap, true)
            CorrectionEvidence(candidate, p, n, candidate.baselineScore + p + n)
        }.sortedWith(compareByDescending<CorrectionEvidence> { it.score }.thenBy { it.candidate.word })
        val best = ranked.firstOrNull()
        // Unchanged has zero repair evidence and competes with the best replacement for automatic edits.
        val margin = best?.let { it.score - maxOf(0f, ranked.getOrNull(1)?.score ?: 0f) } ?: 0f
        val reasons = ArrayList<String>()
        snapshot.guardReason?.let(reasons::add)
        val extra = if (snapshot.normalized.length >= 4) extraBudget else 0f
        if (best == null) reasons += "no-candidate"
        if (best != null) {
            if (best.candidate.cost > snapshot.autoBudget + extra) reasons += "cost-budget"
            if (best.candidate.rareTarget && best.candidate.cost > 1f) reasons += "rare-target-budget"
            if (!best.candidate.lengthAllowed) reasons += "length-guard"
            if (best.score < 0f) reasons += "unchanged-wins"
            if (margin < winnerMargin) reasons += "winner-margin"
        }
        val contextualWord = best?.takeIf { snapshot.contextualEligible && it.score >= -15f }?.candidate?.word
        val suggestion = contextualWord ?: snapshot.legacy?.word
        val contextualPass = snapshot.contextualEligible && best != null && reasons.isEmpty()
        if (!contextualAutomatic) reasons += "legacy-automatic-only"
        if (!lateAutomaticEligible) reasons += "late-validation-gate"
        val automaticWord = if (contextualAutomatic && snapshot.contextualEligible)
            best?.candidate?.word?.takeIf { contextualPass } else snapshot.legacy?.takeIf { it.automatic }?.word
        return CorrectionDecision(version, suggestion, automaticWord?.let { CorrectionOutcome(it, true) } ?: snapshot.unchanged,
            snapshot.unchanged, margin, frozenList(ranked), frozenList(reasons),
            lateAutomaticEligible && contextualPass)
    }

    companion object {
        // v4 adds manual possessive offers alongside bounded multiword offers. Automatic decisions remain legacy;
        // old logs stay readable but cannot silently append under a changed repair policy. v5 lets a one-slip
        // missed-space offer ("ofbthe" → "of the") replace a single-word offer; automatic decisions are unchanged.
        const val VERSION = "contextual-correction-v5"
        const val LEGACY_VERSION = "legacy-correction-v1"
        val CAPS = listOf(0f, 2f, 4f, 8f)
        val MARGINS = listOf(6f, 8f, 12f)
        val BUDGETS = listOf(0f, 0.25f, 0.5f)
        fun wilsonLower(correct: Int, applications: Int): Double {
            require(applications >= 0 && correct in 0..applications)
            if (applications == 0) return 0.0
            val n = applications.toDouble()
            val p = correct / n
            val z = 1.959963984540054
            val z2 = z * z
            return (p + z2 / (2 * n) - z * sqrt(p * (1 - p) / n + z2 / (4 * n * n))) / (1 + z2 / n)
        }
    }
}

data class CorrectionEvidence(val candidate: CorrectionCandidate, val prior: Float, val neural: Float, val score: Float)
data class CorrectionDecision(
    val policyVersion: String,
    val suggestion: String?,
    val automatic: CorrectionOutcome,
    val unchanged: CorrectionOutcome,
    val margin: Float,
    val evidence: List<CorrectionEvidence>,
    val reasons: List<String>,
    val lateAutomaticEligible: Boolean,
)
