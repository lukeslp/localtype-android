package dev.localtype.keyboard

/**
 * What was copied in the last hour, newest first, at most [MAX] items, in memory only, so it never
 * outlives the keyboard process. An hour matches Android 13's own clipboard clear, and the keyboard
 * calls [prune] when the oldest copy's hour is up ([nextExpiryIn]), so none is held longer. The
 * keyboard reports every clipboard change: a copy it [keeps] through [copied], anything else
 * through [skipped], and an emptied clipboard through [cleared], which drops the copy that was on
 * it (a password manager clearing a password it didn't flag, say). Times are elapsed milliseconds.
 * Main thread only.
 */
class RecentCopies {
    private class Copy(val text: String, val copiedAt: Long)

    private var copies: List<Copy> = emptyList()

    /** The kept copy still on the clipboard, or null once something else replaced it. */
    private var onClipboard: String? = null

    /**
     * A copy [keeps] allowed. Copying the same text again keeps one entry, moved to the front with
     * a fresh hour, as Android restarts its own clear on every copy.
     */
    fun copied(text: String, now: Long) {
        copies = (listOf(Copy(text, now)) + live(now).filter { it.text != text }).take(MAX)
        onClipboard = text
    }

    /** The clipboard now holds something not kept (private, too long, an image); older copies stay. */
    fun skipped() {
        onClipboard = null
    }

    /** The clipboard was emptied: the copy that was on it goes too. */
    fun cleared() {
        val text = onClipboard ?: return
        copies = copies.filter { it.text != text }
        onClipboard = null
    }

    /** Drops copies whose hour is up. */
    fun prune(now: Long) {
        if (copies.any { !isLive(it, now) }) copies = live(now)
    }

    /** Copies younger than an hour, newest first. */
    fun items(now: Long): List<String> {
        prune(now)
        return copies.map { it.text }
    }

    /** Milliseconds until the oldest copy's hour is up, or null when nothing is held. */
    fun nextExpiryIn(now: Long): Long? =
        copies.minOfOrNull { it.copiedAt }?.let { (it + MAX_AGE_MS - now).coerceAtLeast(0L) }

    private fun isLive(copy: Copy, now: Long) = now - copy.copiedAt < MAX_AGE_MS

    private fun live(now: Long): List<Copy> = copies.filter { isLive(it, now) }

    companion object {
        const val MAX = 10
        const val MAX_AGE_MS = 60 * 60_000L

        /**
         * Whether a copy may be kept at all: never one the copying app [flaggedSensitive]
         * (EXTRA_IS_SENSITIVE), never one made while the keyboard's field was a password field or
         * didn't allow learning ([privateField]), and never blank or longer than the paste chip's limit.
         */
        fun keeps(text: String?, flaggedSensitive: Boolean, privateField: Boolean): Boolean =
            !flaggedSensitive && !privateField && text != null && SavedTexts.fits(text)
    }
}
