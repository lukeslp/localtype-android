package dev.localtype.keyboard

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/**
 * Saved text on disk: one small file in noBackupFilesDir, so neither backup nor a device-to-device
 * transfer copies it (snippets can hold addresses and numbers). One worker thread reads it and
 * writes it, in order, and each write replaces the whole file through [AtomicFile], so a crash
 * mid-write leaves the previous list. The keyboard and settings share one [SavedTexts] per process
 * ([open]), so Remove all saved text in settings reaches an open keyboard at once.
 */
class SavedTextStore private constructor(private val file: AtomicFile) : SavedTexts.Store {
    /** On the worker, once, while [open] builds the list. No file yet, or one that can't be read, is an empty list. */
    override fun load(): List<SavedTexts.Entry> = try {
        SavedTextCodec.decodeEntries(String(file.readFully(), Charsets.UTF_8))
    } catch (_: IOException) {
        emptyList()
    }

    override fun save(entries: List<SavedTexts.Entry>) {
        worker.post { write(entries) }
    }

    private fun write(entries: List<SavedTexts.Entry>) {
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        val bytes = SavedTextCodec.encodeEntries(entries).toByteArray(Charsets.UTF_8)
        val out = try {
            file.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (_: IOException) {
            file.failWrite(out)
        }
    }

    companion object {
        private const val FILE_NAME = "saved_text.txt"

        private val worker: Handler by lazy { Handler(HandlerThread("localtype-saved-text").apply { start() }.looper) }
        private val main = Handler(Looper.getMainLooper())

        // Main thread only.
        private var shared: SavedTexts? = null
        private val waiting = ArrayList<(SavedTexts) -> Unit>()

        /** Calls [onReady] on the main thread with this process's saved text, reading the file first the first time. */
        fun open(context: Context, onReady: (SavedTexts) -> Unit) {
            shared?.let { return onReady(it) }
            waiting += onReady
            if (waiting.size > 1) return
            val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
            worker.post {
                val texts = SavedTexts(SavedTextStore(file))
                main.post {
                    shared = texts
                    val ready = waiting.toList()
                    waiting.clear()
                    ready.forEach { it(texts) }
                }
            }
        }
    }
}
