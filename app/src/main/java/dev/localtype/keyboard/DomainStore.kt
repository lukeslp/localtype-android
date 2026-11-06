package dev.localtype.keyboard

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/**
 * The remembered hosts on disk: one small file in noBackupFilesDir, so neither backup nor a device-to-device
 * transfer copies it. One worker thread reads and writes it, in order, each write replacing the whole file
 * through [AtomicFile]. The keyboard and settings share one [RememberedDomains] per process ([open]), so
 * removing a host in settings reaches an open keyboard at once.
 *
 * Forgetting is durable before it returns: an empty save, and [forget] itself, leave a marker file on the
 * calling thread. A process killed before the worker catches up finds the marker at its next [load] and
 * deletes the hosts' file instead of reading it. The worker removes the marker only after a write that was
 * queued after the newest forget, checking and removing under the lock that counting and marking hold, so an
 * older write still in the queue cannot take the marker away and leave its forgotten hosts on disk.
 */
class DomainStore private constructor(private val file: AtomicFile, private val forget: File) : RememberedDomains.Store {
    /** On the worker, once, while [open] builds the list. No file yet, or one that can't be read, is an empty list. */
    override fun load(): List<RememberedDomains.Entry> {
        if (forget.exists()) {
            file.delete()
            if (!file.baseFile.exists()) forget.delete()
            return emptyList()
        }
        return try {
            DomainCodec.decode(String(file.readFully(), Charsets.UTF_8))
        } catch (_: IOException) {
            emptyList()
        }
    }

    override fun save(entries: List<RememberedDomains.Entry>) {
        if (entries.isEmpty()) mark(forget)
        val queuedAfter = forgets.get()
        worker.post { write(entries, queuedAfter) }
    }

    /** [queuedAfter] is how many forgets had been asked for when this write was queued. */
    private fun write(entries: List<RememberedDomains.Entry>, queuedAfter: Int) {
        if (entries.isEmpty()) {
            file.delete()
            if (!file.baseFile.exists()) unmark(forget, queuedAfter)
            return
        }
        val bytes = DomainCodec.encode(entries).toByteArray(Charsets.UTF_8)
        val out = try {
            file.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            out.write(bytes)
            file.finishWrite(out)
            // What is on disk now was all typed after the newest forget, unless another one has come since.
            unmark(forget, queuedAfter)
        } catch (_: IOException) {
            file.failWrite(out)
        }
    }

    companion object {
        private const val FILE_NAME = "remembered_domains.txt"
        private const val TLD_ASSET = "tlds.txt"
        private const val FORGET_SUFFIX = ".forget"

        /** Forgets asked for in this process, counted on the main thread and read on the worker. */
        private val forgets = java.util.concurrent.atomic.AtomicInteger()

        /** Counting a forget and leaving its marker are one step, and so are checking the count and removing it. */
        private val markerLock = Any()

        private fun mark(forget: File) = synchronized(markerLock) {
            forgets.incrementAndGet()
            runCatching { forget.createNewFile() }
        }

        /** Removes the marker unless a forget has been asked for since the write that calls this was queued. */
        private fun unmark(forget: File, queuedAfter: Int) = synchronized(markerLock) {
            if (queuedAfter == forgets.get()) forget.delete()
        }

        private val worker: Handler by lazy { Handler(HandlerThread("localtype-domains").apply { start() }.looper) }
        private val main = Handler(Looper.getMainLooper())

        // Main thread only.
        private var shared: RememberedDomains? = null
        private val waiting = ArrayList<(RememberedDomains) -> Unit>()

        /** The top-level domains a typed host may end in; empty until the first [open] has read them. */
        @Volatile var tlds: Set<String> = emptySet()
            private set

        /**
         * Forget learned words. The marker is left first, on the calling thread, so the hosts are gone for good
         * from the moment this returns, even when the list has not been read yet and the process dies before it is.
         */
        fun forget(context: Context, done: () -> Unit) {
            mark(File(context.applicationContext.noBackupFilesDir, "$FILE_NAME$FORGET_SUFFIX"))
            open(context) { domains ->
                domains.clear()
                done()
            }
        }

        /** Calls [onReady] on the main thread with this process's remembered hosts, reading both files first the first time. */
        fun open(context: Context, onReady: (RememberedDomains) -> Unit) {
            shared?.let { return onReady(it) }
            waiting += onReady
            if (waiting.size > 1) return
            val app = context.applicationContext
            val file = File(app.noBackupFilesDir, FILE_NAME)
            worker.post {
                // With no list of endings nothing typed counts as a host, so nothing is remembered.
                val endings = try {
                    app.assets.open(TLD_ASSET).bufferedReader().useLines { DomainHosts.parseTlds(it) }
                } catch (_: IOException) {
                    emptySet()
                }
                val domains = RememberedDomains(DomainStore(AtomicFile(file), File(file.path + FORGET_SUFFIX)))
                main.post {
                    tlds = endings
                    shared = domains
                    val ready = waiting.toList()
                    waiting.clear()
                    ready.forEach { it(domains) }
                }
            }
        }
    }
}
