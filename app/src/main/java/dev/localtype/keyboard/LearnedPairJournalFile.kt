package dev.localtype.keyboard

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One serial worker owns this object. The journal is appended and compacted off the typing thread;
 * [load] returns a detached state for publication to the main thread. Android storage and the
 * legacy preferences are supplied by the caller, so recovery and migration can run on the JVM.
 */
class LearnedPairJournalFile(
    private val file: File,
    private val legacy: Legacy? = null,
    private val compactFloorBytes: Long = DEFAULT_COMPACT_BYTES,
) {
    interface Legacy {
        fun read(): Map<String, *>
        fun delete()
    }

    private val staging = File(file.parentFile, "${file.name}.tmp")
    /** See [forgetMarker]; its owner writes it on the calling thread and removes it after [forgetting] clears. */
    val forgetMarker = forgetMarker(file)
    private var state = LearnedPairState()
    private var loaded = false
    private var rewritePending = false
    private var clearPending = false
    private var legacyDeletePending = false
    private var compactBaselineBytes = 0L

    init {
        require(compactFloorBytes > 0)
    }

    /** A forget is still waiting for its deletions to succeed. */
    val forgetting: Boolean get() = clearPending

    /** A committed journal is the migration marker, even if the old-file deletion was interrupted. */
    fun load(): LearnedPairState {
        if (!loaded && forgetMarker.exists()) {
            // A forget was requested but the process died before its deletions finished. Finish it
            // without reading or migrating the words it asked to remove.
            clear()
            return LearnedPairState()
        }
        if (!loaded) {
            deleteFile(staging)
            if (file.exists()) {
                val decoded = LearnedPairJournal.decode(file.readBytes())
                state = decoded.state
                rewritePending = decoded.damaged
                compactBaselineBytes = LearnedPairJournal.compact(state.entries()).size.toLong()
            } else {
                state = LearnedPairState.migrate(legacy?.read() ?: emptyMap<String, Any>())
                // Commit first. Deleting the old file before this succeeds would lose its pairs.
                rewrite()
            }
            loaded = true
            legacyDeletePending = legacy != null
        }
        // A failed old-file deletion must not hide the successfully committed migration. flush()
        // retries it without rereading the preferences or adding their counts a second time.
        try {
            deleteLegacy()
        } catch (_: IOException) {
            // Keep the pending flag for the caller's worker retry.
        }
        return LearnedPairState(state.entries())
    }

    fun record(previous: String, next: String) {
        check(loaded) { "Load learned pairs before recording" }
        state.record(previous, next)
        persist(LearnedPairJournal.increment(previous, next))
    }

    fun remove(previous: String, next: String) {
        check(loaded) { "Load learned pairs before removing" }
        state.remove(previous, next)
        persist(LearnedPairJournal.remove(previous, next))
    }

    /** Can run before load: forgetting must never read or migrate the words being forgotten. */
    fun clear() {
        state.clear()
        loaded = true
        rewritePending = false
        clearPending = true
        legacyDeletePending = legacy != null
        flush()
    }

    /** Retry a failed write or deletion without needing another finished word. */
    fun flush() {
        if (clearPending) {
            // Leave the committed journal in place until the source that could remigrate is gone.
            deleteLegacy()
            deleteFile(staging)
            deleteFile(file)
            compactBaselineBytes = 0L
            clearPending = false
        }
        if (rewritePending) rewrite()
        deleteLegacy()
    }

    private fun persist(record: ByteArray) {
        // A queued record following a failed clear first finishes deleting the old files, then
        // writes only the new state. It must not append to the words requested for deletion.
        if (clearPending) {
            rewritePending = true
            flush()
            return
        }
        if (rewritePending || !file.exists()) {
            rewritePending = true
            rewrite()
        } else {
            // A partially written record is unknown until the next load. After any IOException,
            // retry the complete worker state instead of appending over that uncertain tail.
            rewritePending = true
            FileOutputStream(file, true).use { out ->
                out.write(record)
                out.fd.sync()
            }
            rewritePending = false
            if (file.length() > maxOf(compactFloorBytes, compactBaselineBytes * 2)) rewrite()
        }
        deleteLegacy()
    }

    private fun rewrite() {
        rewritePending = true
        val bytes = LearnedPairJournal.compact(state.entries())
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Cannot create learned-pair directory")
        }
        // Both files are siblings, so the move stays on one filesystem. API 26 supports these
        // NIO calls. A crash before the atomic move leaves the original journal untouched.
        FileOutputStream(staging).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        compactBaselineBytes = bytes.size.toLong()
        rewritePending = false
    }

    private fun deleteLegacy() {
        if (!legacyDeletePending) return
        legacy?.delete()
        legacyDeletePending = false
    }

    private fun deleteFile(target: File) {
        if (target.exists() && !target.delete()) throw IOException("Cannot delete learned-pair file")
    }

    companion object {
        const val DEFAULT_COMPACT_BYTES = 256L * 1024

        /**
         * Present from a Forget request until every requested forget has deleted its files. It exists on
         * disk before the request returns, so a process killed before the worker reaches the clear still
         * forgets: the next [load] finds it and deletes instead of reading.
         */
        fun forgetMarker(file: File): File = File(file.parentFile, "${file.name}.forget")
    }
}
