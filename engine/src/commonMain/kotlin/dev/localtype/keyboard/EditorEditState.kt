package dev.localtype.keyboard

/** Main-thread editor positions. Callback age never establishes who moved the cursor. */
class EditorEditState(private val capacity: Int = 64) {
    data class Selection(val start: Int, val end: Int) {
        val collapsed: Boolean get() = start >= 0 && start == end
    }
    /**
     * DELAYED: the receipt and a fresh read both name own positions at or beyond the one the editor
     * last confirmed, but behind the latest expected one. A WebView answers reads from its renderer,
     * which can trail the last commit, so this is either a late receipt the editor is still catching
     * up with or a move back to a position typed moments ago. Neither the callback's age nor the
     * stale read settles it; [verify] does, with a later fresh read, within [maxVerifications] attempts.
     */
    enum class Acknowledgement { OWN, UNCHANGED, UNEXPLAINED, DELAYED }
    /** Why the latest [Acknowledgement.UNEXPLAINED] was reached; positions themselves are never kept. */
    enum class Cause { NONE, NEGATIVE, NO_ANCHOR, READ_MISSING, READ_MISMATCH, UNKNOWN_RECEIPT, VERIFY_FAILED }
    var lastCause = Cause.NONE
        private set
    var generation = 0L
        private set
    var expected: Selection? = null
        private set
    private var acknowledged: Selection? = null
    /** The furthest own position the editor has reported or a fresh read has shown. */
    private var confirmed: Selection? = null
    private val history = ArrayDeque<Selection>()
    private var verifications = 0
    val maxVerifications = 6

    init { require(capacity > 0) }

    /** Every restart invalidates async work, even when its trace can be retained. */
    fun start(start: Int, end: Int, retainHistory: Boolean = false) {
        generation++
        if (!retainHistory) history.clear()
        expected = Selection(start, end).takeIf { start >= 0 && end >= 0 }
        acknowledged = expected
        confirmed = expected
        verifications = 0
        // The anchor is an own position too: a WebView's first read after a commit can still name it.
        expected?.let { if (history.lastOrNull() != it) history.addLast(it) }
        while (history.size > capacity) history.removeFirst()
    }

    /** Register before sending an edit, including the intermediate batch positions. */
    fun edit(before: Int, after: Int, inserted: Int) {
        require(before >= 0 && after >= 0 && inserted >= 0)
        val cursor = expected ?: return
        val position = minOf(cursor.start, cursor.end) - before + inserted
        if (position < 0) return uncertain()
        expected = Selection(position, position)
        history.addLast(expected!!)
        while (history.size > capacity) history.removeFirst()
    }

    fun select(start: Int, end: Int) {
        expected = Selection(start, end).takeIf { start >= 0 && end >= 0 }
        expected?.let { history.addLast(it) }
        while (history.size > capacity) history.removeFirst()
    }

    /**
     * [observed] is a fresh editor selection when available. It distinguishes a delayed receipt
     * for an earlier append from a real move back to that same position. Missing observations
     * can recognize the latest expected position, but cannot certify an intermediate receipt.
     */
    fun acknowledge(start: Int, end: Int, observed: Selection? = null): Acknowledgement {
        val received = Selection(start, end)
        val target = expected
        if (start < 0 || end < 0) return unexplained(received, observed, Cause.NEGATIVE)
        if (target == null) return unexplained(received, observed, Cause.NO_ANCHOR)
        if (observed != null && observed != target) {
            // Both own positions, neither before the one the editor already confirmed: the read may
            // trail the receipt (a WebView's text state updates after its selection callback) or the
            // receipt the read; verify() settles which with a later fresh read.
            val catchingUp = received in history && observed in history &&
                history.indexOf(received) >= history.indexOf(confirmed) &&
                history.indexOf(observed) >= history.indexOf(confirmed)
            return if (catchingUp) Acknowledgement.DELAYED else unexplained(received, observed, Cause.READ_MISMATCH)
        }
        val own = received == target && received in history ||
            (observed == target && received in history)
        if (own) {
            acknowledged = received
            confirmed = observed ?: received
            verifications = 0
            return Acknowledgement.OWN
        }
        if (received == acknowledged) return Acknowledgement.UNCHANGED
        return unexplained(received, observed, if (observed == null) Cause.READ_MISSING else Cause.UNKNOWN_RECEIPT)
    }

    /**
     * A later fresh read after [Acknowledgement.DELAYED]: the latest expected position resolves it
     * as own, another own position means the editor is still catching up, and anything else, or
     * running out of attempts, is an unexplained move.
     */
    fun verify(observed: Selection?): Acknowledgement {
        val target = expected ?: return unexplained(Selection(-1, -1), observed, Cause.NO_ANCHOR)
        if (observed == target) {
            acknowledged = target
            confirmed = target
            verifications = 0
            return Acknowledgement.OWN
        }
        if (observed != null && observed in history && ++verifications < maxVerifications) return Acknowledgement.DELAYED
        verifications = 0
        return unexplained(observed ?: Selection(-1, -1), observed, Cause.VERIFY_FAILED)
    }

    private fun unexplained(received: Selection, observed: Selection?, cause: Cause): Acknowledgement {
        lastCause = cause
        uncertain()
        // The caller must still invalidate the current word and asynchronous work. Agreement
        // with a fresh editor read establishes only where subsequent edits begin. Without this
        // anchor, every future append stays unknown until the editor restarts input.
        if (received.start >= 0 && received.end >= 0 && received == observed) {
            expected = observed
            acknowledged = observed
            confirmed = observed
        }
        return Acknowledgement.UNEXPLAINED
    }

    /** Rejected commands, ambiguous reads and unsafe operations discard all expected positions. */
    fun uncertain() {
        history.clear()
        expected = null
        acknowledged = null
        confirmed = null
        verifications = 0
    }
}
