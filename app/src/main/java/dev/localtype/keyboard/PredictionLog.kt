package dev.localtype.keyboard

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The opt-in typing log. One worker thread owns the file and the [PredictionLogSession] state
 * machine; the keyboard's main thread only builds immutable events and posts them here, so it
 * never waits on a write. Every block is appended whole with open-append-close, the file lives in
 * noBackupFilesDir so it never reaches a backup, and it leaves the phone only through [export].
 */
object PredictionLog {
    /** Writing stops here; the settings screen says so and offers export and clear. */
    const val MAX_BYTES = 8L * 1024 * 1024
    private const val FILE_NAME = "prediction_log.tsv"
    private const val PREFS = "keyboard_state"
    /** Per-install file counter, bumped every time the file is created; the header's #file. */
    private const val KEY_FILE = "prediction_log_file"
    /** When the current file was created, for the settings status line only; never exported. */
    private const val KEY_STARTED = "prediction_log_started_ms"
    /** The last field number written, so numbering keeps running across process restarts. */
    private const val KEY_FIELD = "prediction_log_field"
    /** The file's header names another prior, so settings says so before this process tries to write again. */
    private const val KEY_STALE = "prediction_log_stale"
    private const val KEY_CHARS = 200
    private const val CONTEXT_CHARS = 160
    private const val CANDIDATES = 6

    /**
     * [stale]: the file was started under another ranking setup or by another build, so nothing more is written to it
     * until it's cleared. The verdict is kept in preferences, so it outlives the process that reached it.
     */
    class Status(val bytes: Long, val startedAtMs: Long, val full: Boolean, val stale: Boolean)

    /** True until the main thread posts a snapshot, and again after [clear], so a file always opens with tables. */
    @Volatile private var snapshotPending = true

    /** Bumped by [clear] on the main thread; the keyboard compares it before every offer so a clear mid-field reopens cleanly. */
    @Volatile var logGeneration = 0
        private set

    /** The worker hid a mutation (an X row with no word), so the keyboard's model must be rebuilt before the next snapshot. */
    private val hiddenMutation = AtomicBoolean(false)
    @Volatile private var full = false
    @Volatile private var stale = false
    @Volatile private var app: Context? = null

    private val worker: Handler by lazy { Handler(HandlerThread("localtype-log").apply { start() }.looper) }

    // Worker-thread state: touched only from posted jobs.
    private val session = PredictionLogSession(sink = ::write, keyChars = KEY_CHARS, contextChars = CONTEXT_CHARS)
    private var snapWritten = false
    private var droppedBlocks = 0
    private var fieldSeq = 0
    private var dictSizes = "0,0"
    /** The prior, seeds, neural mode and optional correction policy/settings of the field. */
    private var rankingHeader = PredictionLogCodec.priorHeader(null, AdaptiveNgramModel.PriorWeights.DEFAULT) + PredictionLogCodec.neuralHeader(null)
    /** This process has compared the file's header with [rankingHeader], or created the file itself. */
    private var headerChecked = false

    // region Keyboard (main thread)

    fun needsSnapshot(): Boolean = snapshotPending

    /**
     * A freshly built model's tables, written before the field they belong to. [reason] is one of the
     * SNAP_ constants. [priorSha256] and [priorWeights] name the corpus prior the model ranks with
     * (null for none); they never change what is learned, so they aren't in the tables, only in the header.
     */
    fun snapshot(
        context: Context,
        reason: String,
        tables: AdaptiveNgramModel.Tables,
        dictWords: Int,
        dictWordsWithProfanity: Int,
        priorSha256: String?,
        priorWeights: AdaptiveNgramModel.PriorWeights,
        neuralSha256: String? = null,
        correctionPolicy: String? = null,
        autocorrect: Boolean = true,
        webAutocorrect: Boolean = true,
        vocabularyHeader: Map<String, String> = CurrentVocabulary.EMPTY.header(),
        personalLearning: String? = null,
    ) {
        app = context.applicationContext
        snapshotPending = false
        worker.post {
            dictSizes = "$dictWords,$dictWordsWithProfanity"
            val ranking = PredictionLogCodec.priorHeader(priorSha256, priorWeights) + PredictionLogCodec.neuralHeader(neuralSha256) +
                mapOf("addedWords" to AddedWords.VERSION) +
                vocabularyHeader +
                (personalLearning?.let { mapOf("personalLearning" to it) } ?: emptyMap()) +
                (correctionPolicy?.let { PredictionLogCodec.correctionHeader(it, autocorrect, webAutocorrect) }
                    ?: rankingHeader.filterKeys { it in PredictionLogCodec.CORRECTION_KEYS })
            // A different ranking or correction configuration requires another header comparison.
            if (ranking != rankingHeader) {
                // A restart may not have finished the old field; flush it with its original header.
                session.fieldEnd()
                headerChecked = false
            }
            rankingHeader = ranking
            // A new or cleared file says so, whatever the keyboard's reason was. So does one that replaces a file with no header.
            val fresh = app?.let { !checkedFile(it).exists() } ?: false
            session.snapshot(if (fresh) PredictionLogCodec.SNAP_FILE else reason, tables.unigrams, tables.bigrams)
        }
    }

    fun beginField(
        restart: Boolean,
        autocorrect: Boolean,
        profanity: Boolean,
        model: Int,
        emoji: Boolean,
        neuralSha256: String? = null,
        correctionPolicy: String? = null,
        webAutocorrect: Boolean = true,
        configuredAutocorrect: Boolean = autocorrect,
        vocabularyHeader: Map<String, String> = CurrentVocabulary.EMPTY.header(),
        personalLearning: String? = null,
    ) {
        worker.post {
            // Close an earlier field using its header before checking the new field's mode. The
            // check is needed even when the caller reused a clean snapshot instead of posting one.
            session.fieldEnd()
            val ranking = rankingHeader + PredictionLogCodec.neuralHeader(neuralSha256) + mapOf("addedWords" to AddedWords.VERSION) +
                vocabularyHeader +
                (personalLearning?.let { mapOf("personalLearning" to it) } ?: emptyMap()) +
                (correctionPolicy?.let { PredictionLogCodec.correctionHeader(it, configuredAutocorrect, webAutocorrect) }
                    ?: rankingHeader.filterKeys { it in PredictionLogCodec.CORRECTION_KEYS })
            if (ranking != rankingHeader) headerChecked = false
            rankingHeader = ranking
            app?.let(::checkedFile)
            session.fieldStart(nextFieldSeq(), restart, autocorrect, profanity, model, emoji, neuralSha256)
        }
    }

    fun offer(event: OfferEvent) {
        worker.post { session.offer(event) }
    }

    fun tap(event: TapEvent) {
        worker.post { session.tap(event) }
    }

    fun tapEmoji(index: Int) {
        worker.post { session.tapEmoji(index) }
    }

    fun typed(event: TypedEvent) {
        worker.post { session.typed(event) }
    }

    fun repair(event: RepairEvent) {
        worker.post { session.repair(event) }
    }

    fun endField() {
        worker.post { session.fieldEnd() }
    }

    /** True once after the worker hid a mutation; the caller rebuilds its model so the next snapshot is clean. */
    fun takeHiddenMutation(): Boolean = hiddenMutation.getAndSet(false)

    // endregion

    // region Settings

    fun status(context: Context): Status {
        val file = file(context)
        val exists = file.exists()
        val bytes = if (exists) file.length() else 0L
        val prefs = prefs(context)
        val staleFile = exists && (stale || prefs.getBoolean(KEY_STALE, false))
        return Status(bytes, prefs.getLong(KEY_STARTED, 0L), full || bytes >= MAX_BYTES, staleFile)
    }

    /**
     * Copies the file to [uri] after every pending write; [onDone] runs on the worker. The picker can
     * hand back an existing file to replace, and plain "w" may keep its old tail past the new end, so
     * the copy opens with "wt" and falls back to "w" only for a provider that refuses truncation.
     */
    fun export(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.post {
            val ok = runCatching {
                val resolver = app.contentResolver
                val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrElse { resolver.openOutputStream(uri, "w") }
                stream?.use { out -> file(app).inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            onDone(ok)
        }
    }

    /**
     * Deletes the file. The generation and snapshot flags change on the caller's thread, so a
     * keyboard open right now reopens with fresh tables before its next row; [onDone] runs on the worker.
     */
    fun clear(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        logGeneration++
        snapshotPending = true
        worker.post {
            session.reset()
            file(app).delete()
            prefs(app).edit().remove(KEY_STARTED).remove(KEY_FIELD).remove(KEY_STALE).apply()
            snapWritten = false
            droppedBlocks = 0
            fieldSeq = 0
            full = false
            stale = false
            headerChecked = false
            onDone()
        }
    }

    // endregion

    // region Worker

    /** The session's sink: one block of records, appended whole or dropped whole. */
    private fun write(records: List<LogRecord>) {
        val app = app ?: return
        if (records.isEmpty()) return
        // A mutation the file cannot show still changed the model on the device.
        if (records.any { it is LogRecord.Mutation && it.word.isEmpty() }) hiddenMutation.set(true)
        // Checked first, so a block arriving just after a file with no header was deleted can't open the new one.
        val file = checkedFile(app)
        val snapshot = records.first() is LogRecord.Snapshot
        if (!snapshot && !snapWritten) {
            droppedBlocks++
            return
        }
        if (full || stale) return
        var block = PredictionLogCodec.encodeBlock(records)
        if (snapshot && droppedBlocks > 0) {
            block += PredictionLogCodec.encodeBlock(listOf(LogRecord.Dropped(droppedBlocks)))
            droppedBlocks = 0
        }
        val bytes = block.toByteArray()
        if (!file.exists()) createFile(app, file)
        if (file.length() + bytes.size > MAX_BYTES) {
            full = true
            return
        }
        FileOutputStream(file, true).use { it.write(bytes) }
        if (snapshot) snapWritten = true
    }

    private fun createFile(app: Context, file: File) {
        val prefs = prefs(app)
        val number = prefs.getInt(KEY_FILE, 0) + 1
        fieldSeq = 0
        val header = PredictionLogCodec.HEADER_KEYS.filter { (it !in PredictionLogCodec.CORRECTION_KEYS && it != "personalLearning") || it in rankingHeader }.associateWith { key ->
            when (key) {
                "app" -> BuildConfig.VERSION_NAME
                "code" -> BuildConfig.VERSION_CODE.toString()
                "flavor" -> PredictionLogCodec.FLAVOR
                "sdk" -> Build.VERSION.SDK_INT.toString()
                "file" -> number.toString()
                "dict" -> dictSizes
                "contextScope" -> "verified-sentence"
                "contextChars" -> CONTEXT_CHARS.toString()
                "keyChars" -> KEY_CHARS.toString()
                "candidates" -> CANDIDATES.toString()
                else -> rankingHeader[key].orEmpty()
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(PredictionLogCodec.encodeHeader(header))
        headerChecked = true
        // The file's own creation time feeds the settings status line ("since Sep 20"); it never enters the file.
        prefs.edit().putInt(KEY_FILE, number).putLong(KEY_STARTED, file.lastModified()).remove(KEY_FIELD).remove(KEY_STALE).apply()
    }

    /**
     * The log file, after its header has been compared with [rankingHeader] once per process and
     * again after the prior or neural mode changes. Replaying it needs one ranking setup
     * and one build throughout, so a mismatched file (another setup, or an update) becomes [stale] and stays
     * as it is until it's cleared. A file with no `#localtype-log` line (created, then its header
     * write failed) can't be read at all, so it's deleted and the next write starts a new one.
     */
    private fun checkedFile(app: Context): File {
        val file = file(app)
        if (!file.exists()) return file
        val prefs = prefs(app)
        // Once frozen, an existing file stays frozen across mode changes and process restarts.
        if (prefs.getBoolean(KEY_STALE, false)) stale = true
        if (headerChecked) return file
        headerChecked = true
        // A read that fails leaves the file alone and stops writing to it, as a mismatch does.
        val header = runCatching { file.bufferedReader().use(PredictionLogCodec::readHeader) }.getOrNull()
        when {
            header != null && PredictionLogCodec.MAGIC_KEY !in header -> {
                file.delete()
                // The new file needs a snapshot of its own before any other block.
                snapWritten = false
            }
            header == null || !PredictionLogCodec.sameRanking(header, rankingHeader) ||
                !PredictionLogCodec.sameBuild(header, BuildConfig.VERSION_CODE) -> {
                stale = true
                prefs.edit().putBoolean(KEY_STALE, true).apply()
            }
        }
        return file
    }

    private fun nextFieldSeq(): Int {
        val app = app
        if (fieldSeq == 0 && app != null) fieldSeq = prefs(app).getInt(KEY_FIELD, 0)
        fieldSeq++
        app?.let { prefs(it).edit().putInt(KEY_FIELD, fieldSeq).apply() }
        return fieldSeq
    }

    // endregion

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
