package dev.localtype.keyboard


/**
 * Own-completion context only; never seed this from editor text. Caller verifies the editor
 * transaction, whole-word ownership and learning permission before append/correction/undo.
 * Completion IDs must increase for the lifetime of this trace, including across resets.
 */
class PersonalContextTrace {
    enum class ResetReason { FIELD, RESTART, CURSOR, PASTE, UNVERIFIABLE, PROTECTED_FIELD }
    enum class OwnOrigin { FULLY_TYPED, EXPLICIT_KEEP, SUGGESTION_ACCEPT }
    class Receipt constructor(
        val owner: PersonalContextTrace,
        val completionId: Long,
        val contextBefore: List<String>,
        val word: String,
    )
    private data class Completion(val receipt: Receipt, var word: String)
    private val recent = ArrayList<Completion>(3)
    private var lastId = -1L

    /** A protected field clears context and yields no receipt, even if verification succeeded. */
    fun appendVerifiedOwnCompletion(
        completionId: Long, word: String, origin: OwnOrigin,
        learningAllowed: Boolean, sentenceEnded: Boolean = false,
    ): Receipt? {
        if (!learningAllowed) {
            lastId = maxOf(lastId, completionId)
            reset(ResetReason.PROTECTED_FIELD)
            return null
        }
        if (completionId < 0 || completionId <= lastId) return null
        lastId = completionId
        val normalized = normalizedWord(word)
        if (normalized == null) {
            reset(ResetReason.UNVERIFIABLE)
            return null
        }
        // Exhaustive origin check documents that paste and automatic repair are not own completions.
        when (origin) {
            OwnOrigin.FULLY_TYPED, OwnOrigin.EXPLICIT_KEEP, OwnOrigin.SUGGESTION_ACCEPT -> Unit
        }
        val receipt = Receipt(this, completionId, snapshot(), normalized)
        recent.add(Completion(receipt, normalized))
        if (recent.size > 3) recent.removeAt(0)
        if (sentenceEnded) sentenceBoundary()
        return receipt
    }

    /** Only a verified replacement of the most recent completion may change its spelling. */
    fun replaceMostRecentAfterVerifiedCorrection(receipt: Receipt, replacement: String): Boolean {
        val latest = recent.lastOrNull() ?: return false
        if (receipt.owner !== this || latest.receipt !== receipt) return false
        val word = normalizedWord(replacement) ?: run {
            reset(ResetReason.UNVERIFIABLE)
            return false
        }
        latest.word = word
        return true
    }

    /** Caller verified removal of this exact completion, preserving all subsequent editor text. */
    fun removeAfterVerifiedUndo(receipt: Receipt): Boolean {
        if (receipt.owner !== this) return false
        val index = recent.indexOfFirst { it.receipt === receipt }
        if (index < 0) return false
        recent.removeAt(index)
        return true
    }

    /** Call for . ! ? or newline, including punctuation entered after an earlier completion. */
    fun sentenceBoundary() { recent.clear() }

    fun reset(reason: ResetReason) {
        when (reason) {
            ResetReason.FIELD, ResetReason.RESTART, ResetReason.CURSOR, ResetReason.PASTE,
            ResetReason.UNVERIFIABLE, ResetReason.PROTECTED_FIELD -> recent.clear()
        }
    }

    fun snapshot(): List<String> = frozenList(recent.map { it.word })

    private fun normalizedWord(raw: String): String? {
        if (raw.length !in 1..128 || raw.encodeToByteArray().size > 512) return null
        val word = AdaptiveNgramModel.normalize(raw)
        return word.takeIf { it.length in 1..128 && it.encodeToByteArray().size <= 512 &&
            AdaptiveNgramModel.WORD_REGEX.matches(it) && LearnedPairState.validWord(it) }
    }
}
