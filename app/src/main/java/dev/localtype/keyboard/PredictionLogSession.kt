package dev.localtype.keyboard

import java.util.Locale

/**
 * One strip refresh, built on the main thread at the end of refreshCandidates. [context] is the raw
 * text before the cursor (the service's window, prefix included); everything else is a plain value
 * the session reads off the worker thread.
 */
data class OfferEvent(
    val context: String,
    val prefix: String,
    val touchCount: Int,
    val revisited: Boolean,
    val mode: Char,
    val branch: Char,
    val shown: Boolean,
    val ranked: List<Pair<String, Int>>,
    val slots: List<String>,
    val src3: String,
    val modelCount: Int,
    val dict: Int,
    val us: Int,
    val rus: Int,
    val stripRevision: Int,
)

/**
 * A strip tap; [src] is computed on the device from live state, [prev] is what it passed to accept.
 * [verified] is false for the literal slot and its correction when the prefix they echo was not
 * touch-typed here (pasted or pre-filled text); the word then never reaches a row or the history.
 */
data class TapEvent(
    val contextBeforeWord: String,
    val prefix: String,
    val idx: Int,
    val word: String,
    val src: Char,
    val prev: String?,
    val stripRevision: Int,
    val verified: Boolean,
)

/**
 * A word finished by a separator: learnJustCompletedWord fired. [typedRaw] is the raw keys when
 * autocorrect rewrote the word, null otherwise; [typedVerified] says the caller checked the touch
 * count against the letters before the trace was cleared.
 */
data class TypedEvent(
    val contextBeforeSeparator: String,
    val typedRaw: String?,
    val committed: String,
    val sep: Char,
    val learned: Boolean,
    val typedVerified: Boolean,
    val prev: String?,
)

/** Caller verifies editor effects; the session verifies the original against retained closed rows. */
data class RepairEvent(
    val contextBeforeWord: String,
    val original: String,
    val replacement: String,
    val outcome: Char,
    val learned: Boolean = false,
    val prev: String? = null,
)

/**
 * The typing log's state machine: turns strip refreshes, taps and finished words into [LogRecord]
 * blocks handed to [sink] in file order. Pure Kotlin, single-threaded by contract (the writer hosts
 * it on one worker thread; the fixture generator drives it directly).
 *
 * A word instance is identified by the text before the word ([keyChars] of it); one instance is
 * open at a time and it is written lazily, as one block, when its outcome arrives or a new key
 * closes it. Nothing the person did not type or tap on this keyboard reaches a block: every offer
 * at p >= 1 must have a touch count equal to its prefix length, a typed word must have been verified
 * by the caller, and context words are stored only as the suffix of the current sentence that
 * matches this session's own history of committed words. A device mutation on an instance that is
 * not written leaves a text-free or verified-word [LogRecord.Mutation] instead.
 *
 * From 2026-10-13 a verified word that a mutation row prints enters that history as a written word does,
 * so the next word can name it; a word with no instance names the newest history word when the device
 * used exactly that; a word ended by a period keeps its instance until the keyboard's deferred learning
 * arrives; and re-entering the newest word aligns the text before it with the words before it in the
 * history. Before that, one unwritten word left the pair after it unreplayable (docs/prediction-log-spec.md).
 */
class PredictionLogSession(
    private val sink: (List<LogRecord>) -> Unit,
    private val keyChars: Int = 200,
    private val contextChars: Int = 160,
    private val windowChars: Int = 280,
) {
    /** Shares the device's prefix, sentence-start and previous-word rules exactly. */
    private val rules = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList())

    private enum class Taint { SHORT, FOREIGN }

    /** The open word instance; offers keep arrival order and merge per (p, prefix). */
    private class Instance(
        val key: String,
        val sentence: String,
        val cut: Boolean,
        val ss: Boolean,
        /** The device's previous word, always verified or empty (an unverified one makes the instance [unknown]). */
        val prev: String,
        val unknown: Boolean,
        val revisit: Boolean,
        /** The history entry of the word a revisit re-enters, when it was the newest: this instance's outcome replaces it. */
        val reentered: HistoryWord?,
    ) {
        val offers = LinkedHashMap<Pair<Int, String>, LogRecord.Offer>()
        var taint: Taint? = null
        var lastSeenPrefix = ""
        var lastSlots: List<String> = emptyList()
        var lastStripRevision = 0
        val writable: Boolean get() = taint == null && !unknown
        val onlyNextWord: Boolean get() = offers.keys.all { it.first == 0 }
    }

    /**
     * What the last closed instance looked like, for the stale-read and revisit rules: [committed]
     * and [typed] after an outcome, only [typed] (the last prefix offered) after an abandonment.
     */
    private class Closed(
        val key: String, val committed: String?, val typed: String?, val wid: Int?, val outcome: Boolean,
        /** The history entry its outcome wrote, for a revisit to align against; null when nothing entered the history. */
        val history: HistoryWord? = null,
    )

    /** A word a period just ended, with its text before that period: its outcome arrives only with the whitespace after it. */
    private class Parked(val instance: Instance, val word: String)

    private class Retained(val closed: Closed, val original: String, val prev: String, var history: HistoryWord?) {
        var applied: String? = null
        var undone = false
    }
    private class HistoryWord(var word: String)
    private val retained = ArrayDeque<Retained>()

    private var fieldOpen = false
    private var fieldSeq = 0
    private var open: Instance? = null
    private var parked: Parked? = null
    private var lastClosed: Closed? = null
    private var lastWid = 0
    private val history = ArrayDeque<HistoryWord>()

    private var dup = 0
    private var stale = 0
    private var single = 0
    private var unverifiedShort = 0
    private var unverifiedForeign = 0
    private var unknown = 0
    private var revisit = 0
    private var x = 0

    /** X rows written with an empty word since the last [reset]: mutations the file cannot replay. */
    var hiddenMutations = 0
        private set

    /** A field start that passed the gate; a field still open is ended first. */
    fun fieldStart(seq: Int, restart: Boolean, autocorrect: Boolean, profanity: Boolean, model: Int, emoji: Boolean, neuralSha256: String? = null) {
        if (fieldOpen) fieldEnd()
        if (!restart) {
            history.clear()
            retained.clear()
            lastClosed = null
        }
        fieldOpen = true
        fieldSeq = seq
        dup = 0; stale = 0; single = 0; unverifiedShort = 0; unverifiedForeign = 0; unknown = 0; revisit = 0; x = 0
        sink(listOf(LogRecord.Field(seq, restart, autocorrect, profanity, model, emoji, neuralSha256)))
    }

    /** Closes the open instance as A/F and writes the field's counters. */
    fun fieldEnd() {
        if (!fieldOpen) return
        flushParked()
        open?.let { close(it, 'F') }
        fieldOpen = false
        sink(listOf(LogRecord.FieldEnd(fieldSeq, dup, stale, single, unverifiedShort, unverifiedForeign, unknown, revisit, x)))
    }

    fun offer(e: OfferEvent) {
        if (!fieldOpen) return
        val contextBeforeWord = e.context.dropLast(e.prefix.length)
        val key = contextBeforeWord.takeLast(keyChars)
        val p = e.prefix.length
        var instance = open
        // A parked word waits only through refreshes of the empty prefix right after its period.
        if (parked != null && (p > 0 || instance?.key != key)) flushParked()
        if (instance != null && instance.key != key) {
            val ended = periodWord(instance, e, contextBeforeWord)
            if (ended != null) {
                parked = Parked(instance, ended)
                open = null
            } else close(instance, 'N')
            instance = null
        }
        if (instance == null) {
            var revisiting = false
            var reentered: HistoryWord? = null
            val closed = lastClosed
            if (closed != null && closed.key == key) {
                val prefix = normalize(e.prefix)
                // The offer shows the closed word again: a lagging read, or the person backed into it.
                // An empty prefix is neither (a field cleared after Send arrives that way).
                val echo = prefix.isNotEmpty() && (closed.committed?.startsWith(prefix) == true || prefix == closed.typed)
                if (echo && !e.revisited && closed.committed != null) {
                    // The editor still reported the state from before the commit.
                    stale++
                    return
                }
                if (echo && e.revisited) {
                    revisiting = true
                    reentered = closed.history
                    revisit++
                    // Text-free, and written now so a later drop of the revisit instance keeps it.
                    if (closed.outcome && closed.wid != null) sink(listOf(LogRecord.Revisit(closed.wid)))
                }
            }
            instance = openInstance(e, contextBeforeWord, key, revisiting, reentered)
            open = instance
        }
        if (p >= 1 && e.touchCount != p && instance.taint == null) {
            instance.taint = if (e.touchCount > p) Taint.SHORT else Taint.FOREIGN
        }
        instance.lastSeenPrefix = e.prefix
        instance.lastSlots = e.slots
        instance.lastStripRevision = e.stripRevision
        val id = p to e.prefix
        val stored = instance.offers[id]
        val fresh = LogRecord.Offer(0, p, e.prefix, e.mode, e.branch, e.shown, e.ranked, e.slots, e.src3, e.modelCount, e.dict, e.us, e.rus)
        instance.offers[id] = when {
            stored == null -> fresh
            // The dictionary landed between two refreshes: the first ranked list was not the ranker's.
            stored.dict == 0 && e.dict > 0 -> { dup++; fresh }
            else -> { dup++; stored.copy(shown = e.shown, slots = e.slots, src3 = e.src3, model = e.modelCount) }
        }
    }

    fun tap(e: TapEvent) {
        if (e.src == 'E') return tapEmoji(e.idx)
        flushParked()
        val key = e.contextBeforeWord.takeLast(keyChars)
        val instance = open?.takeIf { it.key == key }
        // Read before the tapped word itself enters the history.
        val orphanPrev = if (instance == null) orphanPrev(e.prev) else ""
        // A verified strip word enters the history whatever happens to the instance; an unverified
        // one (the editor's own prefix, or a correction of it) is a hidden mutation.
        val remembered = if (e.verified) remember(e.word, instance?.reentered) else null
        val word = if (e.verified) e.word else ""
        if (instance == null) return mutation(word, orphanPrev)
        if (!e.verified && instance.taint == null) instance.taint = Taint.FOREIGN
        val src = if (e.stripRevision != instance.lastStripRevision) 'X' else e.src
        val ps = normalize(e.prev.orEmpty()) == instance.prev
        val committed = normalize(e.word)
        if (instance.writable) {
            val wid = write(instance) { LogRecord.Tap(it, e.prefix.length, e.idx, e.word, src, ps) }
            lastClosed = Closed(key, committed, null, wid, outcome = true, history = remembered)
            retain(lastClosed!!, e.word, instance.prev, remembered)
        } else {
            countDrop(instance)
            mutation(word, mutationPrev(instance, e.prev))
            lastClosed = Closed(key, committed, null, null, outcome = true, history = remembered)
        }
        open = null
    }

    /** An emoji tap replaces the word and mutates nothing, so a dropped instance leaves no X row. */
    fun tapEmoji(idx: Int) {
        flushParked()
        val instance = open ?: return
        val word = instance.lastSlots.getOrNull(idx).orEmpty()
        if (instance.writable) {
            val wid = write(instance) { LogRecord.Tap(it, instance.lastSeenPrefix.length, idx, word, 'E', true) }
            lastClosed = Closed(instance.key, null, null, wid, outcome = true)
        } else {
            countDrop(instance)
            lastClosed = Closed(instance.key, null, null, null, outcome = true)
        }
        open = null
    }

    fun typed(e: TypedEvent) {
        val key = e.contextBeforeSeparator.dropLast(e.committed.length).takeLast(keyChars)
        val waiting = parked
        if (waiting != null) {
            parked = null
            // The deferred learning of the word a period ended: its instance was kept for exactly this row.
            if (waiting.instance.key == key && normalize(e.typedRaw ?: e.committed) == normalize(waiting.word)) {
                // The empty-prefix offer after the period was ranked before this learning, as before a learned FIX.
                open?.let { close(it, 'N') }
                return finish(waiting.instance, e, key)
            }
            abandon(waiting.instance, 'N')
        }
        val instance = open
        if (instance == null || instance.key != key) {
            if (e.learned) {
                val word = if (e.typedVerified) e.committed else ""
                // Read before the word itself enters the history.
                val prev = orphanPrev(e.prev)
                // What the row prints, the next word may name.
                if (word.isNotEmpty()) remember(word)
                mutation(word, prev)
            }
            return
        }
        finish(instance, e, key)
        open = null
    }

    /** The typed outcome of [instance], open or parked: its block when it is written, a counter and any mutation otherwise. */
    private fun finish(instance: Instance, e: TypedEvent, key: String) {
        val committed = normalize(e.committed)
        val typed = e.typedRaw ?: e.committed
        if (!e.typedVerified && instance.taint == null) instance.taint = Taint.FOREIGN
        if (instance.writable) {
            val remembered = remember(e.committed, instance.reentered)
            val ps = normalize(e.prev.orEmpty()) == instance.prev
            val wid = write(instance) { LogRecord.Typed(it, typed, e.committed, e.sep, e.typedRaw != null, e.learned, ps) }
            lastClosed = Closed(key, committed, normalize(typed), wid, outcome = true, history = remembered)
            retain(lastClosed!!, typed, instance.prev, remembered)
        } else {
            // The word itself is verified whenever the caller verified it. It enters the history when its
            // instance was clean, and also when the X row below prints it: the file then shows the word either way.
            val printed = e.learned && e.typedVerified
            val remembered = if (instance.taint == null || printed) remember(e.committed, instance.reentered) else null
            countDrop(instance)
            if (e.learned) mutation(if (e.typedVerified) e.committed else "", mutationPrev(instance, e.prev))
            lastClosed = Closed(key, committed, normalize(typed), null, outcome = true, history = remembered)
        }
    }

    /**
     * Writes the model's tables as one SNAP block, [MAX_SNAPSHOT_ROWS] at most (then `-partial`).
     * Returns the hash of a complete block, so the caller can pass it back as [lastHash]: when the
     * fresh build hashes the same, a rowless `SNAP same` is written instead. Null after a partial one.
     */
    fun snapshot(
        reason: String,
        unigrams: Map<String, Int>,
        bigrams: List<Triple<String, String, Int>>,
        lastHash: String? = null,
    ): String? {
        retained.clear()
        val rows = ArrayList<LogRecord>(unigrams.size + bigrams.size)
        unigrams.entries.sortedBy { it.key }.forEach { (word, count) -> rows += LogRecord.Unigram(word, count) }
        bigrams.sortedWith(compareBy({ it.first }, { it.second })).forEach { (prev, next, count) -> rows += LogRecord.Bigram(prev, next, count) }
        val partial = rows.size > MAX_SNAPSHOT_ROWS
        val hash = if (partial) null else PredictionLogCodec.blockHash(rows.map(PredictionLogCodec::encode))
        if (hash != null && hash == lastHash) {
            sink(listOf(LogRecord.Snapshot(PredictionLogCodec.SNAP_SAME, 0), LogRecord.SnapshotEnd))
            return hash
        }
        val written = if (partial) rows.subList(0, MAX_SNAPSHOT_ROWS) else rows
        val block = ArrayList<LogRecord>(written.size + 2)
        block += LogRecord.Snapshot(if (partial) reason + PredictionLogCodec.SNAP_PARTIAL_SUFFIX else reason, written.size)
        block += written
        block += LogRecord.SnapshotEnd
        sink(block)
        return hash
    }

    /** Forgets everything: the open field and instance, the word history, the counters and the wid sequence. */
    fun reset() {
        fieldOpen = false
        fieldSeq = 0
        open = null
        parked = null
        lastClosed = null
        lastWid = 0
        history.clear()
        retained.clear()
        hiddenMutations = 0
        dup = 0; stale = 0; single = 0; unverifiedShort = 0; unverifiedForeign = 0; unknown = 0; revisit = 0; x = 0
    }

    // region instance lifecycle

    private fun openInstance(e: OfferEvent, contextBeforeWord: String, key: String, revisiting: Boolean, reentered: HistoryWord?): Instance {
        val ss = rules.isSentenceStart(e.context)
        val devicePrev = rules.lastCompletedWord(e.context)
        val boundary = contextBeforeWord.lastIndexOfAny(SENTENCE_ENDS)
        val current = if (boundary < 0) contextBeforeWord else contextBeforeWord.substring(boundary + 1)
        val words = WORD_REGEX.findAll(current).toList()
        // A revisit re-enters the newest history word, which is not part of the text before it.
        val own = reentered?.takeIf { history.lastOrNull() === it }
        val skip = if (own != null) 1 else 0
        // The longest run of words at the end of the sentence that this session saw committed.
        var matched = 0
        while (matched < words.size && matched + skip < history.size &&
            normalize(words[words.size - 1 - matched].value) == history[history.size - 1 - skip - matched].word
        ) matched++
        var tail = if (matched > 0) current.substring(words[words.size - matched].range.first)
        else current.substring(words.lastOrNull()?.range?.let { it.last + 1 } ?: 0).takeLast(TRAILING_CHARS)
        var cut = boundary < 0 && e.context.length >= windowChars
        if (tail.length > contextChars) {
            val from = tail.length - contextChars
            val start = WORD_REGEX.findAll(tail).firstOrNull { it.range.first >= from }?.range?.first ?: from
            tail = tail.substring(start)
            cut = true
        }
        // Only the words were checked against the history: digits, symbols and emoji between or after
        // them (a pasted code, a pre-filled number) become one placeholder that resets context the same way.
        tail = tail.replace(UNVERIFIED_RUN, "0")
        // An unverified previous word (pre-filled text, a paste) means the device saw context the file cannot show.
        val unknown = devicePrev != null && matched == 0
        return Instance(key, tail, cut, ss, devicePrev.orEmpty(), unknown, revisiting, own)
    }

    /**
     * The word a just-typed period ended, when this refresh is the empty prefix right after it: the text
     * before the new word is [instance]'s own, then a word that continues its last prefix, then the period.
     * The keyboard defers that word's learning until whitespace follows (the next letters may be a domain),
     * so the instance waits for it instead of closing as abandoned.
     */
    private fun periodWord(instance: Instance, e: OfferEvent, contextBeforeWord: String): String? {
        if (e.prefix.isNotEmpty() || !contextBeforeWord.endsWith('.')) return null
        val body = contextBeforeWord.dropLast(1)
        val word = rules.activePrefix(body)
        if (word.isEmpty() || !word.startsWith(instance.lastSeenPrefix)) return null
        return word.takeIf { body.dropLast(it.length).takeLast(keyChars) == instance.key }
    }

    /** A parked word whose outcome never came closes as its period's new key would have closed it. */
    private fun flushParked() {
        val waiting = parked ?: return
        parked = null
        abandon(waiting.instance, 'N')
    }

    /**
     * Closes [instance] without an outcome: A/N or A/F when it is written, a counter otherwise. An
     * instance that only ever had the p=0 offer (the refresh right after every commit) is merely
     * counted and leaves the last outcome close in place, so backing into that word is still a revisit.
     */
    private fun close(instance: Instance, why: Char) {
        abandon(instance, why)
        open = null
    }

    private fun abandon(instance: Instance, why: Char) {
        var wid: Int? = null
        when {
            !instance.writable -> countDrop(instance)
            instance.onlyNextWord -> single++
            else -> wid = write(instance) { LogRecord.Abandon(it, why) }
        }
        if (wid != null || !instance.onlyNextWord) lastClosed = Closed(instance.key, null, normalize(instance.lastSeenPrefix), wid, outcome = false)
    }

    private fun write(instance: Instance, outcome: (Int) -> LogRecord): Int {
        val wid = ++lastWid
        val block = ArrayList<LogRecord>(instance.offers.size + 2)
        block += LogRecord.Word(wid, fieldSeq, instance.sentence, instance.cut, instance.ss, instance.prev, instance.revisit)
        instance.offers.values.forEach { block += it.copy(wid = wid) }
        block += outcome(wid)
        sink(block)
        return wid
    }

    private fun countDrop(instance: Instance) {
        when (instance.taint) {
            Taint.SHORT -> unverifiedShort++
            Taint.FOREIGN -> unverifiedForeign++
            null -> if (instance.unknown) unknown++
        }
    }

    /** A device mutation with no instance to carry it; an empty [word] is a hidden one. */
    private fun mutation(word: String, prev: String) {
        x++
        if (word.isEmpty()) hiddenMutations++
        sink(listOf(LogRecord.Mutation(fieldSeq, word, prev)))
    }

    /**
     * The previous word an X row may carry: [AdaptiveNgramModel.START] when the device credited a
     * sentence start (text-free, so always safe), the verified word when the device used exactly
     * that, and empty when what it used is unknown.
     */
    private fun mutationPrev(instance: Instance, devicePrev: String?): String = when {
        instance.unknown -> ""
        devicePrev == null -> AdaptiveNgramModel.START
        normalize(devicePrev) == instance.prev -> instance.prev
        else -> ""
    }

    /**
     * The previous word an X row with no open instance may carry: [AdaptiveNgramModel.START] when the
     * device credited a sentence start, the newest word of the history when the device used exactly
     * that word, and empty otherwise. There is no instance to align the text against, so only the one
     * word this session last saw committed can be named.
     */
    private fun orphanPrev(devicePrev: String?): String = when {
        devicePrev == null -> AdaptiveNgramModel.START
        history.lastOrNull()?.word == normalize(devicePrev) -> history.last().word
        else -> ""
    }

    /** A committed word enters the history; the outcome of a revisit replaces the entry it re-entered ([reentered]). */
    private fun remember(word: String, reentered: HistoryWord? = null): HistoryWord? {
        val normalized = WORD_REGEX.find(word)?.value?.let(::normalize) ?: return null
        if (reentered != null && history.lastOrNull() === reentered) {
            // Earlier repair rows of this word no longer speak for the entry.
            retained.forEach { if (it.history === reentered) it.history = null }
            reentered.word = normalized
            return reentered
        }
        val row = HistoryWord(normalized)
        history.addLast(row)
        if (history.size > HISTORY_WORDS) history.removeFirst()
        return row
    }

    private fun retain(closed: Closed, original: String, prev: String, history: HistoryWord?) {
        if (!PredictionLogCodec.repairWord(original)) return
        retained.addLast(Retained(closed, original, prev, history))
        if (retained.size > HISTORY_WORDS) retained.removeFirst()
    }

    fun repair(event: RepairEvent) {
        val e = event
        if (!fieldOpen) return
        val key = e.contextBeforeWord.takeLast(keyChars)
        val row = retained.lastOrNull { it.closed.key == key && normalize(it.original) == normalize(e.original) }
        val valid = row != null && PredictionLogCodec.repairWord(e.replacement) && e.outcome in "OPJU" &&
            (!e.learned || e.outcome == 'P') && when (e.outcome) {
                'P' -> row.applied == null && !row.undone
                'U' -> row.applied == normalize(e.replacement) && !row.undone
                else -> row.applied == null && !row.undone
            }
        if (!valid) {
            // A learned effect without verified provenance cannot export either raw argument.
            if (e.learned) {
                flushParked()
                open?.let { close(it, 'N') }
                mutation("", "")
            }
            return
        }
        // Buffered offers were computed before this learning. Flush them before the FIX record.
        if (e.learned) {
            flushParked()
            open?.let { close(it, 'N') }
        }
        val prev = when {
            e.prev == null -> AdaptiveNgramModel.START
            normalize(e.prev) == row.prev && PredictionLogCodec.repairWord(row.prev) -> row.prev
            else -> ""
        }
        sink(listOf(LogRecord.Repair(row.closed.wid!!, row.original, e.replacement, e.outcome, e.learned, prev)))
        when (e.outcome) {
            'P' -> { row.applied = normalize(e.replacement); row.history?.word = normalize(e.replacement) }
            'U' -> { row.undone = true; row.history?.word = normalize(row.original) }
        }
    }

    // endregion

    companion object {
        const val MAX_SNAPSHOT_ROWS = 50_000
        const val HISTORY_WORDS = 64

        /** Kept from the sentence when no word before the instance is verified. */
        private const val TRAILING_CHARS = 16
        private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '\n')

        // The same tokens and case rule as AdaptiveNgramModel, so the harness recomputes what the device saw.
        private val WORD_REGEX = Regex("[\\p{L}][\\p{L}'’-]*")
        /** What a stored tail may not carry verbatim: anything but letters, spaces, apostrophes, hyphens and plain punctuation. */
        private val UNVERIFIED_RUN = Regex("[^\\p{L}\\s'’,;:\"()-]+")
        private fun normalize(word: String) = word.replace('’', '\'').lowercase(Locale.ROOT)
    }
}
