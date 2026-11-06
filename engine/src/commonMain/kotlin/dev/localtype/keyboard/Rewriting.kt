package dev.localtype.keyboard

/** How the Rewrite tools ask the on-device model to change a sentence or a selection. */
enum class RewriteStyle { SHORTER, FORMAL, FRIENDLY }

/**
 * The prompts for the Rewrite tools and how a reply is read. Pure, so the rules are tested. Like proofreading, the
 * answer is only ever a proposal the person applies, and nothing in it is learned.
 */
object Rewriting {
    fun prompt(style: RewriteStyle, text: String): String = buildString {
        appendLine("Rewrite this text typed on a phone keyboard.")
        appendLine(when (style) {
            RewriteStyle.SHORTER -> "Make it shorter and simpler, keeping every idea."
            RewriteStyle.FORMAL -> "Make it more formal and polite."
            RewriteStyle.FRIENDLY -> "Make it warmer and friendlier."
        })
        appendLine("Keep the meaning, the language, names and numbers. Do not add new facts.")
        appendLine("Reply with only the rewritten text and nothing else.")
        appendLine()
        append(text)
    }

    /**
     * The rewritten text from [reply], or null for an answer that isn't a plausible rewrite of [original]: empty, an
     * explanation, or far off the length [style] allows. A lead-in such as "Here is the rewritten text:" and quotes
     * the original didn't have come off.
     */
    fun clean(original: String, reply: String, style: RewriteStyle): String? {
        var text = reply.trim()
        text = text.replace(LEAD_IN, "").trim()
        if (text.length >= 2 && text.first() in QUOTES && text.last() in QUOTES && original.trimStart().firstOrNull()?.let { it in QUOTES } != true) {
            text = text.substring(1, text.length - 1).trim()
        }
        // A single line in, a single line out: a second paragraph is the model explaining itself.
        if ('\n' !in original) text = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (text.isEmpty()) return null
        val ratio = text.length.toDouble() / original.trim().length.coerceAtLeast(1)
        val allowed = if (style == RewriteStyle.SHORTER) 0.15..1.2 else 0.5..2.5
        return text.takeIf { ratio in allowed }
    }

    private const val QUOTES = "\"“”'"
    private val LEAD_IN = Regex(
        "^(here is|here's|sure[,!.]?\\s*here is|sure[,!.]?\\s*here's)[^:\\n]*:\\s*|^(rewritten( text)?|rewrite)\\s*:\\s*",
        RegexOption.IGNORE_CASE,
    )
}
