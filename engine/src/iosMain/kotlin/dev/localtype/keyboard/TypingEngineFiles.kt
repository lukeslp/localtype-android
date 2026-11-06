package dev.localtype.keyboard

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * How the iPhone keyboard opens and saves a [TypingEngine]: from the word lists and priors bundled with it,
 * and a learned-pair journal in its own sandbox. Swift passes paths and gets plain answers back.
 */
object TypingEngineFiles {
    /** The language codes the engine has word lists for, English first (not the IPA symbols keyboard). */
    val languages: List<String> get() = LanguageProfile.WITH_WORDS.map { it.code }

    /** [languageCode]'s name in itself, for the space bar and the chooser. */
    fun nativeName(languageCode: String): String = profile(languageCode).nativeName

    /** [languageCode]'s letter rows, top first, for drawing the keys before its engine has loaded. */
    fun keyRows(languageCode: String): List<String> = profile(languageCode).keyRows

    /** What a hold on [letter] offers in [languageCode], as [TypingEngine.holds]. */
    fun holds(languageCode: String, letter: String): List<String> =
        letter.singleOrNull()?.let { LetterAccents.holds(profile(languageCode), it.lowercaseChar()) }.orEmpty()

    /** Whether [languageCode] opens a question or exclamation with ¿ or ¡. */
    fun hasOpeningMarks(languageCode: String): Boolean = profile(languageCode).openingMarks.isNotEmpty()

    /**
     * Opens [languageCode] from the assets in [directory], with the journal at [journalPath] and the tap offsets
     * at [offsetsPath] if there are any. A damaged journal is ignored rather than failing the keyboard; a missing
     * word list throws.
     */
    @OptIn(NativeRuntimeApi::class)
    @Throws(Exception::class)
    fun open(directory: String, languageCode: String, journalPath: String?, offsetsPath: String?, suggestSwears: Boolean): TypingEngine {
        val language = profile(languageCode)
        fun text(name: String): String = read("$directory/$name")?.decodeToString() ?: error("missing $name")
        val journal = journalPath?.let { read(it) }
        val engine = TypingEngine(
            language = language,
            words = text(language.wordsAsset),
            swears = read("$directory/${language.profanityAsset}")?.decodeToString(),
            lexicon = text(language.lexiconAsset),
            modern = if (language.english) read("$directory/words_en_modern.txt")?.decodeToString() else null,
            prior = language.priorAsset?.let { read("$directory/$it") },
            journal = journal?.takeIf { runCatching { LearnedPairJournal.decode(it) }.isSuccess },
            suggestSwears = suggestSwears,
            emoji = if (language.english) read("$directory/emoji_en.txt")?.decodeToString() else null,
            offsets = offsetsPath?.let { read(it) }?.decodeToString(),
        )
        // Collect once the heap is a quarter over what's live, not (Kotlin's default) double it: with about 15 MB
        // live, the default let typing leave up to 15 MB of garbage, and one simulator run peaked at 51 MB.
        GC.targetHeapUtilization = HEAP_UTILIZATION
        // Free the file text and parsing leftovers now; a keyboard extension is held to a small memory limit.
        // This collection also sets the next target from the utilization above.
        GC.collect()
        return engine
    }

    /** The share of the Kotlin heap that may be live before the next collection (Kotlin's default is 0.5). */
    private const val HEAP_UTILIZATION = 0.8

    /**
     * Opens Chime from `cifg_int8.bin` in [directory], checked against its SHA-256. Throws when the file is
     * missing or isn't the bundled export, and the keyboard then goes on without it.
     */
    @OptIn(NativeRuntimeApi::class)
    @Throws(Exception::class)
    fun openChime(directory: String): ChimePredictor {
        val chime = ChimePredictor.verified(read("$directory/${ChimePredictor.FILE_NAME}") ?: error("missing ${ChimePredictor.FILE_NAME}"))
        GC.collect()
        return chime
    }

    /** Saves [engine]'s learned pairs to [journalPath] and its tap offsets to [offsetsPath], replacing each file whole. */
    fun save(engine: TypingEngine, journalPath: String, offsetsPath: String): Boolean =
        write(engine.journal(), journalPath) && write(engine.offsets().encodeToByteArray(), offsetsPath)

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun write(bytes: ByteArray, path: String): Boolean {
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        return data.writeToFile(path, atomically = true)
    }

    /** Deletes the file at [journalPath] (a journal or tap offsets), if any. */
    @OptIn(ExperimentalForeignApi::class)
    fun delete(journalPath: String): Boolean =
        !NSFileManager.defaultManager.fileExistsAtPath(journalPath) || NSFileManager.defaultManager.removeItemAtPath(journalPath, null)

    private fun profile(code: String): LanguageProfile = LanguageProfile.of(code) ?: error("no language $code")

    @OptIn(ExperimentalForeignApi::class)
    private fun read(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val size = data.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }
}
