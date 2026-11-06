package dev.localtype.keyboard

/**
 * Holds a logged field back until the dictionary and learned-pair loads have published, so its
 * header, offers and snapshot describe the same ready model. Pure state, main thread only.
 */
class LoggedFieldGate {
    /** A field wants the log but is waiting for a load. */
    var pending = false
        private set
    private var pendingRestart = false

    /** A field starts. True when it may open in the log now; false when it waits or does not log. */
    fun start(wantsLog: Boolean, dictionaryReady: Boolean, restart: Boolean, learnedReady: Boolean = true): Boolean {
        val ready = dictionaryReady && learnedReady
        pending = wantsLog && !ready
        pendingRestart = restart
        return wantsLog && ready
    }

    /** The field closed; a waiting open is dropped with it. */
    fun finish() {
        pending = false
    }

    /** A load published. The waiting field's restart flag, only when both loads are ready. */
    fun published(dictionaryReady: Boolean = true, learnedReady: Boolean = true): Boolean? {
        if (!pending || !dictionaryReady || !learnedReady) return null
        pending = false
        return pendingRestart
    }
}
