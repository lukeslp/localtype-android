package dev.localtype.keyboard

/** A bounded editor read that retains a stable sentence start for incremental CIFG sessions. */
object NeuralContext {
    const val MAX_READ_CHARS = 8192
    private const val SENTENCE_MARKS = ".!?…\n。．？！¿¡‥"

    /**
     * Matches [CifgTokenizer]'s sentence marks, including its canonical look-alikes. A decimal
     * point inside a number is a number token, so it must not cut the sentence. Beyond the bound
     * a session may re-read its token window; no unbounded editor text is retained.
     */
    fun sentence(beforeCursor: String): String {
        var start = (beforeCursor.length - MAX_READ_CHARS).coerceAtLeast(0)
        if (start > 0 && beforeCursor[start].isLowSurrogate() && beforeCursor[start - 1].isHighSurrogate()) start++
        for (i in beforeCursor.lastIndex downTo start) {
            val char = beforeCursor[i]
            if (char !in SENTENCE_MARKS) continue
            if (char == '.' && i > 0 && i + 1 < beforeCursor.length &&
                isDigitCodePoint(codePointBefore(beforeCursor, i)) && isDigitCodePoint(codePointAt(beforeCursor, i + 1))) continue
            return beforeCursor.substring(i + 1)
        }
        return beforeCursor.substring(start)
    }
}
