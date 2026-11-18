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

    /** Every keyboard the engine can draw: the languages with word lists, then the IPA symbols keyboard. */
    val keyboards: List<String> get() = LanguageProfile.ALL.map { it.code }

    /** What the keys need to know about [languageCode] before, or without, its engine: rows, direction and special keys. */
    class Layout(
        val rows: List<String>,
        /** Letter keys across the widest row: 10, or 11 for Arabic script. */
        val columns: Int,
        /** No Shift key and no capitals. */
        val shiftless: Boolean,
        val rightToLeft: Boolean,
        /** Delete ends the bottom row instead of the top one (a shiftless language whose top row is full). */
        val deleteEndsBottomRow: Boolean,
        /** A row of symbols above the letters (IPA), or empty. */
        val topRow: String,
        /** The language's comma ("،" in Arabic script). */
        val comma: String,
        /** What the period's hold offers first in this language, or empty for the usual marks. */
        val periodHolds: List<String>,
        /** The language's own digits, 1 to 0, offered on the digits' holds; or empty. */
        val digits: String,
        /** A key beside the space bar types the zero-width non-joiner (Persian). */
        val joinerKey: Boolean,
        /** Loads word lists: false for the IPA symbols keyboard, which suggests, corrects and learns nothing. */
        val words: Boolean,
    )

    fun layout(languageCode: String): Layout = profile(languageCode).let {
        Layout(it.keyRows, it.columns, it.shiftless, it.rightToLeft, it.deleteEndsBottomRow, it.topRow, it.comma, it.periodHolds, it.digits,
            it.joinerKey, it.words)
    }

    /** What the person switched on, as file names in the bundle and paths in the keyboard's sandbox. */
    class Choices(
        /** Spelling: British (English). */
        val british: Boolean = false,
        /** Bundled word pack files switched on ("pack_medical_en.txt"). */
        val packs: List<String> = emptyList(),
        /** Blocked words, as the app's settings list them. */
        val blocked: List<String> = emptyList(),
        /** Where the learned capitals are kept, beside the journal. */
        val spellingsPath: String? = null,
        /** Today, in days since 1970-01-01. */
        val epochDay: Long = 0,
        /** Learn my phrases: where the phrases are kept, beside the journal. Null is the switch off. */
        val personalPath: String? = null,
    )

    /** [languageCode]'s name in itself, for the space bar and the chooser. */
    fun nativeName(languageCode: String): String = profile(languageCode).nativeName

    /** [languageCode]'s letter rows, top first, for drawing the keys before its engine has loaded. */
    fun keyRows(languageCode: String): List<String> = profile(languageCode).keyRows

    /** What a hold on [letter] offers in [languageCode], as [TypingEngine.holds]. */
    fun holds(languageCode: String, letter: String): List<String> =
        letter.singleOrNull()?.let { LetterAccents.holds(profile(languageCode), it.lowercaseChar()) }.orEmpty()

    /**
     * As [holds] for a top-row letter that also holds a digit: the language's own accents first, then [digits] (the
     * Western digit, then the script's own), then the shared accents, in Android's order.
     */
    fun holds(languageCode: String, letter: String, digits: List<String>): List<String> =
        letter.singleOrNull()?.let { LetterAccents.holds(profile(languageCode), it.lowercaseChar(), digits) }.orEmpty()

    /** Whether [languageCode] opens a question or exclamation with ¿ or ¡. */
    fun hasOpeningMarks(languageCode: String): Boolean = profile(languageCode).openingMarks.isNotEmpty()

    /**
     * Opens [languageCode] from the assets in [directory], with the journal at [journalPath] and the tap offsets
     * at [offsetsPath] if there are any. A damaged journal is ignored rather than failing the keyboard; a missing
     * word list throws.
     */
    @OptIn(NativeRuntimeApi::class)
    @Throws(Exception::class)
    fun open(directory: String, languageCode: String, journalPath: String?, offsetsPath: String?, suggestSwears: Boolean,
             choices: Choices): TypingEngine {
        val language = profile(languageCode)
        require(language.words) { "$languageCode has no word lists" }
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
            options = TypingEngine.Options(
                british = if (choices.british && language.english) read("$directory/${BritishSpelling.ASSET}")?.decodeToString() else null,
                packs = choices.packs.mapNotNull { read("$directory/$it")?.decodeToString() },
                blocked = choices.blocked,
                vocabulary = if (language.english) read("$directory/${CurrentVocabulary.ASSET}")?.decodeToString() else null,
                spellings = choices.spellingsPath?.let { read(it) }?.decodeToString(),
                epochDay = choices.epochDay,
                personalLearning = choices.personalPath != null,
            ),
        )
        // Collect once the heap is a quarter over what's live, not (Kotlin's default) double it: with about 15 MB
        // live, the default let typing leave up to 15 MB of garbage, and one simulator run peaked at 51 MB.
        GC.targetHeapUtilization = HEAP_UTILIZATION
        // Free the file text and parsing leftovers now; a keyboard extension is held to a small memory limit.
        // This collection also sets the next target from the utilization above.
        GC.collect()
        // The phrases are read after that collection: checking a snapshot builds a second copy of it for a moment,
        // which then stays out of the load's peak. A missing file is no phrases yet; a damaged one is ignored.
        choices.personalPath?.let { read(it) }?.let {
            engine.restorePersonal(it)
            GC.collect()
        }
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

    /**
     * Saves [engine]'s learned pairs to [journalPath], its tap offsets to [offsetsPath], its learned capitals to
     * [spellingsPath] and, with Learn my phrases on, its phrases to [personalPath], replacing each file whole. The
     * phrases are written only when one changed since they were read or last saved: writing them checks them all.
     */
    fun save(engine: TypingEngine, journalPath: String, offsetsPath: String, spellingsPath: String, personalPath: String?): Boolean =
        write(engine.journal(), journalPath) && write(engine.offsets().encodeToByteArray(), offsetsPath) &&
            write(engine.spellings().encodeToByteArray(), spellingsPath) &&
            (personalPath == null || !engine.personalChanged || savePersonal(engine, personalPath))

    private fun savePersonal(engine: TypingEngine, path: String): Boolean {
        if (!write(engine.personal(), path)) return false
        engine.personalSaved()
        return true
    }

    /**
     * Opens Remember sites I type from `tlds.txt` in [directory] and the list saved at [path], if any. Without the
     * list of endings nothing is ever a site, so nothing is remembered.
     */
    fun openSites(directory: String, path: String): TypingEngine.Sites =
        TypingEngine.Sites(read("$directory/tlds.txt")?.decodeToString().orEmpty(), read(path)?.decodeToString())

    /** Saves [sites] to [path], replacing the file whole. */
    fun saveSites(sites: TypingEngine.Sites, path: String): Boolean {
        if (!write(sites.text().encodeToByteArray(), path)) return false
        sites.saved()
        return true
    }

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
