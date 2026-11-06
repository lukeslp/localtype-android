package dev.localtype.keyboard

/** One bounded repair and one recoverable replacement. No editor operations or learning here. */
class RecentWordCorrection {
    data class Identity(val field: Long, val dictionary: Int, val policy: String)
    data class Word(
        val identity: Identity, val original: String, val delimiter: String, val start: Int,
        val left: String, val right: String, val createdAt: Long, val previous: String? = null,
        val contextBeforeWord: String = "",
        val repairWindowMs: Long = REPAIR_MS, val grammar: Boolean = false,
    )
    data class RangeEdit(val start: Int, val end: Int, val text: String, val caret: Int)
    data class Undo(val word: Word, val replacement: String, val createdAt: Long)
    var pending: Word? = null
        private set
    var undo: Undo? = null
        private set
    private var pendingTail = ""
    private var undoTail = ""

    fun begin(word: Word) {
        pending = word.takeIf { it.start >= 0 && it.original.isNotBlank() && it.delimiter.isNotEmpty() }
        pendingTail = ""
    }

    /** Every append is known text; a new boundary closes the previous repair window. */
    fun append(text: String, now: Long, boundary: Boolean = false) {
        if (boundary) cancelPending()
        else if (pending != null) {
            pendingTail += text
            if (now - pending!!.createdAt > pending!!.repairWindowMs.coerceIn(1, UNDO_MS) || pendingTail.length > MAX_APPENDED) cancelPending()
        }
        if (undo != null) {
            undoTail += text
            if (now - undo!!.createdAt >= UNDO_MS || undoTail.length >= MAX_APPENDED) clearUndo()
        }
    }

    fun repair(identity: Identity, snapshot: VerifiedWordTransaction.Snapshot, replacement: String, now: Long): RangeEdit? {
        val word = pending ?: return null
        if (now - word.createdAt > word.repairWindowMs.coerceIn(1, UNDO_MS) || identity != word.identity || !matches(word, word.original, pendingTail, snapshot)) {
            cancelPending(); return null
        }
        if (replacement.isBlank() || replacement == word.original) return null
        return RangeEdit(word.start, word.start + word.original.length, replacement,
            snapshot.selection.end + replacement.length - word.original.length)
    }

    /** Only a postcondition-verified command creates Undo. */
    fun applied(word: Word, replacement: String, now: Long, following: String = pendingTail) {
        undo = Undo(word, replacement, now)
        undoTail = following
        cancelPending()
    }

    fun undoEdit(identity: Identity, snapshot: VerifiedWordTransaction.Snapshot, now: Long): RangeEdit? {
        val record = undo ?: return null
        if (now - record.createdAt >= UNDO_MS || undoTail.length >= MAX_APPENDED || identity != record.word.identity ||
            !matches(record.word, record.replacement, undoTail, snapshot)) {
            clearUndo(); return null
        }
        return RangeEdit(record.word.start, record.word.start + record.replacement.length, record.word.original,
            snapshot.selection.end + record.word.original.length - record.replacement.length)
    }

    fun hasUndo(now: Long): Boolean {
        if (undo?.let { now - it.createdAt >= UNDO_MS } == true) clearUndo()
        return undo != null
    }
    fun cancelPending() { pending = null; pendingTail = "" }
    fun clearUndo() { undo = null; undoTail = "" }
    fun cancel() { cancelPending(); clearUndo() }

    private fun matches(word: Word, actual: String, tail: String, s: VerifiedWordTransaction.Snapshot): Boolean {
        val suffix = word.left + actual + word.delimiter + tail
        return s.selection.collapsed && s.selection.end == word.start + actual.length + word.delimiter.length + tail.length &&
            s.before.endsWith(suffix) && s.after == word.right &&
            (word.original.isEmpty() || (!word.original.first().isLowSurrogate() && !word.original.last().isHighSurrogate()))
    }

    companion object {
        const val REPAIR_MS = 500L
        const val UNDO_MS = 5_000L
        const val MAX_APPENDED = 64
    }
}
