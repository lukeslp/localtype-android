package dev.localtype.keyboard


/**
 * The retained pairs, independent of Android and disk I/O. Rows are oldest first; only recording
 * a pair makes its previous word recent. The live model keeps its own additive counts until it
 * next rebuilds from this store, just as it did with the old preferences store's count ceiling.
 */
class LearnedPairState(entries: List<Triple<String, String, Int>> = emptyList()) {
    private val rows = LinkedHashMap<String, MutableMap<String, Int>>()

    init {
        entries.forEach { (previous, next, count) -> restore(previous, next, count) }
    }

    /** A detached snapshot; row order is part of the journal's compaction contract. */
    fun entries(): List<Triple<String, String, Int>> = rows.flatMap { (previous, followers) ->
        followers.entries.sortedBy { it.key }.map { (next, count) -> Triple(previous, next, count) }
    }

    fun record(previous: String, next: String) {
        require(validWord(previous) && validWord(next))
        val followers = touch(previous)
        val limit = followerLimit(previous)
        if (next !in followers && followers.size >= limit) {
            // A new follower at a full row is a chance for old habits to fade. Halving releases
            // singleton slots immediately; one weakest survivor goes if every old pair survives.
            val old = followers.entries.iterator()
            while (old.hasNext()) {
                val entry = old.next()
                val decayed = entry.value / 2
                if (decayed == 0) old.remove() else entry.setValue(decayed)
            }
            if (followers.size >= limit) evictWeakest(followers)
        }
        followers[next] = ((followers[next] ?: 0) + 1).coerceAtMost(MAX_COUNT)
    }

    fun remove(previous: String, next: String) {
        val followers = rows[previous] ?: return
        followers.remove(next)
        if (followers.isEmpty()) rows.remove(previous)
    }

    fun clear() = rows.clear()

    /** Absolute counts appear only in a compacted file or the one-time migration. */
    fun restore(previous: String, next: String, count: Int) {
        if (!validWord(previous) || !validWord(next) || count <= 0) return
        val followers = touch(previous)
        followers[next] = count.coerceAtMost(MAX_COUNT)
        if (followers.size > followerLimit(previous)) evictWeakest(followers)
    }

    /** Added words are a list the person keeps, not a row of habits, so their reserved row has its own bound. */
    private fun followerLimit(previous: String): Int = if (previous == AddedWords.CONTEXT) AddedWords.MAX_WORDS else MAX_FOLLOWERS

    private fun touch(previous: String): MutableMap<String, Int> {
        val followers = rows.remove(previous) ?: HashMap()
        rows[previous] = followers
        // Deliberate dictionary entries do not disappear when old typing contexts age out.
        if (rows.size > MAX_PREVIOUS_WORDS) rows.remove(rows.keys.first { it != AddedWords.CONTEXT })
        return followers
    }

    private fun evictWeakest(followers: MutableMap<String, Int>) {
        val weakest = followers.entries.minWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key },
        ) ?: return
        followers.remove(weakest.key)
    }

    companion object {
        /** The old persisted count ceiling; model replay separately retains its 12-count ceiling. */
        const val MAX_COUNT = 100

        /** More followers than the prediction strip can show, while bounding a common word's row. */
        const val MAX_FOLLOWERS = 64

        /** At most 65,536 pairs, including the sentence-start row; old unused contexts leave first. */
        const val MAX_PREVIOUS_WORDS = 1_024

        // Far above the IME's bounded word context, but finite so malformed lengths cannot allocate
        // arbitrary buffers. This is a byte bound, so all normal scripts and START round-trip.
        const val MAX_WORD_BYTES = 65_536
        private const val LEGACY_SEPARATOR = '\u001f'

        fun validWord(word: String): Boolean {
            val bytes = word.encodeToByteArray()
            return word.isNotEmpty() && bytes.size <= MAX_WORD_BYTES && bytes.decodeToString() == word
        }

        /**
         * Preferences had no row recency. Establish it lexically, retaining the strongest 64
         * followers (lexically first on equal counts) rather than decaying their existing counts.
         * The caller deletes the old file only after this state has been written successfully.
         */
        fun migrate(preferences: Map<String, *>): LearnedPairState {
            val entries = preferences.mapNotNull { (key, value) ->
                val separator = key.indexOf(LEGACY_SEPARATOR)
                val count = value as? Int ?: return@mapNotNull null
                if (separator <= 0 || separator >= key.lastIndex || count <= 0) return@mapNotNull null
                val previous = key.substring(0, separator)
                val next = key.substring(separator + 1)
                if (!validWord(previous) || !validWord(next)) return@mapNotNull null
                Triple(previous, next, count.coerceAtMost(MAX_COUNT))
            }.sortedWith(compareBy<Triple<String, String, Int>> { it.first }.thenBy { it.second })
            return LearnedPairState(entries)
        }
    }
}

/**
 * Append-only, self-framed binary records. Each frame is LTP1, a big-endian payload byte length,
 * its bitwise complement, the payload, then its CRC32. The payload is a one-byte operation followed
 * by two length-prefixed UTF-8 words, and an int count only for a compacted SET record. START is an
 * ordinary UTF-8 word. The complement detects a damaged length before it can hide a later frame;
 * a valid frame header makes the entire payload one record, even when its checksum fails.
 */
object LearnedPairJournal {
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
    private const val INCREMENT = 1
    private const val SET = 2
    private const val REMOVE = 3
    private const val MIN_PAYLOAD_BYTES = 11 // operation, two lengths and two nonempty UTF-8 words
    private const val MAX_PAYLOAD_BYTES = 13 + 2 * LearnedPairState.MAX_WORD_BYTES
    private const val HEADER_BYTES = 12
    private const val FRAME_OVERHEAD_BYTES = HEADER_BYTES + 4

    data class DecodeResult(val state: LearnedPairState, val damaged: Boolean, val records: Int)

    fun increment(previous: String, next: String): ByteArray = frame(INCREMENT, previous, next)

    fun remove(previous: String, next: String): ByteArray = frame(REMOVE, previous, next)

    /** One SET record per retained pair, preserving the oldest-to-newest previous-word order. */
    fun compact(entries: List<Triple<String, String, Int>>): ByteArray {
        val bytes = ByteWriter()
        entries.forEach { (previous, next, count) ->
            require(count in 1..LearnedPairState.MAX_COUNT)
            bytes.write(frame(SET, previous, next, count))
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): DecodeResult {
        val state = LearnedPairState()
        var offset = 0
        var damaged = false
        var records = 0
        while (offset < bytes.size) {
            if (!matchesMagic(bytes, offset) || bytes.size - offset < HEADER_BYTES) {
                damaged = true
                offset++
                continue
            }
            val size = ByteReader(bytes, offset + 4, 4).getInt()
            val inverse = ByteReader(bytes, offset + 8, 4).getInt()
            if (size !in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES || size.inv() != inverse) {
                damaged = true
                offset++
                continue
            }
            if (bytes.size - offset - FRAME_OVERHEAD_BYTES < size) {
                // Its checked header declares one final incomplete record. Ignore the whole tail,
                // including any byte pattern inside it that happens to look like another frame.
                damaged = true
                break
            }
            val payloadStart = offset + HEADER_BYTES
            val crc = crc32(bytes, payloadStart, size).toInt()
            val storedCrc = ByteReader(bytes, payloadStart + size, 4).getInt()
            if (crc != storedCrc) {
                damaged = true
                offset += size + FRAME_OVERHEAD_BYTES
                continue
            }
            if (decodePayload(bytes, payloadStart, size, state)) records++ else damaged = true
            offset += size + FRAME_OVERHEAD_BYTES
        }
        return DecodeResult(state, damaged, records)
    }

    private fun frame(operation: Int, previous: String, next: String, count: Int = 0): ByteArray {
        require(LearnedPairState.validWord(previous) && LearnedPairState.validWord(next))
        val payloadBytes = ByteWriter()
        payloadBytes.writeByte(operation)
        writeWord(payloadBytes, previous)
        writeWord(payloadBytes, next)
        if (operation == SET) payloadBytes.writeInt(count)
        val payload = payloadBytes.toByteArray()
        val frame = ByteWriter(payload.size + FRAME_OVERHEAD_BYTES)
        frame.write(MAGIC)
        frame.writeInt(payload.size)
        frame.writeInt(payload.size.inv())
        frame.write(payload)
        frame.writeInt(crc32(payload).toInt())
        return frame.toByteArray()
    }

    private fun writeWord(out: ByteWriter, word: String) {
        val bytes = word.encodeToByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun matchesMagic(bytes: ByteArray, offset: Int): Boolean = bytes.size - offset >= MAGIC.size &&
        MAGIC.indices.all { bytes[offset + it] == MAGIC[it] }

    private fun decodePayload(bytes: ByteArray, offset: Int, size: Int, state: LearnedPairState): Boolean = try {
        ByteReader(bytes, offset, size).let { payload ->
            val operation = payload.get().toInt() and 0xFF
            val previous = readWord(payload)
            val next = readWord(payload)
            val count = if (operation == SET) payload.getInt() else 0
            if (payload.remaining() != 0 || operation !in INCREMENT..REMOVE ||
                (operation == SET && count !in 1..LearnedPairState.MAX_COUNT)
            ) {
                false
            } else {
                when (operation) {
                    INCREMENT -> state.record(previous, next)
                    SET -> state.restore(previous, next, count)
                    REMOVE -> state.remove(previous, next)
                }
                true
            }
        }
    } catch (_: ByteReader.Underflow) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun readWord(input: ByteReader): String {
        val size = input.getInt()
        require(size in 1..LearnedPairState.MAX_WORD_BYTES && size <= input.remaining())
        val bytes = ByteArray(size)
        input.get(bytes)
        val word = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("invalid UTF-8")
        }
        require(LearnedPairState.validWord(word))
        return word
    }
}
