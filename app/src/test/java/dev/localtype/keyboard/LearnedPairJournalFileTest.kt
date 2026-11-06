package dev.localtype.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LearnedPairJournalFileTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun file(): File = File(temporary.newFolder(), "learned_pairs.bin")

    private fun staging(file: File): File = File(file.parentFile, "${file.name}.tmp")

    @Test
    fun `append round trip and published state is detached`() {
        val file = file()
        val journal = LearnedPairJournalFile(file)
        val published = journal.load()
        journal.record("hello", "world")
        journal.record("hello", "world")
        journal.record("hello", "there")
        journal.remove("hello", "there")

        assertTrue(published.entries().isEmpty())
        assertEquals(4, LearnedPairJournal.decode(file.readBytes()).records)
        assertEquals(listOf(Triple("hello", "world", 2)), LearnedPairJournalFile(file).load().entries())
        assertEquals(256L * 1024, LearnedPairJournalFile.DEFAULT_COMPACT_BYTES)
    }

    @Test
    fun `torn tail is recovered before the next append`() {
        val file = file()
        val finished = LearnedPairJournal.increment("hello", "world")
        val torn = LearnedPairJournal.increment("never", "learned").dropLast(3).toByteArray()
        file.writeBytes(finished + torn)
        val journal = LearnedPairJournalFile(file)
        assertEquals(listOf(Triple("hello", "world", 1)), journal.load().entries())

        journal.record("hello", "there")
        val decoded = LearnedPairJournal.decode(file.readBytes())
        assertFalse(decoded.damaged)
        assertEquals(listOf(Triple("hello", "there", 1), Triple("hello", "world", 1)), decoded.state.entries())
        assertEquals(decoded.state.entries(), LearnedPairJournalFile(file).load().entries())
    }

    @Test
    fun `threshold compacts to one absolute record per pair`() {
        val file = file()
        val journal = LearnedPairJournalFile(file, compactFloorBytes = 40)
        journal.load()
        journal.record("hello", "world")
        journal.record("hello", "world")
        assertEquals(1, LearnedPairJournal.decode(file.readBytes()).records)
        assertArrayEquals(LearnedPairJournal.compact(listOf(Triple("hello", "world", 2))), file.readBytes())

        journal.record("hello", "world")
        // The compacted file is already near the small floor. Doubling its baseline avoids
        // rewriting the snapshot after each subsequent word.
        assertEquals(2, LearnedPairJournal.decode(file.readBytes()).records)
        journal.record("hello", "world")
        assertEquals(1, LearnedPairJournal.decode(file.readBytes()).records)
        assertEquals(listOf(Triple("hello", "world", 4)), LearnedPairJournalFile(file).load().entries())
    }

    @Test
    fun `compaction and restart preserve the next follower decay and oldest row eviction`() {
        val file = file()
        val entries = buildList {
            repeat(LearnedPairState.MAX_PREVIOUS_WORDS) { row ->
                repeat(if (row == 0) LearnedPairState.MAX_FOLLOWERS else 1) { follower ->
                    add(Triple("row-${row.toString().padStart(4, '0')}", "next-$follower", 2))
                }
            }
        }
        val expected = LearnedPairState(entries)
        file.writeBytes(LearnedPairJournal.compact(expected.entries()))
        val journal = LearnedPairJournalFile(file, compactFloorBytes = 1)
        journal.load()
        val largeFollower = "x".repeat(LearnedPairState.MAX_WORD_BYTES)
        journal.record("row-0000", largeFollower)
        expected.record("row-0000", largeFollower)
        // This single large frame crosses twice the initial snapshot size and forces a real
        // filesystem compaction; the touched first row must remain the newest after restart.
        assertArrayEquals(LearnedPairJournal.compact(expected.entries()), file.readBytes())

        val copiedFile = file()
        copiedFile.writeBytes(file.readBytes())
        val restarted = LearnedPairJournalFile(copiedFile)
        assertEquals(expected.entries(), restarted.load().entries())
        listOf("row-0000" to "new-follower", "new-context" to "word").forEach { (previous, next) ->
            expected.record(previous, next)
            journal.record(previous, next)
            restarted.record(previous, next)
        }

        assertEquals(expected.entries(), LearnedPairJournalFile(file).load().entries())
        assertEquals(expected.entries(), LearnedPairJournalFile(copiedFile).load().entries())
        assertFalse(expected.entries().any { it.first == "row-0001" })
        assertTrue(expected.entries().any { it.first == "row-0000" })
    }

    @Test
    fun `migration commits capped valid pairs before deleting the preferences`() {
        val file = file()
        var reads = 0
        var deletes = 0
        val legacy = object : LearnedPairJournalFile.Legacy {
            override fun read(): Map<String, *> {
                reads++
                return mapOf("hello\u001fworld" to 500, "hello\u001fthere" to 3,
                    "broken" to 7, "zero\u001fcount" to 0, "wrong\u001ftype" to "2")
            }

            override fun delete() {
                assertTrue(file.isFile)
                assertEquals(listOf(Triple("hello", "there", 3), Triple("hello", "world", 100)),
                    LearnedPairJournal.decode(file.readBytes()).state.entries())
                deletes++
            }
        }
        val journal = LearnedPairJournalFile(file, legacy)
        val migrated = journal.load().entries()
        assertEquals(migrated, journal.load().entries())
        assertEquals(migrated, LearnedPairJournalFile(file, legacy).load().entries())
        assertEquals(1, reads)
        assertEquals(2, deletes)
    }

    @Test
    fun `failed migration write retains the old source for retry`() {
        val file = file()
        var deletes = 0
        var obstruct = true
        val legacy = object : LearnedPairJournalFile.Legacy {
            override fun read(): Map<String, *> {
                if (obstruct) staging(file).mkdir()
                return mapOf("hello\u001fworld" to 5)
            }

            override fun delete() { deletes++ }
        }
        val journal = LearnedPairJournalFile(file, legacy)
        assertTrue(runCatching { journal.load() }.exceptionOrNull() is IOException)
        assertFalse(file.exists())
        assertEquals(0, deletes)

        obstruct = false
        assertEquals(listOf(Triple("hello", "world", 5)), journal.load().entries())
        assertEquals(1, deletes)
    }

    @Test
    fun `interrupted legacy deletion never remigrates and flush retries it`() {
        val file = file()
        var reads = 0
        var deletionFails = true
        var deleted = false
        val legacy = object : LearnedPairJournalFile.Legacy {
            override fun read(): Map<String, *> {
                reads++
                return mapOf("hello\u001fworld" to 9)
            }

            override fun delete() {
                if (deletionFails) throw IOException("test deletion failure")
                deleted = true
            }
        }
        val journal = LearnedPairJournalFile(file, legacy)
        assertEquals(listOf(Triple("hello", "world", 9)), journal.load().entries())
        assertTrue(runCatching { journal.flush() }.exceptionOrNull() is IOException)
        val restarted = LearnedPairJournalFile(file, legacy)
        assertEquals(listOf(Triple("hello", "world", 9)), restarted.load().entries())
        assertEquals(1, reads)
        deletionFails = false
        restarted.flush()
        assertTrue(deleted)
    }

    @Test
    fun `clear deletes both formats and staging before later queued records recreate the journal`() {
        val file = file()
        file.writeBytes(LearnedPairJournal.increment("old", "word"))
        staging(file).writeBytes(LearnedPairJournal.increment("staged", "word"))
        var deleted = false
        val legacy = object : LearnedPairJournalFile.Legacy {
            override fun read(): Map<String, *> = error("clear must not read the old preferences")
            override fun delete() { deleted = true }
        }
        val journal = LearnedPairJournalFile(file, legacy)
        journal.clear()
        journal.flush()
        assertTrue(deleted)
        assertFalse(file.exists())
        assertFalse(staging(file).exists())

        journal.record("new", "word")
        assertEquals(listOf(Triple("new", "word", 1)), LearnedPairJournalFile(file).load().entries())
    }

    @Test
    fun `a forget marker left by a killed process deletes instead of reading or migrating`() {
        val file = file()
        file.writeBytes(LearnedPairJournal.increment("old", "word"))
        var deleted = false
        val legacy = object : LearnedPairJournalFile.Legacy {
            override fun read(): Map<String, *> = error("a pending forget must not read the old preferences")
            override fun delete() { deleted = true }
        }
        val journal = LearnedPairJournalFile(file, legacy)
        assertTrue(journal.forgetMarker.createNewFile())

        assertTrue(journal.load().entries().isEmpty())
        assertFalse(journal.forgetting)
        assertTrue(deleted)
        assertFalse(file.exists())
        // The owner removes the marker; until then every restart forgets again, and nothing returns.
        assertTrue(journal.forgetMarker.exists())
        assertTrue(LearnedPairJournalFile(file, legacy).load().entries().isEmpty())

        journal.record("new", "word")
        assertTrue(journal.forgetMarker.delete())
        assertEquals(listOf(Triple("new", "word", 1)), LearnedPairJournalFile(file).load().entries())
    }

    @Test
    fun `a forget marker before migration leaves the old preferences unread`() {
        val file = file()
        var deleted = false
        val legacy = object : LearnedPairJournalFile.Legacy {
            override fun read(): Map<String, *> = error("a pending forget must not migrate")
            override fun delete() { deleted = true }
        }
        assertTrue(LearnedPairJournalFile.forgetMarker(file).also { it.parentFile.mkdirs() }.createNewFile())
        assertTrue(LearnedPairJournalFile(file, legacy).load().entries().isEmpty())
        assertTrue(deleted)
        assertFalse(file.exists())
    }

    @Test
    fun `failed append is retried as a full snapshot without another increment`() {
        val file = file()
        val journal = LearnedPairJournalFile(file)
        journal.load()
        journal.record("first", "word")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        assertTrue(runCatching { journal.record("second", "word") }.exceptionOrNull() is IOException)
        assertTrue(file.delete())

        journal.flush()
        assertEquals(listOf(Triple("first", "word", 1), Triple("second", "word", 1)),
            LearnedPairJournalFile(file).load().entries())
    }

    @Test
    fun `failed clear flush finishes deletion without recreating an empty file`() {
        val file = file()
        val journal = LearnedPairJournalFile(file)
        journal.load()
        journal.record("old", "word")
        val blocked = staging(file)
        assertTrue(blocked.mkdir())
        File(blocked, "child").writeText("test")
        assertTrue(runCatching { journal.clear() }.exceptionOrNull() is IOException)
        assertTrue(file.exists())
        assertTrue(blocked.deleteRecursively())

        journal.flush()
        assertFalse(file.exists())
        assertFalse(blocked.exists())
    }

    @Test
    fun `record queued after a failed clear survives the later deletion retry`() {
        val file = file()
        val journal = LearnedPairJournalFile(file)
        journal.load()
        journal.record("old", "word")
        val blocked = staging(file)
        assertTrue(blocked.mkdir())
        File(blocked, "child").writeText("test")
        assertTrue(runCatching { journal.clear() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { journal.record("new", "word") }.exceptionOrNull() is IOException)
        assertTrue(blocked.deleteRecursively())

        journal.flush()
        assertEquals(listOf(Triple("new", "word", 1)), LearnedPairJournalFile(file).load().entries())
    }

    @Test
    fun `an interrupted compaction retains the committed original`() {
        val file = file()
        file.writeBytes(LearnedPairJournal.increment("original", "word"))
        staging(file).writeBytes(LearnedPairJournal.compact(listOf(Triple("uncommitted", "word", 3))))

        assertEquals(listOf(Triple("original", "word", 1)), LearnedPairJournalFile(file).load().entries())
        assertFalse(staging(file).exists())
    }
}
