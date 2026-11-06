package dev.localtype.keyboard

/** An explicit request is bound to one field, selection and coherent text receipt. */
class ProofreadRequest private constructor(
    val generation: Long,
    val snapshot: VerifiedWordTransaction.Snapshot,
    val original: String,
    val leading: String,
    val trailing: String,
    val selected: Boolean,
) {
    fun matches(generation: Long, current: VerifiedWordTransaction.Snapshot?) =
        this.generation == generation && current == snapshot

    val replaced: String get() = leading + original + trailing

    fun replacement(corrected: String): WordEdits.Replacement =
        WordEdits.Replacement(if (selected) 0 else replaced.length, 0, leading + corrected + trailing)

    companion object {
        const val LIMIT = 600

        fun sentenceSelection(text: String?): Boolean = text != null && text.length <= LIMIT &&
            text.trim().split(Regex("\\s+")).count { word -> word.any(Char::isLetter) } >= 2

        fun capture(generation: Long, snapshot: VerifiedWordTransaction.Snapshot?): ProofreadRequest? {
            val receipt = snapshot ?: return null
            if (!receipt.selection.collapsed) {
                val text = receipt.selected ?: return null
                if (text.length > LIMIT || text.count(Char::isLetter) < 2) return null
                val body = text.trim()
                val leading = text.take(text.length - text.trimStart().length)
                val trailing = text.takeLast(text.length - text.trimEnd().length)
                return ProofreadRequest(generation, receipt, body, leading, trailing, true)
            }
            val target = Proofreading.target(receipt.before) ?: return null
            // A clipped first sentence is not the whole sentence the person asked to check.
            if (!receipt.beforeComplete && target.start == 0) return null
            return ProofreadRequest(generation, receipt, target.sentence, "", target.trailing, false)
        }
    }
}
