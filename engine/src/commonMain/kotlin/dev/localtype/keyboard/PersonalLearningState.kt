package dev.localtype.keyboard

enum class LearningOrigin { TYPED, EXPLICIT_KEEP, SUGGESTION_ACCEPT, VERIFIED_REPAIR }

data class PersonalFollower(
    val word: String,
    val observations: Int,
    val explicitObservations: Int,
    val lastObserved: Long,
    val contextLength: Int,
) {
    companion object {
        /**
         * One deliberate choice qualifies. A fully typed known word qualifies after one use when
         * at least two own words precede it, and after two uses behind a single word, where one
         * observation would let every follower of a common word claim the reserved slot.
         */
        fun qualifies(observations: Int, explicitObservations: Int, contextLength: Int): Boolean =
            observations > 0 && (explicitObservations > 0 || observations >= 2 || contextLength >= 2)
    }
}

/** Bounded, main-thread-owned evidence. Callers verify context and apply field/privacy gates. */
class PersonalLearningState(
    private val maxContexts: Int = 1024,
    private val maxFollowers: Int = 8,
    private val maxCount: Int = 100,
    private val maxUndoReceipts: Int = 64,
    /** One-letter words that may be followers ([LanguageProfile.oneLetterWords]); English's by default. */
    private val oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords,
) {
    data class Entry(
        val context: List<String>,
        val word: String,
        val observations: Int,
        val explicitObservations: Int,
        val lastObserved: Long,
    )

    private class Evidence(var count: Int, var explicit: Int, var observed: Long)
    private data class Delta(
        val context: List<String>, val word: String, val evidence: Evidence,
        val count: Int, val explicit: Int,
    )
    private val rows = LinkedHashMap<List<String>, MutableMap<String, Evidence>>()
    private val receipts = LinkedHashMap<Long, List<Delta>>()
    private var sequence = 0L
    private var receiptSequence = 0L

    init {
        require(maxContexts in 1..1024 && maxFollowers in 1..8 && maxCount in 1..100)
        require(maxUndoReceipts in 0..64)
    }

    fun record(context: List<String>, word: String, origin: LearningOrigin): Long? {
        val next = normalizedWord(word, offerable = true) ?: return null
        val tail = normalizedContext(context) ?: return null
        if (tail.isEmpty() || receiptSequence == Long.MAX_VALUE) return null
        val observed = if (sequence < Long.MAX_VALUE) ++sequence else sequence
        val explicit = origin == LearningOrigin.EXPLICIT_KEEP || origin == LearningOrigin.SUGGESTION_ACCEPT
        val deltas = (1..tail.size).map { length ->
            val key = tail.takeLast(length)
            val row = rows.remove(key) ?: HashMap()
            rows[key] = row // Observation order, never read order.
            if (rows.size > maxContexts) rows.remove(rows.keys.first())
            if (next !in row && row.size >= maxFollowers) evictWeakest(row)
            val evidence = row.getOrPut(next) { Evidence(0, 0, observed) }
            val added = if (evidence.count < maxCount) 1 else 0
            val addedExplicit = if (explicit && evidence.explicit < evidence.count + added) 1 else 0
            // Explicit provenance is a subset of counted observations, independently of total-count saturation.
            evidence.count += added
            evidence.explicit += addedExplicit
            evidence.observed = observed
            Delta(key, next, evidence, added, addedExplicit)
        }
        val receipt = ++receiptSequence
        if (maxUndoReceipts > 0) {
            receipts[receipt] = deltas
            if (receipts.size > maxUndoReceipts) receipts.remove(receipts.keys.first())
        }
        return receipt
    }

    /** Consumes a known receipt once, including receipts whose rows have since been evicted. */
    fun undo(receipt: Long): Boolean {
        val deltas = receipts.remove(receipt) ?: return false
        for (delta in deltas) {
            val row = rows[delta.context] ?: continue
            val evidence = row[delta.word] ?: continue
            if (evidence !== delta.evidence) continue
            evidence.count -= delta.count
            evidence.explicit = (evidence.explicit - delta.explicit).coerceIn(0, evidence.count)
            // Recency remains the latest observation; undo never rewinds later activity.
            if (evidence.count == 0) row.remove(delta.word)
            if (row.isEmpty()) rows.remove(delta.context)
        }
        return true
    }

    fun followers(context: List<String>, maxContext: Int = 3): List<PersonalFollower> {
        val tail = normalizedContext(context) ?: return emptyList()
        for (length in minOf(tail.size, maxContext.coerceIn(0, 3)) downTo 1) {
            val candidates = rows[tail.takeLast(length)].orEmpty().mapNotNull { (word, evidence) ->
                if (!PersonalFollower.qualifies(evidence.count, evidence.explicit, length)) null
                else PersonalFollower(word, evidence.count, evidence.explicit, evidence.observed, length)
            }.sortedWith(compareByDescending<PersonalFollower> { it.observations }
                .thenByDescending { it.lastObserved }.thenBy { it.word })
            if (candidates.isNotEmpty()) return candidates
        }
        return emptyList()
    }

    /** Detached snapshot in context observation order, with lexical follower order. */
    fun entries(): List<Entry> = rows.flatMap { (context, row) ->
        row.entries.sortedBy { it.key }.map { (word, evidence) ->
            Entry(context.toList(), word, evidence.count, evidence.explicit, evidence.observed)
        }
    }

    /** Replaces evidence, without inferring explicit intent or expanding stored context rows. */
    fun restore(entries: List<Entry>) {
        clear()
        // Oldest first ensures bounds retain the latest observed contexts and strongest followers.
        // Duplicate normalized keys use the latest entry, never sum snapshot counts.
        entries.sortedWith(compareBy<Entry> { it.lastObserved }.thenBy { it.observations }
            .thenBy { it.explicitObservations }.thenBy { it.context.joinToString(" ") }
            .thenBy { it.word }).forEach { entry ->
            if (entry.context.size !in 1..3 || entry.observations <= 0 || entry.lastObserved < 0) return@forEach
            val context = normalizedContext(entry.context) ?: return@forEach
            val word = normalizedWord(entry.word, offerable = true) ?: return@forEach
            val count = entry.observations.coerceAtMost(maxCount)
            val row = rows.remove(context) ?: HashMap()
            row[word] = Evidence(count, entry.explicitObservations.coerceIn(0, count), entry.lastObserved)
            rows[context] = row
            if (row.size > maxFollowers) evictWeakest(row)
            if (rows.size > maxContexts) rows.remove(rows.keys.first())
            sequence = maxOf(sequence, entry.lastObserved)
        }
    }

    /**
     * Takes in evidence from a copy made on another phone. Nothing held here is lost to it: a row held here and
     * there keeps the larger counts and this phone's recency, so reading the same copy twice changes nothing;
     * behind a context this phone already has followers for, the copy adds new followers only into the room left,
     * strongest first; and rows only the copy has come in as the oldest, the first to leave at a bound. The copy's
     * own recency is kept only as an order, never as numbers, so no file can move this phone's clock. Like
     * [restore], this ends every undo receipt.
     */
    fun merge(incoming: List<Entry>) {
        val existing = entries()
        val held = HashMap<List<String>, MutableSet<String>>()
        existing.forEach { held.getOrPut(it.context) { HashSet() }.add(it.word) }
        val copy = LinkedHashMap<Pair<List<String>, String>, Entry>()
        for (entry in incoming.sortedBy { it.lastObserved }) {
            // A longer context is not cut down to fit: it is not a row this store could have written.
            if (entry.context.size !in 1..3 || entry.observations <= 0) continue
            val context = normalizedContext(entry.context) ?: continue
            val word = normalizedWord(entry.word, offerable = true) ?: continue
            val count = entry.observations.coerceAtMost(maxCount)
            copy[context to word] = entry.copy(context = context, word = word, observations = count,
                explicitObservations = entry.explicitObservations.coerceIn(0, count))
        }
        val merged = ArrayList<Entry>()
        if (existing.isEmpty()) {
            // A new phone keeps the order the other one learned in, counted from one.
            copy.values.forEachIndexed { rank, entry -> merged += entry.copy(lastObserved = rank + 1L) }
        } else {
            val room = HashMap<List<String>, Int>()
            copy.values.filter { it.word !in held[it.context].orEmpty() }
                .sortedWith(compareByDescending<Entry> { it.observations }.thenBy { it.word })
                .forEach { entry ->
                    val left = room.getOrPut(entry.context) { maxFollowers - held[entry.context].orEmpty().size }
                    if (left <= 0) return@forEach
                    room[entry.context] = left - 1
                    merged += entry.copy(lastObserved = 0L)
                }
            existing.forEach { entry ->
                val other = copy[entry.context to entry.word]
                merged += if (other == null) entry else entry.copy(
                    observations = maxOf(entry.observations, other.observations),
                    explicitObservations = maxOf(entry.explicitObservations, other.explicitObservations),
                )
            }
        }
        restore(merged)
    }

    fun clear() {
        rows.clear()
        receipts.clear()
        // Sequences never reset: old receipts cannot alias future ones after clear/restore.
    }

    private fun evictWeakest(row: MutableMap<String, Evidence>) {
        val weakest = row.entries.minWithOrNull(compareBy<Map.Entry<String, Evidence>> { it.value.count }
            .thenBy { it.value.observed }.thenBy { it.key }) ?: return
        row.remove(weakest.key)
    }

    private fun normalizedContext(context: List<String>): List<String>? {
        val result = ArrayList<String>(3)
        for (word in context.takeLast(3)) result.add(normalizedWord(word, offerable = false) ?: return null)
        return result
    }

    private fun validSize(word: String): Boolean =
        word.length in 1..128 && word.encodeToByteArray().size <= 512

    private fun normalizedWord(word: String, offerable: Boolean): String? {
        if (!validSize(word)) return null
        val normalized = AdaptiveNgramModel.normalize(word)
        if (!validSize(normalized) || !WordRule.STORED.matches(normalized) || !LearnedPairState.validWord(normalized)) return null
        if (offerable && normalized.length < 2 && normalized !in oneLetterWords) return null
        return normalized
    }
}
