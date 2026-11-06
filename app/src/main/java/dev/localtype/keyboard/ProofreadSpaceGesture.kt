package dev.localtype.keyboard

/** Only three actual, verified space taps can request proofreading. No delayed text insertion. */
class ProofreadSpaceGesture(private val intervalMs: Long = 700) {
    private data class Tap(val time: Long, val generation: Long, val receipt: VerifiedWordTransaction.Snapshot)
    private var first: Tap? = null
    private var period: Tap? = null

    fun reset() { first = null; period = null }

    fun consume(time: Long, generation: Long, current: VerifiedWordTransaction.Snapshot?): Boolean {
        val armed = period ?: return false
        reset()
        return time - armed.time in 0 until intervalMs && generation == armed.generation && current == armed.receipt
    }

    fun committed(time: Long, generation: Long, initial: VerifiedWordTransaction.Snapshot?,
                  receipt: VerifiedWordTransaction.Snapshot?, madePeriod: Boolean, automaticSpace: Boolean) {
        val previous = first
        reset()
        if (initial == null || receipt == null || !initial.selection.collapsed || !receipt.selection.collapsed) return
        if (madePeriod) {
            if (!automaticSpace && previous != null && previous.generation == generation && previous.receipt == initial &&
                time - previous.time in 0 until intervalMs && receipt.before.endsWith(". ")) {
                period = Tap(time, generation, receipt)
            }
        } else if (!automaticSpace && receipt.before.endsWith(' ')) {
            first = Tap(time, generation, receipt)
        }
    }
}
