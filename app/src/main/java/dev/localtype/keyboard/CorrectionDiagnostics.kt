package dev.localtype.keyboard

/** Content-free, bounded process counters; nothing is stored or exported automatically. */
class CorrectionDiagnostics {
    enum class Reason { ELIGIBLE, REVISITED, UNTRACED, RESTART, FIELD, MISSING_READ, COMMAND_REJECTED, UNVERIFIED, STALE, OFFERED, APPLIED, UNDONE, AUTOMATIC_CANDIDATE, NO_AUTOMATIC_CANDIDATE }
    enum class Stage { POSITION, RECEIPT, FINALIZATION }
    /** Why the current word stopped being the keyboard's own; one count per `WordTrace.revisit`. */
    enum class Revisit {
        RESTART, SELECTION, UNDO, SELECTION_DELETE, APOSTROPHE, COMMIT_FAILED, INSERT, PROOFREAD, RECENT_REPAIR,
        TAP_INELIGIBLE, PRECONDITION, FALLBACK, UNSAFE_READ, UNVERIFIED, UNDONE, LETTER_PREFIX, BACKSPACE_PREFIX,
        // SELECTION, split by which test in EditorEditState.acknowledge the callback failed.
        SELECTION_NEGATIVE, SELECTION_NO_ANCHOR, SELECTION_READ_MISSING, SELECTION_READ_MISMATCH,
        SELECTION_UNKNOWN_RECEIPT, SELECTION_VERIFY_FAILED,
        // The callback and the fresh read agree with each other, so the keyboard's anchor was wrong, not the read.
        // REANCHORED: the text before the caret is exactly the keyboard's own appended text, so the word stays own.
        SELECTION_READ_AGREES, SELECTION_REANCHORED,
        // A first start of a field, kept apart from RESTART (restarting=true), which alone means the app restarted input.
        FIELD_START,
    }
    private val counts = LongArray(Reason.entries.size)
    private val revisits = LongArray(Revisit.entries.size)
    fun revisit(reason: Revisit) { if (revisits[reason.ordinal] != Long.MAX_VALUE) revisits[reason.ordinal]++ }
    fun revisitSnapshot(): Map<Revisit, Long> = Revisit.entries.associateWith { revisits[it.ordinal] }
    private val keyCpu = CpuWindow()
    private val stages = Array(Stage.entries.size) { CpuWindow() }
    fun recordKeyCpu(nanos: Long) = keyCpu.record(nanos)
    fun keyCpuSummary(): Triple<Long, Long, Long> = keyCpu.summary()
    fun recordStageCpu(stage: Stage, nanos: Long) = stages[stage.ordinal].record(nanos)
    fun stageCpuSummary(stage: Stage): Triple<Long, Long, Long> = stages[stage.ordinal].summary()

    /** Sorting happens only on explicit export; nearest-rank p95 includes the tail of small runs. */
    private class CpuWindow {
        private val samples = LongArray(1024)
        private var count = 0L
        fun record(nanos: Long) { samples[(count++ % samples.size).toInt()] = nanos.coerceAtLeast(0) }
        fun summary(): Triple<Long, Long, Long> {
            val values = samples.take(minOf(count, samples.size.toLong()).toInt()).sorted()
            val rank = kotlin.math.ceil(values.size * .95).toInt() - 1
            return Triple(count, values.getOrNull(rank) ?: 0, values.lastOrNull() ?: 0)
        }
    }
    fun count(reason: Reason) { if (counts[reason.ordinal] != Long.MAX_VALUE) counts[reason.ordinal]++ }
    fun snapshot(): Map<Reason, Long> = Reason.entries.associateWith { counts[it.ordinal] }
}
