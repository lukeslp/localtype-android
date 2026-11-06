package dev.localtype.keyboard

/**
 * The mail domains offered after an address's `@` before the person has typed any of their own: the common
 * providers, most used first. Pure. A remembered host still comes first on Android; these only fill in until
 * one is remembered, and taking one never counts as typing it.
 */
object MailDomains {
    val COMMON: List<String> = listOf(
        "gmail.com", "icloud.com", "outlook.com", "yahoo.com", "hotmail.com", "aol.com", "proton.me", "me.com",
        "live.com", "msn.com",
    )

    /**
     * What follows the last `@` in [run], the non-whitespace text before the caret, lowercase; null without one,
     * or when nothing of a name stands before it, since a leading `@` opens a handle, not an address.
     */
    fun typedAfterAt(run: String): String? {
        val at = run.lastIndexOf('@')
        if (at <= 0 || !run[at - 1].let { it.isLetterOrDigit() || it in "._-+" }) return null
        val typed = run.substring(at + 1).lowercase()
        return typed.takeIf { part -> part.all { it.isLetterOrDigit() || it == '.' || it == '-' } }
    }

    /**
     * The common domains that complete [typed], the part after `@`, best first, up to [limit]. Nothing before a
     * first letter, so a bare `@` (which may open a handle) offers nothing, and nothing once the domain is whole.
     */
    fun complete(typed: String, limit: Int = 3): List<String> {
        if (typed.isEmpty()) return emptyList()
        return COMMON.filter { it.length > typed.length && it.startsWith(typed) }.take(limit)
    }
}
