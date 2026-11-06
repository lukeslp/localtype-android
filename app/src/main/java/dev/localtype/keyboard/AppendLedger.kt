package dev.localtype.keyboard

/**
 * Plain appends sent since the editor last showed its text, so that a restart can resend the ones it never applied.
 *
 * A check before each key cannot close the race with `restartInput`: the app can retire the connection between the
 * keyboard's last read and its commit, and the commit then vanishes while reporting success. So every plain append
 * (a typed character, or a separator that replaces nothing) is recorded with the text before it and the text it should
 * produce, both as the last [window] characters before the cursor. A later read that sees the text again starts the
 * ledger over. When the same editor restarts, its new connection's text is compared with the ledger: matching the last
 * expected text means everything landed; matching the text *before* entry j means entries j onward never arrived and
 * are returned to be sent again. Anything else, or an ambiguous match, returns nothing, so a character that did land is
 * never sent twice. Any edit other than a plain append clears the ledger first.
 *
 * Pure state, so the matching rules are testable without Android.
 */
class AppendLedger(private val window: Int, private val capacity: Int = 32) {
    data class Entry(val text: String, val before: String, val after: String)

    private val entries = ArrayDeque<Entry>()
    private var owner: String? = null

    val size: Int get() = entries.size

    /**
     * Records [text] sent to [identity]'s editor. [before] is the text before the cursor read just before sending, or
     * null when that read failed; then the entry chains onto the last expected text, and with nothing to chain onto
     * (or with [fallback], a read taken earlier in the same key) it starts from [fallback] or is not recorded.
     */
    fun record(identity: String?, before: String?, text: String, fallback: String? = null) {
        if (text.isEmpty()) return
        if (identity != owner) {
            entries.clear()
            owner = identity
        }
        val base = when {
            before != null -> { entries.clear(); before.takeLast(window) }
            entries.isNotEmpty() -> entries.last().after
            fallback != null -> fallback.takeLast(window)
            else -> return
        }
        entries.addLast(Entry(text, base, (base + text).takeLast(window)))
        while (entries.size > capacity) entries.removeFirst()
    }

    /**
     * The same editor restarted and its text before the cursor now reads [text]. Returns the appends that never reached
     * it, oldest first, and empties the ledger either way.
     */
    fun missing(identity: String?, text: String?): List<String> {
        val now = text?.takeLast(window)
        val recorded = entries.toList()
        val ours = identity == owner
        clear()
        if (!ours || now == null || recorded.isEmpty() || now == recorded.last().after) return emptyList()
        val matches = recorded.indices.filter { recorded[it].before == now }
        // Two entries with the same starting text (a long run of one character) can't say which arrived.
        if (matches.size != 1) return emptyList()
        return recorded.drop(matches.single()).map { it.text }
    }

    fun clear() {
        entries.clear()
    }
}
