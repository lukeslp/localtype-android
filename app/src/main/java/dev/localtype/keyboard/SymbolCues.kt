package dev.localtype.keyboard

/**
 * Symbols offered in the strip's last slot for what was just typed: `(c)` offers ©, `(tm)` ™, `->` →, `1/2` ½,
 * and the whole word "copyright" or "trademark" offers its sign. Pure. Luke, November 7, 2026, after a thread on
 * his Bluesky about ™ (people said the signs are "impossible to get" on a phone, and that apps turn a typed ™ into
 * the ™️ emoji): "we could predict (C) and copyright even as the copyright symbol".
 *
 * It's an offer, never an automatic change: taking it replaces the cue, and Delete right after puts the cue back,
 * as with every [Expansions.Offer]. The sign goes in as the plain character, with no emoji variation selector.
 */
object SymbolCues {
    /** A cue typed at the end of the run before the caret, and the symbol it offers. Longest cues first. */
    val CUES: List<Pair<String, String>> = listOf(
        "(tm)" to "™", "(sm)" to "℠", "(c)" to "©", "(r)" to "®", "(p)" to "℗",
        "+/-" to "±", "<->" to "↔", "<=>" to "⇔",
        "->" to "→", "<-" to "←", "=>" to "⇒", "!=" to "≠", "<=" to "≤", ">=" to "≥", "+-" to "±",
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾", "1/8" to "⅛",
    )

    /** Whole words that offer their sign once typed in full; a short list, so ordinary words keep the slot. */
    val WORDS: Map<String, String> = mapOf("copyright" to "©", "trademark" to "™")

    /** What [Expansions.Offer.replace] removes and what goes in. */
    data class Found(val replace: Int, val symbol: String)

    /**
     * The symbol for the end of [run] (the non-whitespace text before the caret) or for [word], the word being typed,
     * or null. A fraction needs no digit or slash before it, so a date like 11/2 offers nothing; a letter cue in
     * parentheses ignores case, so (C) and (TM) work too.
     */
    fun find(run: String, word: String): Found? {
        val lower = run.lowercase()
        for ((cue, symbol) in CUES) {
            if (!lower.endsWith(cue)) continue
            val before = run.getOrNull(run.length - cue.length - 1)
            if (cue[0].isDigit() && before != null && (before.isDigit() || before == '/')) continue
            return Found(cue.length, symbol)
        }
        val start = run.length - word.length
        if (word.isNotEmpty() && run.endsWith(word) && (start == 0 || !run[start - 1].isLetterOrDigit())) {
            WORDS[word.lowercase()]?.let { return Found(word.length, it) }
        }
        return null
    }
}
