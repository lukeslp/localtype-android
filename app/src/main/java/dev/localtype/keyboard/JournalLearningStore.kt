package dev.localtype.keyboard

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * One process-wide learned-pair cache per language, owned by the main thread. File work, including
 * legacy preferences migration, runs in order on one worker. A rebuild reads the cache even when its
 * latest increments are still queued for disk; it never reparses a file on the typing thread.
 * English keeps `learned_pairs.bin`; another language writes its own file and never the English one.
 */
class JournalLearningStore private constructor(
    private val context: Context,
    private val fileName: String = FILE_NAME,
    /** Only English has preferences from before the journal to migrate. */
    private val legacyPrefs: String? = LEGACY_PREFS,
) : AdaptiveNgramModel.LearningStore {
    private val buffer = LearnedPairBuffer(::enqueue)
    private val waiting = ArrayList<(List<Triple<String, String, Int>>) -> Unit>()
    val ready: Boolean get() = buffer.ready

    // Worker only. Operations arriving before a successful load wait here, in their original order.
    private var journal: LearnedPairJournalFile? = null
    private val pendingWrites = ArrayList<LearnedPairBuffer.Mutation>()
    private var retryScheduled = false
    // Both guarded by forgetLock: requests come from the main thread, the worker finishes them.
    private val forgetLock = Any()
    private var forgetsRequested = 0
    private var forgetsFinished = 0

    init {
        worker.post { load() }
    }

    override fun entries() = buffer.entries()
    override fun record(previous: String, next: String) = buffer.record(previous, next)
    fun remove(previous: String, next: String) = buffer.remove(previous, next)
    override fun clear() {
        // On disk before this returns, so a process killed before the worker deletes the files still forgets.
        synchronized(forgetLock) {
            forgetsRequested++
            try {
                val marker = LearnedPairJournalFile.forgetMarker(File(context.noBackupFilesDir, fileName))
                marker.parentFile?.mkdirs()
                marker.createNewFile()
            } catch (_: IOException) {
                Log.w(TAG, "Could not mark learned pairs forgotten")
            }
        }
        buffer.clear()
    }

    /**
     * Main-thread callback. Before readiness, its baseline excludes any pairs forgotten during
     * the load, but not the buffered increments already counted by the service's live model.
     */
    fun whenReady(onReady: (List<Triple<String, String, Int>>) -> Unit) {
        if (ready) onReady(emptyList()) else waiting += onReady
    }

    private fun newFile() = LearnedPairJournalFile(
            File(context.noBackupFilesDir, fileName),
            object : LearnedPairJournalFile.Legacy {
                override fun read(): Map<String, *> = legacyPrefs?.let { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }.orEmpty()
                override fun delete() {
                    val prefs = legacyPrefs ?: return
                    if (!context.deleteSharedPreferences(prefs)) throw IOException("Could not delete legacy learned pairs")
                }
            },
        )

    private fun load() {
        // A Forget made during a failed load already deleted the old data and published empty.
        if (journal != null) return
        val file = newFile()
        val loaded = try {
            file.load()
        } catch (_: IOException) {
            Log.w(TAG, "Could not load learned pairs; retrying")
            worker.postDelayed({ load() }, RETRY_MS)
            return
        }
        journal = file
        releaseForgetMarker()
        // Prepare both maps on the worker. The main thread only merges the intervening mutations.
        publish(loaded, LearnedPairState(loaded.entries()))
        val writes = pendingWrites.toList()
        pendingWrites.clear()
        writes.forEach(::write)
        retryFlush()
    }

    private fun publish(loaded: LearnedPairState, savedBaseline: LearnedPairState) {
        main.post {
            val baseline = buffer.publish(loaded, savedBaseline)
            val callbacks = waiting.toList()
            waiting.clear()
            callbacks.forEach { it(baseline) }
        }
    }

    private fun enqueue(mutation: LearnedPairBuffer.Mutation) {
        worker.post {
            if (mutation == LearnedPairBuffer.Mutation.Clear) synchronized(forgetLock) { forgetsFinished++ }
            if (journal == null && mutation == LearnedPairBuffer.Mutation.Clear) {
                // Forget must work even if an unreadable journal or a failed migration held the
                // initial load back. Older queued increments must not restore the cleared file.
                pendingWrites.clear()
                journal = newFile()
                write(mutation)
                publish(LearnedPairState(), LearnedPairState())
            } else if (journal == null) pendingWrites += mutation else write(mutation)
        }
    }

    private fun write(mutation: LearnedPairBuffer.Mutation) {
        val file = journal ?: return
        try {
            when (mutation) {
                is LearnedPairBuffer.Mutation.Increment -> file.record(mutation.previous, mutation.next)
                is LearnedPairBuffer.Mutation.Remove -> file.remove(mutation.previous, mutation.next)
                LearnedPairBuffer.Mutation.Clear -> file.clear()
            }
            releaseForgetMarker()
        } catch (_: IOException) {
            Log.w(TAG, "Could not write learned pairs; retrying")
            scheduleRetry()
        }
    }

    private fun retryFlush() {
        try {
            journal?.flush()
            releaseForgetMarker()
        } catch (_: IOException) {
            scheduleRetry()
        }
    }

    /** Worker only. The marker goes once every forget requested so far has deleted its files. */
    private fun releaseForgetMarker() {
        val file = journal ?: return
        if (file.forgetting) return
        synchronized(forgetLock) {
            if (forgetsFinished == forgetsRequested && file.forgetMarker.exists() && !file.forgetMarker.delete()) {
                Log.w(TAG, "Could not remove the forget marker")
            }
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        worker.postDelayed({
            retryScheduled = false
            retryFlush()
        }, RETRY_MS)
    }

    companion object {
        private const val TAG = "LocalTypeLearning"
        private const val FILE_NAME = "learned_pairs.bin"
        private const val LEGACY_PREFS = "accepted_predictions"
        private const val RETRY_MS = 5_000L
        private val worker by lazy { Handler(HandlerThread("localtype-learning").apply { start() }.looper) }
        private val main = Handler(Looper.getMainLooper())
        // Main thread only, shared by settings and every service instance; one per language.
        private val shared = HashMap<String, JournalLearningStore>()

        fun get(context: Context, language: LanguageProfile = LanguageProfile.ENGLISH): JournalLearningStore = shared.getOrPut(language.code) {
            if (language.english) JournalLearningStore(context.applicationContext)
            else JournalLearningStore(context.applicationContext, "learned_pairs_${language.code}.bin", legacyPrefs = null)
        }
    }
}
