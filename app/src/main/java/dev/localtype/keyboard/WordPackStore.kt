package dev.localtype.keyboard

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The word packs that ship in the APK, in every flavor: one switch each, off until the person turns it on. */
object WordPackCatalog {
    class Bundled(val id: String, val language: String, val asset: String, val title: Int, val summary: Int, val body: Int)

    val BUNDLED: List<Bundled> = listOf(
        Bundled("medical", "en", "pack_medical_en.txt", R.string.pack_medical_title, R.string.pack_medical_summary, R.string.pack_medical_body),
        Bundled("politics", "en", "pack_politics_en.txt", R.string.pack_politics_title, R.string.pack_politics_summary, R.string.pack_politics_body),
    )

    fun bundled(id: String): Bundled? = BUNDLED.firstOrNull { it.id == id }
}

/**
 * Bundled and imported word packs, each parsed once per process off the main thread and shared by settings and the
 * keyboard (both run in the main process). Imported packs live in `noBackupFilesDir/word_packs`, written as a whole
 * file or not at all, re-encoded in the pack format so they are read back strictly. Nothing here touches the network.
 */
class WordPackStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val cache = ConcurrentHashMap<String, WordPack>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "localtype-packs").apply { isDaemon = true } }
    private val folder: File get() = File(app.noBackupFilesDir, FOLDER)

    /** The loaded packs for [ids], in order, or null when one of them still has to be read. */
    fun cached(ids: List<String>): List<WordPack>? = ids.map { cache[it] ?: return null }

    /** Reads whatever of [ids] isn't loaded yet, on the store's worker, then calls [done] there with every pack that could be read. */
    fun load(ids: List<String>, done: (List<WordPack>) -> Unit) {
        worker.execute { done(ids.mapNotNull { id -> cache[id] ?: read(id)?.also { cache[id] = it } }) }
    }

    /** Runs [task] on the store's worker, after any load already queued. */
    fun background(task: () -> Unit) = worker.execute(task)

    private fun read(id: String): WordPack? = runCatching {
        val bundled = WordPackCatalog.bundled(id)
        if (bundled != null) {
            WordPack.strict(app.assets.open(bundled.asset).bufferedReader().use { it.readText() })
        } else {
            val file = File(folder, "$id.txt")
            if (!file.isFile || file.length() > WordPack.MAX_CHARS * 4L) return null
            WordPack.strict(file.readText(), imported = true).takeIf { it.id == id }
        }
    }.getOrNull()

    /** Writes [pack] as its own file, replacing an earlier import with its id, and caches what was written. Worker thread or IO only. */
    fun save(pack: WordPack, sources: List<String>): WordPack? = runCatching {
        folder.mkdirs()
        val text = pack.encode(sources)
        val stored = WordPack.strict(text, imported = true)
        val file = AtomicFile(File(folder, "${pack.id}.txt"))
        val out = file.startWrite()
        try {
            out.write(text.encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        cache[stored.id] = stored
        stored
    }.getOrNull()

    /** Deletes an imported pack's file and forgets it. Worker thread or IO only. */
    fun remove(id: String) {
        if (WordPackCatalog.bundled(id) != null) return
        cache.remove(id)
        AtomicFile(File(folder, "$id.txt")).delete()
    }

    companion object {
        const val FOLDER = "word_packs"

        @Volatile private var instance: WordPackStore? = null

        fun get(context: Context): WordPackStore = instance ?: synchronized(this) {
            instance ?: WordPackStore(context).also { instance = it }
        }
    }
}
