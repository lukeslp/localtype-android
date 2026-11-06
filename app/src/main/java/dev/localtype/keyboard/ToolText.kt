package dev.localtype.keyboard

/** Text the tools read from the field. Pure, so it is tested. */
object ToolText {
    /** How much text before the caret Read aloud looks at. */
    const val READ_CHARS = 1_000

    /**
     * The sentence the caret is in or has just ended: the text after the last sentence end that has more after it,
     * trimmed. "One. Two three." gives "Two three."; a line break ends a sentence too.
     */
    fun lastSentence(before: String): String {
        val text = before.trimEnd()
        if (text.isEmpty()) return ""
        var start = 0
        for (i in 0 until text.length - 1) {
            val c = text[i]
            if (c == '\n' || ((c == '.' || c == '!' || c == '?') && text[i + 1].isWhitespace())) start = i + 1
        }
        return text.substring(start).trim()
    }

    /**
     * A date or time from the Tools panel, with a space after it unless the text after the caret already starts with
     * one, so the next word starts fresh instead of completing the time's last letters ("AM" to "AMOUNT").
     */
    fun inserted(text: String, after: String?): String =
        if (after != null && after.firstOrNull()?.isWhitespace() == true) text else "$text "

    /** How much of the field, or of a selection, Count reads; longer says it is too long rather than give a wrong count. */
    const val COUNT_CHARS = 100_000

    /** How long a hand-off may be: search takes a short query, Send to app a passage. */
    const val SEARCH_CHARS = 200
    const val SEND_CHARS = 5_000

    private fun wordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '-' || c == '_'

    /** The word the caret is in or touches, from the text on each side of it; "" between spaces. */
    fun wordAt(before: String, after: String): String {
        var start = before.length
        while (start > 0 && wordChar(before[start - 1])) start--
        var end = 0
        while (end < after.length && wordChar(after[end])) end++
        return (before.substring(start) + after.substring(0, end)).trim('\'', '\u2019', '-', '_')
    }

    enum class Case { LOWER, TITLE, UPPER }

    /** The case Change case turns [text] into: UPPERCASE to lowercase, lowercase to Title Case, anything else to UPPERCASE. */
    fun nextCase(text: String): Case = when {
        text.uppercase() == text && text.lowercase() != text -> Case.LOWER
        text.lowercase() == text -> Case.TITLE
        else -> Case.UPPER
    }

    /** [text] in [case]. Title Case capitalizes the first letter of every word and lowers the rest. */
    fun inCase(text: String, case: Case): String = when (case) {
        Case.LOWER -> text.lowercase()
        Case.UPPER -> text.uppercase()
        Case.TITLE -> buildString {
            var start = true
            for (c in text) {
                append(if (start && c.isLetter()) c.titlecaseChar() else c.lowercaseChar())
                start = !(c.isLetterOrDigit() || c == '\'' || c == '\u2019')
            }
        }
    }

    /**
     * The selection Change case works on, start first: a selection made leftwards (with the arrow pad, say) is
     * reported with its start after its end. Null unless it is at non-negative positions and spans exactly
     * [selectedLength] characters.
     */
    fun caseRange(start: Int, end: Int, selectedLength: Int): Pair<Int, Int>? {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        return if (from < 0 || selectedLength <= 0 || to - from != selectedLength) null else from to to
    }

    /** Words (runs of non-space) and characters (code points, line breaks not counted) in [text]. */
    fun count(text: String): Pair<Int, Int> {
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val chars = text.codePointCount(0, text.length) - text.count { it == '\n' || it == '\r' }
        return words to chars
    }

    /** [count], or null for text longer than [COUNT_CHARS], a selection included: Count says it is too long. */
    fun countWithin(text: String): Pair<Int, Int>? = if (text.length > COUNT_CHARS) null else count(text)
}
