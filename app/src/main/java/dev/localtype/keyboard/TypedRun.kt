package dev.localtype.keyboard

/**
 * The run of non-whitespace text before the caret, for as long as every character of it was typed on this
 * keyboard, in order, starting at white space or the start of the field. Pure.
 *
 * A web address is remembered only from such a run. The checks that already exist stop at the dot: a name
 * pasted in and followed by a typed ".io" looks clean to them, and so would a host the app filled in itself.
 * Each plain append is reported with the text that stood before it. A run starts when that text is empty or
 * ends in white space, grows while the text before still ends with exactly the run at a white-space boundary,
 * shrinks with Backspace, and is given up by anything else: a paste, a strip tap, a correction, a cursor move.
 */
class TypedRun {
    /** Null while the text before the caret holds something this keyboard did not type since the last white space. */
    private var run: String? = null

    /** Only a run with a dot in it can be an address, which is all the run is kept for. */
    val mayBeAddress: Boolean get() = run?.contains('.') == true

    /** How much is owned, for diagnostics; never the text. */
    val length: Int get() = run?.length ?: -1

    /** A plain append of [text] to a field whose text before the caret was [before]; null when that could not be read. */
    fun appended(before: String?, text: String) {
        if (before == null) return reset()
        val base = when {
            before.isEmpty() || before.last().isWhitespace() -> ""
            else -> run?.takeIf { it.isNotEmpty() && endsWith(before, it) }
        }
        val afterSpace = text.takeLastWhile { !it.isWhitespace() }
        run = when {
            // White space in what was typed ends any run; what follows it starts a new one.
            afterSpace.length < text.length -> afterSpace
            base == null -> null
            else -> (base + text).takeIf { it.length <= MAX_CHARS }
        }
    }

    /** Backspace took the last [chars] of [before], the text that stood before the caret. */
    fun deleted(before: String, chars: Int) {
        val owned = run ?: return
        // A press somewhere else in the field, or one that took more than the run, leaves nothing to stand on.
        run = if (chars in 1..owned.length && endsWith(before, owned)) owned.dropLast(chars) else null
    }

    /** Anything else that changed the text or moved the caret. */
    fun reset() {
        run = null
    }

    /** The run, when [before] (the text before the caret now) still ends with exactly it at a white-space boundary. */
    fun current(before: String): String? = run?.takeIf { it.isNotEmpty() && endsWith(before, it) }

    private fun endsWith(before: String, owned: String): Boolean =
        before.endsWith(owned) && (before.length == owned.length || before[before.length - owned.length - 1].isWhitespace())

    private companion object {
        /** A host name is at most 253 characters; a longer run is not one, and the window read from the field is 280. */
        const val MAX_CHARS = 260
    }
}
