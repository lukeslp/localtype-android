package dev.localtype.keyboard


/**
 * Finds the host in a typed web or mail address, and nothing more of it. Pure.
 *
 * Only the host is ever kept: no scheme, no user part, no port, no path, no query. A run counts as an address
 * when it has at least two labels and ends in a real top-level domain (the bundled IANA list), so "e.g." and
 * "end.The" are not hosts while "datapoems.io" is.
 */
object DomainHosts {
    /** The top-level domains in the bundled asset: one per line, lowercase, `#` for a comment. */
    fun parseTlds(lines: Sequence<String>): Set<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()

    /**
     * The host in [run], a whole non-whitespace run such as `https://www.Example.com/page`, `me@example.com`
     * or `example.com.` at the end of a sentence; null when the run is not an address ending in one of [tlds].
     * Lowercase, without a leading `www.`.
     */
    fun host(run: String, tlds: Set<String>): String? {
        val authority = authority(run, allowRest = true) ?: return null
        val host = authority.trimEnd('.').removePrefix(WWW)
        val labels = host.split('.')
        if (host.length > MAX_HOST || labels.size < 2 || labels.any { !label(it) }) return null
        if (labels.first().length < 2 || labels.last() !in tlds) return null
        return host
    }

    /**
     * The start of a host still being typed in [run], lowercase and without a leading `www.`: what follows any
     * scheme and user part. Null once a path, port or query has begun, or when the run cannot be the start of
     * a host.
     */
    fun typing(run: String): String? {
        val authority = authority(run, allowRest = false) ?: return null
        val typed = authority.removePrefix(WWW)
        if (typed.isEmpty() || typed.length > MAX_HOST) return null
        // Labels so far, the last of them possibly unfinished or empty ("example." waits for its ending).
        val labels = typed.split('.')
        if (labels.dropLast(1).any { !label(it) } || labels.last().any { !labelChar(it) }) return null
        return typed
    }

    /** The authority of [run], lowercase, with any scheme, user part, port, path and surrounding punctuation gone. */
    private fun authority(run: String, allowRest: Boolean): String? {
        var text = run.trimStart { it in OPENERS }
        if (allowRest) text = text.trimEnd { it in CLOSERS }
        val scheme = text.indexOf("://")
        if (scheme >= 0) {
            if (text.substring(0, scheme).lowercase() !in SCHEMES) return null
            text = text.substring(scheme + 3)
        }
        val rest = text.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (rest >= 0) {
            if (!allowRest) return null
            text = text.substring(0, rest)
        }
        val user = text.lastIndexOf('@')
        // "@name" is a handle, not a mail address with nobody before the mark.
        if (user == 0) return null
        text = text.substring(user + 1)
        val port = text.indexOf(':')
        if (port >= 0) {
            if (!allowRest || text.substring(port + 1).any { !it.isDigit() }) return null
            text = text.substring(0, port)
        }
        return text.lowercase()
    }

    private fun label(text: String): Boolean =
        text.length in 1..MAX_LABEL && text.all(::labelChar) && text.first() != '-' && text.last() != '-'

    private fun labelChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '-'

    private const val WWW = "www."
    private const val MAX_HOST = 253
    private const val MAX_LABEL = 63
    private val SCHEMES = setOf("http", "https")
    /** What prose puts around an address: "(see example.com)." */
    private const val OPENERS = "([{<\"'“‘"
    private const val CLOSERS = ".,;:!?)]}>\"'”’"
}
