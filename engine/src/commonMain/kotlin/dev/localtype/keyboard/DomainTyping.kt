package dev.localtype.keyboard

/**
 * Dotted identifiers, email/domain fragments, paths, handles, hashtags and identifier-like tokens are literal text,
 * even in an ordinary prose field: nothing in them is corrected, split, learned or logged.
 */
object DomainTyping {
    private val DOTTED = Regex("[\\p{L}\\p{N}-]+\\.[\\p{L}\\p{N}-]+")
    private val EMAIL = Regex("[\\p{L}\\p{N}._+-]+@")
    /** "@name" and "#tag": the mark opens the token, or follows something that is not a letter or digit ("C#" is prose). */
    private val TAGGED = Regex("(?:^|[^\\p{L}\\p{N}])[@#][\\p{L}\\p{N}_]")
    /** snake_case, a digit beside a letter (h264, v2, 3rd), or a capital after a lowercase letter (camelCase, iPhone). */
    private val IDENTIFIER = Regex("[\\p{L}\\p{N}]_|_[\\p{L}\\p{N}]|\\p{L}\\p{N}|\\p{N}\\p{L}|\\p{Ll}\\p{Lu}")

    fun protected(before: String, after: String?): Boolean {
        // Keep just the current non-whitespace run. Look ahead covers editing before an existing .com.
        val left = before.takeLast(280).takeLastWhile { !it.isWhitespace() }
        val right = after.orEmpty().take(280).takeWhile { !it.isWhitespace() }
        val token = left + right
        return DOTTED.containsMatchIn(token) || EMAIL.containsMatchIn(token) || '/' in token || '\\' in token ||
            TAGGED.containsMatchIn(token) || IDENTIFIER.containsMatchIn(token)
    }

    private val CASED_WORD = Regex("[(\"“‘']?#?\\p{L}+(?:['’]\\p{L}+)*")

    /**
     * The run is only letters, and is [protected] just for a capital inside it ("LocalType", "iPhone") or a leading
     * # ("#BlackLivesMatter"). Such a word is still never corrected or split, but it may be learned and shown with
     * the person's own capitals ([WordSpellings]); a digit, underscore, dot, @ or slash keeps a run wholly literal.
     */
    fun casedWord(before: String, after: String?): Boolean {
        val left = before.takeLast(280).takeLastWhile { !it.isWhitespace() }
        val right = after.orEmpty().take(280).takeWhile { !it.isWhitespace() }
        return CASED_WORD.matches(left + right)
    }

    /** A period is uncertain until the next key: only whitespace confirms a sentence boundary. */
    data class Pending(
        val generation: Long,
        val snapshot: VerifiedWordTransaction.Snapshot,
        val original: String,
        val corrected: String,
        val contextBeforeWord: String,
        val logged: Boolean,
    ) {
        fun edit(generation: Long, current: VerifiedWordTransaction.Snapshot?, delimiter: String): WordEdits.Separator? {
            if (this.generation != generation || current != snapshot || delimiter !in listOf(" ", "\n", "")) return null
            if (!snapshot.before.endsWith("$original.")) return null
            val replacement = if (original == corrected) WordEdits.Replacement(0, 0, delimiter)
                else WordEdits.Replacement(original.length + 1, 0, "$corrected.$delimiter")
            return WordEdits.Separator(replacement,
                contextBeforeWord + corrected, original, corrected != original, true, snapshot)
        }
    }
}
