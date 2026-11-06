package dev.localtype.keyboard

import java.util.Locale

/**
 * Remembers what the on-device model predicted after a given run of words, so the slow model runs
 * once per context instead of once per pause. While a word is being typed, cached next words are
 * filtered by the prefix, which needs no model call at all.
 *
 * Entries are keyed by the last few completed words, backing off to the last word alone.
 */
class ModelSuggestionCache(
    private val store: Store = NoOpStore,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Store {
        fun load(): Map<String, Entry>
        fun save(key: String, entry: Entry)
        fun remove(key: String)
        fun clear()
    }

    data class Entry(val words: List<String>, val storedAt: Long)

    private object NoOpStore : Store {
        override fun load() = emptyMap<String, Entry>()
        override fun save(key: String, entry: Entry) = Unit
        override fun remove(key: String) = Unit
        override fun clear() = Unit
    }

    /** Access-ordered, so iteration starts with the least recently used entry. */
    private val entries = LinkedHashMap<String, Entry>(capacity, 0.75f, true)
    // An unpersisted answer must never ride along when a later safe answer merges a back-off key.
    private val persisted = HashMap<String, Entry>()

    init {
        store.load().entries.sortedBy { it.value.storedAt }.takeLast(capacity).forEach {
            entries[it.key] = it.value
            persisted[it.key] = it.value
        }
    }

    val size: Int get() = entries.size

    /** Cached next words for the text before the cursor, filtered by the word being typed. */
    fun lookup(beforeCursor: String, prefix: String): List<String>? {
        val keys = keysFor(beforeCursor.dropLast(prefix.length))
        val hit = keys.firstNotNullOfOrNull { entries[it] } ?: return null
        val typed = prefix.replace('’', '\'').lowercase(Locale.ROOT)
        return hit.words.filter { typed.isEmpty() || (it.startsWith(typed) && it != typed) }
    }

    fun hasEntryFor(beforeCursor: String, prefix: String): Boolean =
        keysFor(beforeCursor.dropLast(prefix.length)).firstOrNull()?.let { entries.containsKey(it) } == true

    /**
     * Stores [words] for the full context key and merges them into the shorter back-off key.
     * [persist] is false in fields that must not be remembered.
     */
    fun put(contextBeforeWord: String, words: List<String>, persist: Boolean) {
        if (words.isEmpty()) return
        val keys = keysFor(contextBeforeWord)
        if (keys.isEmpty()) return
        val now = clock()
        keys.forEachIndexed { index, key ->
            val merged = if (index == 0) words else (words + entries[key]?.words.orEmpty()).distinct().take(MAX_WORDS)
            val entry = Entry(merged.take(MAX_WORDS), now)
            entries[key] = entry
            if (persist) {
                val safeWords = if (index == 0) words else (words + persisted[key]?.words.orEmpty()).distinct().take(MAX_WORDS)
                val safeEntry = Entry(safeWords.take(MAX_WORDS), now)
                persisted[key] = safeEntry
                store.save(key, safeEntry)
            }
        }
        while (entries.size > capacity) {
            val eldest = entries.keys.first()
            entries.remove(eldest)
            persisted.remove(eldest)
            store.remove(eldest)
        }
    }

    fun clear() {
        entries.clear()
        persisted.clear()
        store.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 400
        const val MAX_WORDS = 8
        /** How many completed words make the fullest key. */
        const val CONTEXT_WORDS = 3
        private val WORD = Regex("[\\p{L}][\\p{L}'’-]*")

        /**
         * Keys from most to least specific, for example "see you" then "you". A sentence start or
         * an empty field gives no keys, so nothing is cached or requested there.
         */
        fun keysFor(contextBeforeWord: String): List<String> {
            val tail = contextBeforeWord.trimEnd(' ', '\t')
            // No key at a sentence start: the next word there should come from starters, not a model guess.
            if (tail.isEmpty() || tail.last() in ".!?\n") return emptyList()
            if (!tail.last().isLetter()) return emptyList()
            val sentence = tail.substring(tail.indexOfLast { it in ".!?\n" } + 1)
            val words = WORD.findAll(sentence).map { it.value.replace('’', '\'').lowercase(Locale.ROOT) }.toList()
            if (words.isEmpty()) return emptyList()
            val full = words.takeLast(CONTEXT_WORDS).joinToString(" ")
            val last = words.last()
            return if (full == last) listOf(last) else listOf(full, last)
        }
    }
}

/**
 * Decides when the slow model may run: one request at a time, a cached readiness check, and a
 * cool-down after repeated failures so a broken model stops costing battery and latency.
 */
class ModelRequestGate(private val clock: () -> Long = System::currentTimeMillis) {
    private var inFlight = false
    private var consecutiveFailures = 0
    private var coolDownUntil = 0L
    private var lastStatus: FoundationModelStatus? = null
    private var statusCheckedAt = 0L

    /** Smoothed model latency in milliseconds, or null before the first success. */
    var averageLatencyMs: Long? = null
        private set

    fun canRequest(): Boolean = !inFlight && clock() >= coolDownUntil

    /** A recent readiness answer, so AICore isn't asked over IPC on every pause. */
    fun cachedStatus(): FoundationModelStatus? =
        lastStatus?.takeIf { clock() - statusCheckedAt < STATUS_TTL_MS }

    /** Only READY is cached; anything else (like a model still loading) is asked again next pause. */
    fun recordStatus(status: FoundationModelStatus) {
        lastStatus = status.takeIf { it == FoundationModelStatus.READY }
        statusCheckedAt = clock()
    }

    fun begin(): Long {
        inFlight = true
        return clock()
    }

    fun succeed(startedAt: Long) {
        inFlight = false
        consecutiveFailures = 0
        val latency = clock() - startedAt
        averageLatencyMs = averageLatencyMs?.let { (it * 3 + latency) / 4 } ?: latency
    }

    /** Empty output, a timeout, or an exception. */
    fun fail() {
        inFlight = false
        consecutiveFailures++
        lastStatus = null
        if (consecutiveFailures >= FAILURES_BEFORE_COOL_DOWN) {
            coolDownUntil = clock() + COOL_DOWN_MS
            consecutiveFailures = 0
        }
    }

    /** Waits longer before asking a slow model, so fast typists don't queue work they'll outrun. */
    fun idleDelayMs(): Long = when (val latency = averageLatencyMs) {
        null -> BASE_IDLE_MS
        else -> (BASE_IDLE_MS + latency / 4).coerceAtMost(MAX_IDLE_MS)
    }

    companion object {
        const val BASE_IDLE_MS = 350L
        const val MAX_IDLE_MS = 900L
        const val STATUS_TTL_MS = 30_000L
        const val FAILURES_BEFORE_COOL_DOWN = 3
        const val COOL_DOWN_MS = 5 * 60_000L
        /**
         * Nothing waits on a request (answers only fill the cache), so this only catches a stuck
         * call. A Pixel 10 GPU took about 4 s per generation, which a 4 s limit threw away.
         */
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

/** Model words first (they carry more context), then instant local predictions, without repeats. */
internal fun mergeCandidates(modelWords: List<String>, localWords: List<String>, limit: Int): List<String> =
    (modelWords + localWords).distinctBy { it.lowercase(Locale.ROOT) }.take(limit)
