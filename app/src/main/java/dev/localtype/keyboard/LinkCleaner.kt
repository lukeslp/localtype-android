package dev.localtype.keyboard

import java.util.Locale

/**
 * Takes known tracking parameters off the links in pasted text. Pure.
 *
 * Only `http` and `https` links are touched, and only their query: a parameter goes when its name is on the list
 * below and its value is a plain token, everything else stays byte for byte and in order, and so does the
 * fragment. The list is written by hand from the names the large ad and mail platforms document for their own
 * click identifiers; nothing here is copied from a rule database. A name that is not on it is kept, so a link
 * that needs a parameter keeps working.
 *
 * A few sites carry their own trackers under names that mean something else elsewhere, so those names go only on
 * that site's hosts ([SITE_TRACKERS]), and Amazon's `/ref=…` path segment goes on Amazon only. Three redirect
 * wrappers (Google's `/url`, Facebook's `l.php` and YouTube's `/redirect`) are replaced by the link they carry,
 * decoded once, when that is a plain `http` or `https` link; the result is cleaned but never unwrapped again.
 *
 * Removing is the only thing this does to text, so it errs toward leaving things in. A link ends at white space,
 * at a quote or angle bracket, and at a closing bracket it did not open, which is where Markdown and prose end
 * one; and a value with anything in it but letters, digits and `. _ ~ % + - =` is left alone, because a second
 * link run into the first with a comma would otherwise be read as part of a tracker's value and deleted with it.
 */
object LinkCleaner {
    /** Longer text is pasted as it is: this runs on the typing thread. */
    const val MAX_CHARS = 100_000

    /** Click and campaign identifiers that mean the same thing on every site. Lowercase. */
    private val TRACKERS = setOf(
        "fbclid", "gclid", "gbraid", "wbraid", "dclid", "msclkid", "mc_eid", "mc_cid", "igshid", "igsh",
        "twclid", "ttclid", "yclid", "_hsenc", "_hsmi", "mkt_tok", "srsltid",
    )
    /** Every `utm_…` parameter is a campaign tag. */
    private const val CAMPAIGN_PREFIX = "utm_"
    private val AMAZON_HOSTS = listOf(
        "amazon.com", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.it", "amazon.es", "amazon.ca", "amazon.com.au",
        "amazon.co.jp", "amazon.in", "amazon.com.mx", "amazon.com.br", "amazon.nl", "amazon.se", "amazon.pl",
        "amazon.com.tr", "amazon.com.be", "amazon.sg", "amazon.ae", "amazon.sa", "amazon.eg",
    )
    private val EBAY_HOSTS = listOf(
        "ebay.com", "ebay.co.uk", "ebay.de", "ebay.fr", "ebay.it", "ebay.es", "ebay.ca", "ebay.com.au",
    )

    /**
     * Names that are trackers on these hosts (and their subdomains) and may be ordinary parameters anywhere else:
     * `si` is a share identifier on YouTube and Spotify and a unit system elsewhere. Lowercase. Amazon's `tag` is
     * an affiliate's credit, not a tracker, so it stays; so do `t`, `v`, `list` and every search or page setting.
     */
    private val SITE_TRACKERS: List<Pair<List<String>, Set<String>>> = listOf(
        listOf("youtube.com", "youtu.be") to setOf("si", "pp", "feature"),
        listOf("spotify.com") to setOf("si"),
        AMAZON_HOSTS to setOf("ref_", "ascsubtag", "linkcode", "linkid", "creativeasin"),
        listOf("tiktok.com") to setOf("is_from_webapp", "sender_device"),
        listOf("x.com", "twitter.com") to setOf("s", "ref_src"),
        EBAY_HOSTS to setOf("_trkparms", "_trksid"),
        listOf("linkedin.com") to setOf("trk", "trkinfo"),
        listOf("reddit.com") to setOf("share_id"),
    )

    /** A redirect wrapper: the hosts, the exact path, and the parameters that may carry the real link, in order. */
    private class Wrapper(val hosts: List<String>, val path: String, val targets: List<String>)
    private val WRAPPERS = listOf(
        Wrapper(listOf("google.com", "www.google.com"), "/url", listOf("q", "url")),
        Wrapper(listOf("l.facebook.com", "lm.facebook.com"), "/l.php", listOf("u")),
        Wrapper(listOf("youtube.com", "www.youtube.com", "m.youtube.com"), "/redirect", listOf("q")),
    )

    /** Characters of a `/ref=…` path value on Amazon. */
    private const val PATH_TOKEN_MARKS = "._~%-"

    /** How much of a field before the caret [atCaret] needs at most: a longer link is left alone. */
    const val CARET_READ_CHARS = 4_096

    private const val SEPARATOR = "://"
    private val SCHEMES = listOf("https", "http")
    private const val ENDS_LINK = "<>\"`"
    private const val TRAILING = ".,;:!?'"
    private val CLOSERS = mapOf(')' to '(', ']' to '[', '}' to '{')
    private const val TOKEN_MARKS = "._~%+-="

    /** [text] with tracking parameters removed from each link in it, or [text] itself when there is nothing to remove. */
    fun clean(text: String): String {
        if (text.length > MAX_CHARS) return text
        var out: StringBuilder? = null
        var copied = 0
        var from = 0
        while (true) {
            val start = nextLink(text, from) ?: break
            val end = linkEnd(text, start)
            val link = text.substring(start, end)
            val cleaned = cleanLink(link)
            if (cleaned != link) {
                val builder = out ?: StringBuilder(text.length).also { out = it }
                builder.append(text, copied, start).append(cleaned)
                copied = end
            }
            from = maxOf(end, start + 1)
        }
        return out?.append(text, copied, text.length)?.toString() ?: text
    }

    /** One link, already cut out of its surroundings: unwrapped when it is a known redirect, then cleaned. */
    internal fun cleanLink(link: String): String = unwrap(link)?.let(::cleanParts) ?: cleanParts(link)

    /** [link] without its trackers, or [link] itself when it has none. */
    private fun cleanParts(link: String): String {
        val hash = link.indexOf('#')
        val question = link.indexOf('?').takeUnless { hash in 0 until it } ?: -1
        // Where the part before the query or fragment ends.
        val baseEnd = when {
            question >= 0 -> question
            hash >= 0 -> hash
            else -> link.length
        }
        val base = link.substring(0, baseEnd)
        val host = host(base)
        val cleanBase = if (onHosts(host, AMAZON_HOSTS)) withoutRefSegment(base) else base
        if (question < 0) return if (cleanBase.length == base.length) link else cleanBase + link.substring(baseEnd)
        val fragment = if (hash >= 0) link.substring(hash) else ""
        val query = link.substring(question + 1, if (hash >= 0) hash else link.length)
        val siteNames = SITE_TRACKERS.filter { onHosts(host, it.first) }.flatMap { it.second }.toSet()
        val parameters = query.split('&')
        val kept = parameters.filterNot { tracks(it, siteNames) }
        if (kept.size == parameters.size && cleanBase.length == base.length) return link
        // A question mark with nothing after it says nothing; empty leftovers such as "&&" go with it.
        return if (kept.all { it.isEmpty() }) cleanBase + fragment else cleanBase + "?" + kept.joinToString("&") + fragment
    }

    private fun tracks(parameter: String, siteNames: Set<String>): Boolean {
        val name = parameter.substringBefore('=').lowercase(Locale.ROOT)
        // A campaign tag's name is letters, digits and underscores: without an "=" the whole parameter is the
        // name, and "utm_source,https://next.example/" must not pass for one.
        val campaign = name.startsWith(CAMPAIGN_PREFIX) && name.all { it.isLetterOrDigit() || it == '_' }
        if (name !in TRACKERS && !campaign && name !in siteNames) return false
        return parameter.substringAfter('=', "").all { it.isLetterOrDigit() || it in TOKEN_MARKS }
    }

    private fun onHosts(host: String, hosts: List<String>): Boolean = hosts.any { host == it || host.endsWith(".$it") }

    /** [base] (a link up to its query) without an Amazon `/ref=…` path segment, which names the page it was found on. */
    private fun withoutRefSegment(base: String): String {
        val pathStart = base.indexOf('/', base.indexOf(SEPARATOR) + SEPARATOR.length).takeIf { it >= 0 } ?: return base
        val at = base.indexOf("/ref=", pathStart)
        if (at < 0) return base
        val end = base.indexOf('/', at + 1).takeIf { it >= 0 } ?: base.length
        val value = base.substring(at + "/ref=".length, end)
        if (value.isEmpty() || !value.all { it.isLetterOrDigit() || it in PATH_TOKEN_MARKS }) return base
        return base.substring(0, at) + base.substring(end)
    }

    /**
     * The link a known redirect wrapper carries, decoded once, or null: only an exact wrapper host and path, and
     * only a target that is itself an `http` or `https` link with nothing in it that would end a link.
     */
    private fun unwrap(link: String): String? {
        val hash = link.indexOf('#')
        val question = link.indexOf('?')
        if (question < 0 || hash in 0 until question) return null
        val base = link.substring(0, question)
        val host = host(base)
        val path = base.substringAfter(SEPARATOR).let { rest -> rest.indexOf('/').takeIf { it >= 0 }?.let { rest.substring(it) } ?: "" }
        val wrapper = WRAPPERS.firstOrNull { host in it.hosts && path == it.path } ?: return null
        val parameters = link.substring(question + 1, if (hash >= 0) hash else link.length).split('&')
        for (name in wrapper.targets) {
            val encoded = parameters.firstOrNull { it.substringBefore('=').lowercase(Locale.ROOT) == name && '=' in it }
                ?.substringAfter('=') ?: continue
            val target = percentDecoded(encoded) ?: return null
            val scheme = target.substringBefore(SEPARATOR, "").lowercase(Locale.ROOT)
            if (scheme !in SCHEMES || target.length <= scheme.length + SEPARATOR.length) return null
            if (target.any { it.isWhitespace() || it in ENDS_LINK || it.isISOControl() }) return null
            return target
        }
        return null
    }

    /** [text] with each `%XX` decoded as UTF-8, or null when an escape is broken or the bytes are not UTF-8. A `+` stays. */
    private fun percentDecoded(text: String): String? {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val value = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(value)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString() }.getOrNull()
    }

    /**
     * A link the caret stands right after, with what cleaning it would leave: [replace] characters before the
     * caret (the link and any sentence punctuation after it) become [text]. Only [CARET_READ_CHARS] of text is
     * looked at.
     */
    data class AtCaret(val replace: Int, val text: String, val host: String)

    /**
     * The link that ends at the caret, when cleaning it would change it, or null. [before] is the text before the
     * caret; [whole] says it reaches the start of the field. [after] is at least two characters after the caret
     * (fewer only at the end of the field): a caret inside a link, or a link that runs on past it, gets nothing, and
     * so does a link whose start may lie before what was read.
     */
    fun atCaret(before: String, after: CharSequence, whole: Boolean): AtCaret? {
        if (before.length > CARET_READ_CHARS || !endsAt(after)) return null
        // Only the run before the caret can hold the link: it has no white space in it.
        val runStart = before.indexOfLast { it.isWhitespace() } + 1
        // A run that fills everything read may begin earlier, inside a longer link: rewriting part of that would
        // change the middle of a link the keyboard can't see whole.
        if (runStart == 0 && !whole) return null
        // The first link in the run that reaches the caret. A link nested in another's query ("?u=https://…") is
        // part of the outer one, and links run together ("(https://a)https://b") each end before the next starts.
        var start = nextLink(before, runStart) ?: return null
        var end = linkEnd(before, start)
        while (!reachesCaret(before.substring(end))) {
            start = nextLink(before, start + 1) ?: return null
            end = linkEnd(before, start)
        }
        val tail = before.substring(end)
        val link = before.substring(start, end)
        val cleaned = cleanLink(link)
        if (cleaned == link) return null
        return AtCaret(before.length - start, cleaned + tail, host(cleaned.substringBefore('?').substringBefore('#')))
    }

    /** Whether only sentence punctuation or closing brackets, and few of them, stand between a link's end and the caret. */
    private fun reachesCaret(tail: String): Boolean = tail.length <= MAX_TAIL && tail.all { it in TRAILING || it in CLOSERS.keys }

    /** At most this much punctuation or closing brackets may stand between the link and the caret ("…?utm_source=x)." is two). */
    private const val MAX_TAIL = 3

    /** Whether [after] (the text after the caret) ends a link standing before it. */
    private fun endsAt(after: CharSequence): Boolean {
        if (after.isEmpty()) return true
        val c = after[0]
        if (c.isWhitespace() || c in ENDS_LINK || c in CLOSERS.keys) return true
        return c in TRAILING && (after.length < 2 || after[1].isWhitespace() || after[1] in ENDS_LINK || after[1] in CLOSERS.keys)
    }

    /** The host of [base] (a link up to its query), lowercase, without user information or a port. */
    private fun host(base: String): String = base.substringAfter(SEPARATOR).substringBefore('/')
        .substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)

    /** Where the next `http://` or `https://` starts at or after [from], as a word of its own. */
    private fun nextLink(text: String, from: Int): Int? {
        var at = text.indexOf(SEPARATOR, from)
        while (at >= 0) {
            for (scheme in SCHEMES) {
                val start = at - scheme.length
                if (start >= from && text.regionMatches(start, scheme, 0, scheme.length, ignoreCase = true) &&
                    (start == 0 || !text[start - 1].isLetterOrDigit())
                ) return start
            }
            at = text.indexOf(SEPARATOR, at + 1)
        }
        return null
    }

    /** The end of the link that starts at [start]: before white space, a quote, or a closing bracket it did not open, less any sentence punctuation after it. */
    private fun linkEnd(text: String, start: Int): Int {
        val open = HashMap<Char, Int>()
        var end = start
        while (end < text.length) {
            val c = text[end]
            if (c.isWhitespace() || c in ENDS_LINK) break
            val opener = CLOSERS[c]
            if (opener != null) {
                val depth = open[opener] ?: 0
                if (depth == 0) break
                open[opener] = depth - 1
            } else if (c in CLOSERS.values) open[c] = (open[c] ?: 0) + 1
            end++
        }
        while (end > start && text[end - 1] in TRAILING) end--
        return end
    }

    /**
     * Whether a cleaned paste may still be undone as far as the caret goes: it must be where the paste left it.
     * Checking only the text before the caret would let Undo rewrite an identical link elsewhere in the field,
     * so a position that is not known, then or now, means no Undo.
     */
    fun caretUnmoved(afterPaste: Int?, now: EditorEditState.Selection?): Boolean =
        afterPaste != null && now != null && now.collapsed && now.end == afterPaste
}
