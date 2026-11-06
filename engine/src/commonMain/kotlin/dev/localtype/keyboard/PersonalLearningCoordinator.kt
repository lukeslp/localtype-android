package dev.localtype.keyboard

/** Main-thread lifecycle adapter. Editor text is used only to verify an anchor, never as context. */
class PersonalLearningCoordinator(
    private val record: (List<String>, String, LearningOrigin) -> Long?,
    private val retract: (Long) -> Boolean,
) {
    class Contribution constructor(
        val owner: PersonalLearningCoordinator,
        val storeReceipt: Long?,
        val traceReceipt: PersonalContextTrace.Receipt?,
    ) {
        var consumed = false
    }
    private val trace = PersonalContextTrace()
    private var sequence = 0L
    private var anchor: String? = null
    var generation: Long = 0
        private set

    fun reset(reason: PersonalContextTrace.ResetReason) {
        generation++
        trace.reset(reason)
        anchor = null
    }

    /** A word may extend only the exact owned completion tail, allowing surrounding whitespace. */
    fun context(beforeWord: String): List<String> {
        if (anchor?.let { !beforeWord.trimEnd().endsWith(it) } == true)
            reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        return trace.snapshot()
    }

    /** A stale scheduled request must not reset the live typing trace while asking about its old input. */
    fun modelRequest(context: String, allowed: Boolean): WordPersistence.Request = WordPersistence.Request(
        anchor?.let { context.trimEnd().endsWith(it) } == true &&
            WordPersistence.modelContext(context, trace.snapshot(), allowed), generation,
    )

    fun complete(
        beforeWord: String, original: String, committed: String, origin: LearningOrigin,
        allowed: Boolean, verified: Boolean, sentenceEnded: Boolean = false,
    ): Contribution? {
        if (!allowed) { reset(PersonalContextTrace.ResetReason.PROTECTED_FIELD); return null }
        if (!WordPersistence.verified(allowed, verified)) { reset(PersonalContextTrace.ResetReason.UNVERIFIABLE); return null }
        val previous = context(beforeWord)
        val ownOrigin = when (origin) {
            LearningOrigin.EXPLICIT_KEEP -> PersonalContextTrace.OwnOrigin.EXPLICIT_KEEP
            LearningOrigin.SUGGESTION_ACCEPT -> PersonalContextTrace.OwnOrigin.SUGGESTION_ACCEPT
            LearningOrigin.TYPED, LearningOrigin.VERIFIED_REPAIR -> PersonalContextTrace.OwnOrigin.FULLY_TYPED
        }
        val receipt = trace.appendVerifiedOwnCompletion(++sequence, original, ownOrigin, true) ?: return null
        if (origin == LearningOrigin.VERIFIED_REPAIR && !trace.replaceMostRecentAfterVerifiedCorrection(receipt, committed)) {
            reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            return null
        }
        val contribution = Contribution(this, record(previous, committed, origin), receipt)
        anchor = (beforeWord + committed).trimEnd().takeLast(64)
        if (sentenceEnded) reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        return contribution
    }

    /**
     * A real word the person typed and completed here that teaches nothing because its preceding word
     * was not known to be their own. It joins the record so the word after it is known to follow a
     * typed one, and nothing is stored for it.
     */
    fun own(beforeWord: String, word: String) {
        context(beforeWord)
        if (trace.appendVerifiedOwnCompletion(++sequence, word, PersonalContextTrace.OwnOrigin.FULLY_TYPED, true) == null) {
            reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            return
        }
        anchor = (beforeWord + word).trimEnd().takeLast(64)
    }

    /** Deferred repair has verified its own range; context was captured before the unknown word. */
    fun repair(context: List<String>, word: String, allowed: Boolean, origin: LearningOrigin = LearningOrigin.VERIFIED_REPAIR): Contribution? {
        if (!WordPersistence.verified(allowed, true)) return null
        val contribution = Contribution(this, record(context.toList(), word, origin), null)
        // Unknown raw words break ownership; a late repair cannot reconstruct intervening context.
        reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        return contribution
    }

    /** Called only after the exact editor Undo succeeds. Later store observations remain intact. */
    fun undo(contribution: Contribution?) {
        if (contribution == null || contribution.owner !== this || contribution.consumed) return
        contribution.consumed = true
        contribution.storeReceipt?.let(retract)
        contribution.traceReceipt?.let(trace::removeAfterVerifiedUndo)
        reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
    }
}
