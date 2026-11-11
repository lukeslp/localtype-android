package dev.localtype.keyboard

/** Plans word edits from one bounded editor read; the caller applies them in a batch. */
object WordEdits {
    data class Replacement(val before: Int, val after: Int, val text: String, val autoSpaced: Boolean = false)

    data class Suggestion(
        val replacement: Replacement,
        val word: String,
        val prefix: String,
        val contextBeforeWord: String,
        val learningEligible: Boolean,
    )

    data class Separator(
        val replacement: Replacement,
        val beforeSeparator: String,
        val prefix: String,
        val corrected: Boolean,
        val learningEligible: Boolean,
        val precondition: VerifiedWordTransaction.Snapshot? = null,
        /** A camel-case word or #tag ([DomainTyping.casedWord]): it may be learned, but is never corrected or logged. */
        val casedToken: Boolean = false,
    )

    /** A refused deletion cannot append a second word. A separator can still be inserted as typed. */
    fun apply(
        edit: Replacement, fallback: String? = null,
        delete: (Int, Int) -> Boolean, commit: (String) -> Boolean,
    ): Replacement? {
        val applied = if ((edit.before > 0 || edit.after > 0) && !delete(edit.before, edit.after)) {
            fallback?.let { Replacement(0, 0, it) } ?: return null
        } else edit
        return applied.takeIf { commit(it.text) }
    }

    fun doubleSpace(before: String, quick: Boolean, selected: Boolean, letters: Boolean, autoSpaced: Boolean = false): Replacement? =
        if (quick && !selected && letters && !(autoSpaced && punctuationSpace(before)) &&
            TextRules.shouldInsertPeriodOnSpace(before)) Replacement(1, 0, ". ") else null

    private fun punctuationSpace(before: String): Boolean {
        if (!before.endsWith(" ") || before.length < 2) return false
        val preceding = before[before.length - 2]
        return TextRules.attachesToPreviousWord(preceding) || preceding in "\"”"
    }

    fun attachPunctuation(before: String, value: String, autoSpaced: Boolean, selected: Boolean): Replacement? {
        if (selected || !autoSpaced || !before.endsWith(" ")) return null
        if (Regex("\\.[a-zA-Z]{2,63}").matches(value)) return Replacement(1, 0, value)
        if (value.length != 1) return null
        // A completion's space is provisional. An apostrophe continues its word, so unlike
        // sentence punctuation it must not add another space before the person's suffix.
        if (value[0] in "'’" && before.length > 1 && before[before.length - 2].isLetter()) {
            return Replacement(1, 0, value)
        }
        if (value[0] in "\"”" && before.length > 1 && before[before.length - 2].isLetter()) {
            return Replacement(1, 0, "$value ", autoSpaced = true)
        }
        if (value == ".") return Replacement(1, 0, ".")
        return if (TextRules.attachesToPreviousWord(value[0])) Replacement(1, 0, "$value ", autoSpaced = true) else null
    }

    /**
     * A collapsed cursor replaces its whole word, on both sides. A selection replaces only itself.
     * Missing reads or a word continuing beyond the read limit cannot establish the whole word.
     * [word] already has the strip's case; the original word also supplies it at a word's beginning.
     */
    fun suggestion(
        before: String?, after: String?, selected: Boolean, word: String, revisited: Boolean,
        readLimit: Int, capsLock: Boolean = false, rule: WordRule = WordRule.DEFAULT,
    ): Suggestion? {
        if (word.isBlank()) return null
        if (selected) {
            val space = needsSpace(after)
            val reuseSpace = after?.startsWith(" ") == true
            return Suggestion(Replacement(0, if (reuseSpace) 1 else 0,
                word + if (space || reuseSpace) " " else "", space || reuseSpace), word, "", "", false)
        }
        before ?: return null
        after ?: return null
        val prefix = rule.activePrefix(before)
        if (clippedPrefix(before, prefix, readLimit) || splitsCharacter(before, after) || markBeforePrefix(before, prefix)) return null
        // Apostrophes and hyphens continue an existing word, but don't begin a word on their own.
        val suffix = if (prefix.isNotEmpty()) rule.continuation.find(after)?.value.orEmpty()
        else rule.word.find(after)?.takeIf { it.range.first == 0 }?.value.orEmpty()
        if (suffix.length == after.length && after.length >= readLimit) return null
        if (suffix.length == after.length - 1 && after.length >= readLimit && after.last().isHighSurrogate()) return null
        val original = prefix + suffix
        val cased = TextRules.applyCase(word, original, sentenceStart = false, capsLock = capsLock)
        val following = after.drop(suffix.length)
        if (startsWithMark(following)) return null
        val reuseSpace = following.startsWith(" ")
        val space = needsSpace(following)
        return Suggestion(
            Replacement(prefix.length, suffix.length + if (reuseSpace) 1 else 0, cased + if (space || reuseSpace) " " else "", space || reuseSpace),
            cased, prefix, before.dropLast(prefix.length),
            // Opening an untouched empty field marks the trace revisited too; its p=0 tap is a new word.
            learningEligible = suffix.isEmpty() && (!revisited || prefix.isEmpty()),
        )
    }

    /**
     * Space or attaching punctuation finishes a word regardless of the keyboard's current layer.
     * Only a proven word end may be rewritten or learned. A separator inside a word, after a paste
     * or on a selection is a plain text insert; it is never a prediction-log outcome.
     */
    fun separator(
        before: String, after: String?, selected: Boolean, separator: String, revisited: Boolean,
        suggestions: Boolean, automatic: Boolean, readLimit: Int, typed: Boolean = true,
        capitalizeI: Boolean = true, autoSpaced: Boolean = false, rule: WordRule = WordRule.DEFAULT,
        correct: (String) -> String?,
    ): Separator {
        // Only a known provisional space may be consumed. Manual whitespace stays literal.
        if (autoSpaced && !selected && after != null && before.endsWith(" ")) {
            if (separator == "\n") return Separator(Replacement(1, 0, "\n"), before.dropLast(1), "", false, false)
            if (separator == " " && punctuationSpace(before)) {
                return Separator(Replacement(0, 0, ""), before, "", false, false)
            }
        }
        val prefix = rule.activePrefix(before)
        val end = !selected && after != null && !clippedPrefix(before, prefix, readLimit) &&
            !splitsCharacter(before, after) && !markBeforePrefix(before, prefix) && rule.continuation.find(after)?.value.isNullOrEmpty()
        val protected = DomainTyping.protected(before, after)
        val cased = protected && DomainTyping.casedWord(before, after)
        val eligible = suggestions && typed && end && !revisited && (!protected || cased) && prefix.isNotEmpty() &&
            (isLetterCodePoint(codePointBefore(prefix, prefix.length)) || prefix.last() in rule.marks)
        val correction = if (eligible && automatic && !cased) correct(prefix) else null
        val capital = if (eligible && capitalizeI && prefix == "i") "I" else null
        val replacement = correction ?: capital
        val edit = Replacement(if (replacement == null) 0 else prefix.length, 0, replacement.orEmpty() + separator)
        return Separator(edit, before.dropLast(edit.before) + replacement.orEmpty(), prefix, correction != null, eligible,
            casedToken = cased && eligible)
    }

    private fun clippedPrefix(before: String, prefix: String, limit: Int): Boolean = before.length >= limit &&
        (prefix.length == before.length || (before.first().isLowSurrogate() && prefix.length == before.length - 1))

    private fun splitsCharacter(before: String, after: String): Boolean =
        before.lastOrNull()?.isHighSurrogate() == true || after.firstOrNull()?.isLowSurrogate() == true ||
            startsWithMark(after)

    private fun startsWithMark(text: String): Boolean = text.isNotEmpty() && codePointType(codePointAt(text, 0)) in MARKS

    private fun markBeforePrefix(before: String, prefix: String): Boolean {
        val boundary = before.length - prefix.length
        return boundary > 0 && codePointType(codePointBefore(before, boundary)) in MARKS
    }

    private fun needsSpace(after: String?): Boolean {
        val first = after?.firstOrNull() ?: return true
        // A following paragraph/hard break stays after the caret while typing continues in this one.
        // Keep a provisional space on this side of it; the boundary itself is never consumed.
        if (first in "\n\r\u2028\u2029") return true
        return !first.isWhitespace() && !startsWithMark(after.orEmpty()) && first.category.javaType() !in PUNCTUATION
    }

    private val MARKS = setOf(UnicodeType.NON_SPACING_MARK, UnicodeType.COMBINING_SPACING_MARK, UnicodeType.ENCLOSING_MARK)
    private val PUNCTUATION = setOf(
        UnicodeType.CONNECTOR_PUNCTUATION, UnicodeType.DASH_PUNCTUATION, UnicodeType.START_PUNCTUATION,
        UnicodeType.END_PUNCTUATION, UnicodeType.INITIAL_QUOTE_PUNCTUATION, UnicodeType.FINAL_QUOTE_PUNCTUATION,
        UnicodeType.OTHER_PUNCTUATION,
    )
}
