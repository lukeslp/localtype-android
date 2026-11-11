package dev.localtype.keyboard

/** A restarting compatibility editor stays literal until a verified, ordinary word boundary. */
object RestartCorrectionRecovery {
    fun canResume(
        delimiter: String,
        generation: Long,
        currentGeneration: Long,
        receipt: VerifiedWordTransaction.Snapshot?,
        observed: VerifiedWordTransaction.Snapshot?,
        expected: EditorEditState.Selection?,
    ): Boolean {
        if (delimiter != " " && delimiter != "\n") return false
        if (generation != currentGeneration || receipt == null || receipt != observed) return false
        if (!receipt.selection.collapsed || receipt.selection != expected || receipt.selected != null) return false
        if (!receipt.before.endsWith(delimiter)) return false
        // Do not release the guard from a separator inserted inside an existing word or identifier.
        return receipt.after.firstOrNull()?.let { !it.isLetterOrDigit() && it !in "'’_-@#" } ?: true
    }
}
