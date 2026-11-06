package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

class LearnedPairJournalTest {
    private val codec = LearnedPairJournal

    @Test
    fun `increments round trip sentence starts apostrophes and Unicode`() {
        val bytes = join(
            codec.increment(AdaptiveNgramModel.START, "mañana"),
            codec.increment("mañana", "don't"),
            codec.increment("don't", "東京"),
            codec.increment("mañana", "don't"),
            codec.increment("東京", "𝐡𝐞𝐥𝐥𝐨"),
        )
        val result = codec.decode(bytes)
        assertFalse(result.damaged)
        assertEquals(5, result.records)
        assertEquals(
            listOf(
                Triple(AdaptiveNgramModel.START, "mañana", 1),
                Triple("don't", "東京", 1),
                Triple("mañana", "don't", 2),
                Triple("東京", "𝐡𝐞𝐥𝐥𝐨", 1),
            ),
            result.state.entries(),
        )
    }

    @Test
    fun `empty file is a clean empty state`() {
        val result = codec.decode(byteArrayOf())
        assertTrue(result.state.entries().isEmpty())
        assertFalse(result.damaged)
        assertEquals(0, result.records)
        assertTrue(codec.compact(emptyList()).isEmpty())
    }

    @Test
    fun `remove persists and a later increment starts again at one`() {
        val bytes = join(
            codec.increment("thanks", "again"),
            codec.increment("thanks", "again"),
            codec.increment("see", "you"),
            codec.remove("thanks", "again"),
            codec.increment("thanks", "again"),
        )
        val result = codec.decode(bytes)
        assertEquals(listOf(Triple("see", "you", 1), Triple("thanks", "again", 1)), result.state.entries())
        assertFalse(result.damaged)
        assertEquals(5, result.records)
    }

    @Test
    fun `any truncated final frame keeps all completed records`() {
        val first = codec.increment("first", "kept")
        val last = codec.increment("torn", "lost")
        for (cut in 1 until last.size) {
            val result = codec.decode(first + last.copyOf(cut))
            assertEquals("cut at $cut", listOf(Triple("first", "kept", 1)), result.state.entries())
            assertTrue("cut at $cut", result.damaged)
            assertEquals(1, result.records)
        }
        assertFalse(codec.decode(first + last).damaged)
    }

    @Test
    fun `a corrupt payload is skipped and later records survive`() {
        val damaged = codec.increment("bad", "lost").also { it[17] = (it[17].toInt() xor 1).toByte() }
        val result = codec.decode(join(codec.increment("first", "kept"), damaged, codec.increment("last", "kept")))
        assertTrue(result.damaged)
        assertEquals(listOf(Triple("first", "kept", 1), Triple("last", "kept", 1)), result.state.entries())
        assertEquals(2, result.records)
    }

    @Test
    fun `a corrupt length cannot swallow a later complete record`() {
        for (length in listOf(-1, 0, 12, Int.MAX_VALUE, 100_000)) {
            val damaged = codec.increment("bad", "lost").also { ByteBuffer.wrap(it, 4, 4).putInt(length) }
            val result = codec.decode(damaged + codec.increment("last", "kept"))
            assertTrue("length $length", result.damaged)
            assertEquals("length $length", listOf(Triple("last", "kept", 1)), result.state.entries())
        }
    }

    @Test
    fun `junk between records is skipped`() {
        val result = codec.decode(join(
            codec.increment("first", "kept"),
            byteArrayOf(0, 1, 2, 3, 'L'.code.toByte(), 'T'.code.toByte()),
            codec.increment("last", "kept"),
        ))
        assertTrue(result.damaged)
        assertEquals(2, result.records)
        assertEquals(2, result.state.entries().size)
    }

    @Test
    fun `a corrupt framed payload never learns an embedded valid frame`() {
        val embedded = codec.increment("hidden", "lost")
        val damaged = ByteBuffer.allocate(embedded.size + 16)
            .put(byteArrayOf('L'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte()))
            .putInt(embedded.size).putInt(embedded.size.inv()).put(embedded)
            .putInt(CRC32().also { it.update(embedded) }.value.toInt() xor 1).array()
        val result = codec.decode(damaged + codec.increment("last", "kept"))
        assertTrue(result.damaged)
        assertEquals(1, result.records)
        assertEquals(listOf(Triple("last", "kept", 1)), result.state.entries())
    }

    @Test
    fun `checksum-valid malformed operations counts and UTF8 teach nothing`() {
        val invalidOperation = codec.increment("bad", "lost").also { it[12] = 99; repairCrc(it) }
        val invalidCount = codec.compact(listOf(Triple("bad", "lost", 1))).also {
            ByteBuffer.wrap(it, it.size - 8, 4).putInt(101)
            repairCrc(it)
        }
        val invalidUtf8 = codec.increment("bad", "lost").also { it[17] = 0xff.toByte(); repairCrc(it) }
        for (damaged in listOf(invalidOperation, invalidCount, invalidUtf8)) {
            val result = codec.decode(damaged + codec.increment("last", "kept"))
            assertTrue(result.damaged)
            assertEquals(1, result.records)
            assertEquals(listOf(Triple("last", "kept", 1)), result.state.entries())
        }
    }

    @Test
    fun `persisted counts saturate at the old ceiling and compact exactly`() {
        val state = LearnedPairState()
        val bytes = ByteArrayOutputStream()
        repeat(LearnedPairState.MAX_COUNT + 25) {
            state.record("thanks", "again")
            bytes.write(codec.increment("thanks", "again"))
        }
        assertEquals(listOf(Triple("thanks", "again", 100)), state.entries())
        assertEquals(state.entries(), codec.decode(bytes.toByteArray()).state.entries())
        val compacted = codec.decode(codec.compact(state.entries()))
        assertEquals(state.entries(), compacted.state.entries())
        assertEquals(1, compacted.records)
        compacted.state.record("thanks", "again")
        assertEquals(state.entries(), compacted.state.entries())
    }

    @Test
    fun `overflow halves counts and removes the weakest old follower deterministically`() {
        val state = LearnedPairState((0 until LearnedPairState.MAX_FOLLOWERS).map { Triple("the", word(it), 4) })
        state.record("the", "new")
        val entries = state.entries()
        assertEquals(LearnedPairState.MAX_FOLLOWERS, entries.size)
        assertFalse(entries.any { it.second == word(63) })
        assertEquals(1, entries.single { it.second == "new" }.third)
        assertTrue(entries.filter { it.second != "new" }.all { it.third == 2 })
    }

    @Test
    fun `singleton old followers decay out and repeating an existing follower never decays`() {
        val state = LearnedPairState((0 until LearnedPairState.MAX_FOLLOWERS).map { Triple("the", word(it), 1) })
        state.record("the", word(0))
        assertEquals(64, state.entries().size)
        assertEquals(2, state.entries().single { it.second == word(0) }.third)
        state.record("the", "new")
        assertEquals(listOf(Triple("the", "new", 1), Triple("the", word(0), 1)), state.entries())
    }

    @Test
    fun `previous words are capped and recording keeps a row recent`() {
        val state = LearnedPairState((0 until LearnedPairState.MAX_PREVIOUS_WORDS).map { Triple(context(it), "kept", 1) })
        state.record(context(0), "again")
        state.record("new-context", "kept")
        val entries = state.entries()
        assertEquals(LearnedPairState.MAX_PREVIOUS_WORDS, entries.map { it.first }.distinct().size)
        assertFalse(entries.any { it.first == context(1) })
        assertTrue(entries.any { it.first == context(0) })
        assertEquals("new-context", entries.last().first)
    }

    @Test
    fun `compaction preserves counts row recency and future decay and eviction`() {
        val state = LearnedPairState()
        val journal = ByteArrayOutputStream()
        fun increment(previous: String, next: String) {
            state.record(previous, next)
            journal.write(codec.increment(previous, next))
        }
        (0 until LearnedPairState.MAX_PREVIOUS_WORDS).forEach { increment(context(it), "kept") }
        (0..70).forEach { i -> repeat(4) { increment(context(0), word(i)) } }
        (0 until LearnedPairState.MAX_FOLLOWERS).forEach { i -> repeat(4) { increment(context(100), word(i)) } }
        state.remove(context(10), "kept")
        journal.write(codec.remove(context(10), "kept"))
        assertEquals(state.entries(), codec.decode(journal.toByteArray()).state.entries())
        val compactBytes = codec.compact(state.entries())
        val compacted = codec.decode(compactBytes)
        assertEquals(state.entries(), compacted.state.entries())
        assertEquals(state.entries().size, compacted.records)
        assertTrue(compactBytes.size < journal.size())

        val suffix = ByteArrayOutputStream()
        val changes = listOf(context(100) to "new-follower", "new-context" to "new-word", "another-context" to "another-word")
        changes.forEach { (previous, next) ->
            state.record(previous, next)
            compacted.state.record(previous, next)
            suffix.write(codec.increment(previous, next))
        }
        assertEquals(state.entries(), compacted.state.entries())
        assertEquals(state.entries(), codec.decode(journal.toByteArray() + suffix.toByteArray()).state.entries())
        assertEquals(state.entries(), codec.decode(compactBytes + suffix.toByteArray()).state.entries())
    }

    @Test
    fun `migration retains valid legacy keys starts and capped counts`() {
        val legacy: Map<String, Any> = linkedMapOf(
            "thanks\u001fagain" to 8,
            "${AdaptiveNgramModel.START}\u001fhello" to 150,
            "bad\u001fwrong-type" to "12",
            "bad\u001fzero" to 0,
            "bad\u001fnegative" to -1,
            "missing-separator" to 3,
            "\u001fno-previous" to 1,
            "no-next\u001f" to 1,
        )
        val migrated = LearnedPairState.migrate(legacy)
        assertEquals(
            listOf(Triple(AdaptiveNgramModel.START, "hello", 100), Triple("thanks", "again", 8)),
            migrated.entries(),
        )
        assertEquals(migrated.entries(), codec.decode(codec.compact(migrated.entries())).state.entries())
    }

    @Test
    fun `migration bounds rows and retains strongest followers without decay`() {
        val legacy = LinkedHashMap<String, Int>()
        (0 until 80).forEach { legacy["the\u001f${word(it)}"] = if (it < 10) 1 else 10 }
        val migrated = LearnedPairState.migrate(legacy)
        assertEquals(64, migrated.entries().size)
        assertTrue(migrated.entries().all { it.third == 10 })
        assertEquals(word(10), migrated.entries().first().second)
        assertEquals(word(73), migrated.entries().last().second)
        assertEquals(migrated.entries(), LearnedPairState.migrate(legacy.toList().reversed().toMap()).entries())

        val manyRows = (0..LearnedPairState.MAX_PREVIOUS_WORDS).associate { "${context(it)}\u001fword" to 1 }
        assertEquals(LearnedPairState.MAX_PREVIOUS_WORDS, LearnedPairState.migrate(manyRows).entries().size)
    }

    @Test
    fun `snapshots are detached and clear removes all row history`() {
        val state = LearnedPairState(listOf(Triple("thanks", "again", 5)))
        val snapshot = state.entries()
        state.record("thanks", "again")
        state.clear()
        assertEquals(listOf(Triple("thanks", "again", 5)), snapshot)
        assertTrue(state.entries().isEmpty())
        state.record("new", "pair")
        assertEquals(listOf(Triple("new", "pair", 1)), state.entries())
    }

    @Test
    fun `encoding refuses empty malformed or unbounded words`() {
        assertThrows(IllegalArgumentException::class.java) { codec.increment("", "word") }
        assertThrows(IllegalArgumentException::class.java) { codec.increment("word", "\ud800") }
        assertThrows(IllegalArgumentException::class.java) { codec.increment("word", "x".repeat(LearnedPairState.MAX_WORD_BYTES + 1)) }
    }

    private fun word(i: Int) = "word${i.toString().padStart(3, '0')}"
    private fun context(i: Int) = "context${i.toString().padStart(4, '0')}"
    private fun join(vararg records: ByteArray) = ByteArrayOutputStream().also { out -> records.forEach(out::write) }.toByteArray()

    private fun repairCrc(bytes: ByteArray) {
        val size = ByteBuffer.wrap(bytes, 4, 4).int
        ByteBuffer.wrap(bytes, bytes.size - 4, 4).putInt(CRC32().also { it.update(bytes, 12, size) }.value.toInt())
    }
}
