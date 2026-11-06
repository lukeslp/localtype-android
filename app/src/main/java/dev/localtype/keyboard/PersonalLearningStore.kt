package dev.localtype.keyboard

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Main-thread evidence cache; the serial worker owns a separate state and all snapshot I/O. One per
 * language: English keeps `personal_learning.bin`, another language its own file.
 */
class PersonalLearningStore private constructor(
    context: Context,
    fileName: String = "personal_learning.bin",
    private val oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords,
) {
    private val base = File(context.noBackupFilesDir, fileName)
    private val file = AtomicFile(base)
    private val marker = File(base.parentFile, "${base.name}.forget")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Handler(HandlerThread("localtype-personal-learning").apply { start() }.looper)
    private var cache = PersonalLearningState(oneLetterWords = oneLetterWords)
    private val cacheReceipts = LinkedHashMap<Long, Long>()
    private val beforeReady = ArrayList<Action>()
    private val waiting = ArrayList<() -> Unit>()
    private var clientSequence = 0L
    var ready: Boolean = false
        private set

    // Only marker generation crosses threads, under this lock.
    private val forgetLock = Any()
    private var requestedForget = 0L
    // Worker only.
    private val saved = PersonalLearningState(oneLetterWords = oneLetterWords)
    private val savedReceipts = LinkedHashMap<Long, Long>()
    private var completedForget = 0L
    private var emptyPersistencePending = false
    private var writesAllowed = true
    private var dirty = false
    private var retryScheduled = false
    private var failures = 0

    private sealed class Action {
        data class Record(val id: Long, val context: List<String>, val word: String,
            val origin: LearningOrigin) : Action()
        data class Undo(val id: Long) : Action()
        data class Clear(val generation: Long) : Action()
        data class RemovePair(val previous: String, val next: String) : Action()
        data class RemoveWord(val word: String) : Action()
    }

    init { worker.post { load() } }

    fun whenReady(onReady: () -> Unit) {
        checkMain()
        if (ready) onReady() else waiting += onReady
    }

    /** Receipt acknowledges in-memory learning, not a completed durable write. */
    fun record(context: List<String>, word: String, origin: LearningOrigin): Long? {
        checkMain()
        val tail = context.takeLast(3).toList()
        if (clientSequence == Long.MAX_VALUE || !PersonalLearningCodec.validWordSize(word) ||
            !PersonalLearningCodec.validWordSize(AdaptiveNgramModel.normalize(word)) ||
            tail.any { !PersonalLearningCodec.validWordSize(it) ||
                !PersonalLearningCodec.validWordSize(AdaptiveNgramModel.normalize(it)) }) return null
        val receipt = cache.record(tail, word, origin) ?: return null
        val id = ++clientSequence
        remember(cacheReceipts, id, receipt)
        enqueue(Action.Record(id, tail, word, origin))
        return id
    }

    fun undo(clientReceipt: Long): Boolean {
        checkMain()
        val receipt = cacheReceipts.remove(clientReceipt) ?: return false
        if (!cache.undo(receipt)) return false
        enqueue(Action.Undo(clientReceipt))
        return true
    }

    fun followers(context: List<String>, maxContext: Int = 3): List<PersonalFollower> {
        checkMain()
        return cache.followers(context, maxContext)
    }

    /** Failure to durably mark Forget throws before any in-memory evidence is removed. */
    fun clear() {
        checkMain()
        val generation = synchronized(forgetLock) {
            try {
                if (!base.parentFile!!.isDirectory && !base.parentFile!!.mkdirs()) throw IOException()
                FileOutputStream(marker).use { it.write(1); it.fd.sync() }
                syncDirectory()
            } catch (_: Exception) {
                Log.w(TAG, "Could not mark personal learning forgotten")
                throw IllegalStateException("Could not persist personal learning forget marker")
            }
            ++requestedForget
        }
        val action = Action.Clear(generation)
        apply(cache, cacheReceipts, action)
        enqueue(action)
    }

    fun removePair(previous: String, next: String) {
        checkMain()
        val action = Action.RemovePair(AdaptiveNgramModel.normalize(previous), AdaptiveNgramModel.normalize(next))
        apply(cache, cacheReceipts, action)
        enqueue(action)
    }

    fun removeWord(word: String) {
        checkMain()
        val action = Action.RemoveWord(AdaptiveNgramModel.normalize(word))
        apply(cache, cacheReceipts, action)
        enqueue(action)
    }

    private fun enqueue(action: Action) {
        if (!ready) beforeReady += action
        worker.post {
            apply(saved, savedReceipts, action)
            if (action is Action.Clear) {
                completedForget = action.generation
                emptyPersistencePending = true
            }
            dirty = true
            failures = 0
            flush()
        }
    }

    private fun apply(state: PersonalLearningState, receipts: LinkedHashMap<Long, Long>, action: Action) {
        when (action) {
            is Action.Record -> state.record(action.context, action.word, action.origin)?.let {
                remember(receipts, action.id, it)
            }
            is Action.Undo -> receipts.remove(action.id)?.let { state.undo(it) }
            is Action.Clear -> { state.clear(); receipts.clear() }
            is Action.RemovePair, is Action.RemoveWord -> {
                val retained = state.entries().filterNot { entry ->
                    when (action) {
                        is Action.RemovePair -> entry.context.last() == action.previous && entry.word == action.next
                        is Action.RemoveWord -> entry.word == action.word || action.word in entry.context
                        else -> false
                    }
                }
                if (retained.size != state.entries().size) {
                    // restore invalidates state undo deltas; invalidate the corresponding client IDs too.
                    state.restore(retained)
                    receipts.clear()
                }
            }
        }
    }

    private fun load() {
        if (marker.exists()) {
            emptyPersistencePending = true
            dirty = true
            flush()
        } else {
            try {
                file.openRead().use { input ->
                    require(input.channel.size() <= PersonalLearningCodec.MAX_BYTES)
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        require(buffer.size() + count <= PersonalLearningCodec.MAX_BYTES)
                        buffer.write(chunk, 0, count)
                    }
                    saved.restore(PersonalLearningCodec.decode(buffer.toByteArray(), oneLetterWords))
                }
            } catch (_: Exception) {
                // AtomicFile resolves any backup before decoding. Keep a damaged source privately
                // for diagnosis; never log its contents or replace it if quarantine fails.
                val sources = listOf(base, File(base.path + ".bak"), File(base.path + ".new")).filter { it.exists() }
                if (sources.isNotEmpty()) {
                    for (source in sources) {
                        val diagnostic = File(base.parentFile, "${source.name}.damaged-${System.nanoTime()}")
                        if (!source.renameTo(diagnostic)) writesAllowed = false
                    }
                    Log.w(TAG, "Could not load personal learning snapshot")
                }
                saved.clear()
            }
        }
        val baseline = saved.entries()
        main.post {
            cache = PersonalLearningState(oneLetterWords = oneLetterWords).apply { restore(baseline) }
            cacheReceipts.clear()
            beforeReady.forEach { apply(cache, cacheReceipts, it) }
            beforeReady.clear()
            ready = true
            val callbacks = waiting.toList()
            waiting.clear()
            callbacks.forEach { it() }
        }
    }

    private fun flush() {
        try {
            if (emptyPersistencePending) {
                write(emptyList())
                // Clear may safely replace even a source that could not be quarantined.
                writesAllowed = true
                emptyPersistencePending = false
            }
            synchronized(forgetLock) {
                if (completedForget == requestedForget && marker.exists() && !emptyPersistencePending) {
                    if (!marker.delete()) throw IOException()
                    syncDirectory()
                }
            }
            if (dirty && writesAllowed) {
                write(saved.entries())
                dirty = false
            }
            failures = 0
        } catch (_: Exception) {
            Log.w(TAG, "Could not persist personal learning snapshot")
            // Bounded retries; a later mutation retries the complete worker state again.
            failures++
            if (!retryScheduled && failures <= 3) {
                retryScheduled = true
                worker.postDelayed({ retryScheduled = false; flush() }, 5_000L * failures)
            }
        }
    }

    private fun write(entries: List<PersonalLearningState.Entry>) {
        val bytes = PersonalLearningCodec.encode(entries, oneLetterWords)
        val out = file.startWrite()
        try {
            out.write(bytes)
            out.fd.sync()
            file.finishWrite(out)
            // Some Android AtomicFile versions log rename failures instead of throwing them.
            if (!base.exists() || File(base.path + ".new").exists() || File(base.path + ".bak").exists()) {
                throw IOException()
            }
            syncDirectory()
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
    }

    private fun syncDirectory() {
        val fd = Os.open(base.parentFile!!.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    companion object {
        private const val TAG = "LocalTypePersonalLearning"
        private val shared = HashMap<String, PersonalLearningStore>()

        fun get(context: Context, language: LanguageProfile = LanguageProfile.ENGLISH): PersonalLearningStore {
            check(Looper.myLooper() == Looper.getMainLooper())
            return shared.getOrPut(language.code) {
                if (language.english) PersonalLearningStore(context.applicationContext)
                else PersonalLearningStore(context.applicationContext, "personal_learning_${language.code}.bin", language.oneLetterWords)
            }
        }

        private fun remember(map: LinkedHashMap<Long, Long>, id: Long, receipt: Long) {
            map[id] = receipt
            if (map.size > 64) map.remove(map.keys.first())
        }
    }
}
