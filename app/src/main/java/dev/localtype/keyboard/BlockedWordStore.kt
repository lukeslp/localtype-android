package dev.localtype.keyboard

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AtomicFile
import java.io.File

/** One private, process-owned exclusion snapshot per language. All file work is ordered off the typing thread. */
class BlockedWordStore private constructor(context: Context, val language: LanguageProfile) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "blocked_words_${language.code}.txt"))
    private val persistence = BlockedWordsFile(object : BlockedWordsFile.Disk {
        override fun read(maxBytes: Int): ByteArray? = try {
            file.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= maxBytes) {
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                check(bytes.size <= maxBytes) { "Oversized blocked-word file" }
                bytes
            }
        } catch (error: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw error
            null
        }
        override fun write(bytes: ByteArray) {
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
    }, language)
    @Volatile var words: Set<String> = emptySet()
        private set
    @Volatile var ready = false
        private set
    var failed = false
        private set
    var busy = false
        private set
    private val waiting = ArrayList<() -> Unit>()

    init { reload() }

    fun whenLoaded(done: () -> Unit) {
        checkMain()
        if (!busy) done() else waiting += done
    }

    fun reload() {
        checkMain()
        if (busy) return
        work { persistence.load() }
    }

    /** Reset is explicit recovery for an unreadable file. Ordinary additions/removals require a successful load. */
    fun change(word: String?, block: Boolean = true, reset: Boolean = false, done: (Boolean) -> Unit = {}) {
        checkMain()
        if (busy || (!ready && !reset)) { done(false); return }
        val normalized = if (reset) null else word?.let(BlockedWords::normalize)
        if (!reset && normalized == null) { done(false); return }
        val next = if (reset) emptySet() else if (block) words + normalized!! else words - normalized!!
        if (next.size > BlockedWords.MAX_WORDS) { done(false); return }
        work(done) { persistence.save(next) }
    }

    private fun work(done: (Boolean) -> Unit = {}, action: () -> Set<String>) {
        busy = true
        worker.post {
            val result = runCatching(action)
            main.post {
                busy = false
                failed = result.isFailure
                result.getOrNull()?.let { words = it.toSet(); ready = true }
                // No private words or digests enter preferences/logs, only an invalidation counter.
                KeyboardSettings.bumpLearnedGeneration(app)
                val callbacks = waiting.toList(); waiting.clear()
                callbacks.forEach { it() }
                done(result.isSuccess)
            }
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        private val worker by lazy { Handler(HandlerThread("localtype-blocked-words").apply { start() }.looper) }
        private val main by lazy { Handler(Looper.getMainLooper()) }
        private val stores = HashMap<String, BlockedWordStore>()
        fun get(context: Context, language: LanguageProfile): BlockedWordStore {
            check(Looper.myLooper() == Looper.getMainLooper())
            return stores.getOrPut(language.code) { BlockedWordStore(context, language) }
        }
    }
}
