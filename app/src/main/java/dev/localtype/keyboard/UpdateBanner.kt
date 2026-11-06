package dev.localtype.keyboard

/**
 * When settings looks for a newer version on its own, and when the banner at the top of settings says one is out.
 * Settings checks only while it is open, at most every [CHECK_EVERY_MS], and only with Check for updates
 * automatically on; the keyboard itself never goes online for this.
 */
object UpdateBanner {
    const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    /** Due when there is no earlier check, the last one is [CHECK_EVERY_MS] old, or the clock went backwards. */
    fun due(lastCheckedAt: Long, now: Long): Boolean =
        lastCheckedAt <= 0L || now < lastCheckedAt || now - lastCheckedAt >= CHECK_EVERY_MS

    /**
     * The banner shows for a release newer than the installed one that this phone can install, unless the person
     * said Not now to that release or a newer one; the next release brings it back.
     */
    fun shows(installedCode: Long, latestCode: Int, installable: Boolean, dismissedCode: Int): Boolean =
        latestCode > installedCode && installable && latestCode > dismissedCode
}

/** The rows of Tips and gestures in settings: each string-array item is "what you do|what it does". */
object SettingsTips {
    fun split(item: String): Pair<String, String> {
        val bar = item.indexOf('|')
        require(bar > 0 && bar < item.length - 1 && item.indexOf('|', bar + 1) < 0) { "a tip is \"gesture|effect\": $item" }
        return item.substring(0, bar).trim() to item.substring(bar + 1).trim()
    }
}
