package dev.localtype.keyboard

/**
 * Keys pressed while an editor restart has retired the input connection.
 *
 * When an app calls `restartInput`, the keyboard keeps the old connection until the new one
 * starts, typically 6 to 20 ms later. The app drops text sent through the retired connection
 * while the keyboard's side still reports the command as sent, and the connection's reads come
 * back null. So the service holds a text key whose probe read is null, replays held keys in
 * order once the same editor's new connection starts, and drops them when a different editor
 * starts instead. If no restart arrives within [holdMs] of the last progress, the editor is
 * live but answers reads with null: the held keys go out through it after all, and that
 * editor is not probed again until a fresh session starts.
 *
 * Pure state, so the order, ownership and timing rules are testable without Android.
 */
class PendingKeys<T>(private val capacity: Int = 64, val holdMs: Long = 300) {
    enum class Hold { HELD, FULL }
    enum class Start { NONE, REPLAY, DROPPED }
    enum class Expiry { NONE, WAIT, SEND, DROP }

    private val keys = ArrayDeque<T>()
    private var owner: String? = null
    private var progressAt = 0L
    private var readless: String? = null

    val isEmpty: Boolean get() = keys.isEmpty()
    val size: Int get() = keys.size

    /** Whether a key typed in [identity] should be probed; false for an editor known to answer reads with null. */
    fun probes(identity: String?): Boolean = readless == null || readless != identity

    /** Queues [key] behind any already held; the first held key fixes the owning editor and starts the wait. */
    fun hold(key: T, identity: String?, nowMs: Long): Hold {
        if (keys.size >= capacity) return Hold.FULL
        if (keys.isEmpty()) {
            owner = identity
            progressAt = nowMs
        }
        keys.addLast(key)
        return Hold.HELD
    }

    /** Puts [key] back at the front: it met a retired connection partway through being sent, before anything changed. */
    fun holdFirst(key: T, identity: String?, nowMs: Long): Hold {
        if (keys.size >= capacity) return Hold.FULL
        if (keys.isEmpty()) {
            owner = identity
            progressAt = nowMs
        }
        keys.addFirst(key)
        return Hold.HELD
    }

    /**
     * An editor started. A restart of the editor that owns the held keys replays them; any other
     * start drops them, because they were typed for a session that is gone. A fresh, non-restart
     * session also forgets which editor answered reads with null.
     */
    fun started(identity: String?, restarting: Boolean): Start {
        if (!restarting) readless = null
        if (keys.isEmpty()) return Start.NONE
        if (restarting && identity == owner) return Start.REPLAY
        clear()
        return Start.DROPPED
    }

    /** The oldest held key, removed; null when none are held. */
    fun next(): T? = keys.removeFirstOrNull()

    /** A held key went out, so the wait starts again from [nowMs]. */
    fun progressed(nowMs: Long) {
        progressAt = nowMs
    }

    /** Milliseconds left before [expire] would act, or 0 when it can act now. */
    fun remainingMs(nowMs: Long): Long = (progressAt + holdMs - nowMs).coerceAtLeast(0)

    /**
     * No restart arrived in time. While the same editor is current the held keys are sent through
     * it and the editor is remembered as one whose reads are null; if another editor took over,
     * the keys are dropped.
     */
    fun expire(identity: String?, nowMs: Long): Expiry {
        if (keys.isEmpty()) return Expiry.NONE
        if (nowMs - progressAt < holdMs) return Expiry.WAIT
        if (identity == owner) {
            readless = identity
            return Expiry.SEND
        }
        clear()
        return Expiry.DROP
    }

    /** Drops every held key; returns how many there were. */
    fun clear(): Int {
        val dropped = keys.size
        keys.clear()
        owner = null
        return dropped
    }
}
