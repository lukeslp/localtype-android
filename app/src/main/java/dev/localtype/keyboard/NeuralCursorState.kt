package dev.localtype.keyboard

/** Recognizes only a cursor position explained by a known append, regardless of callback timing. */
class NeuralCursorState {
    private var cursor: Int? = null
    private var expected: Int? = null

    fun start(selectionStart: Int, selectionEnd: Int) {
        cursor = selectionEnd.takeIf { it >= 0 && selectionStart == it }
        expected = null
    }

    fun edit(plainAppend: Boolean, characters: Int) {
        expected = if (plainAppend && characters > 0) (expected ?: cursor)?.plus(characters) else null
        if (!plainAppend) cursor = null
    }

    /** False only for an unchanged selection or an exact known append; ambiguous callbacks reset. */
    fun selectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int): Boolean {
        if (oldStart == newStart && oldEnd == newEnd) return false
        val append = newStart == newEnd && newEnd >= 0 && newEnd == expected && oldStart == oldEnd
        cursor = newEnd.takeIf { it >= 0 && newStart == it }
        expected = null
        return !append
    }
}
