package dev.localtype.keyboard

import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A value read once on a background thread. [await] holds the caller for at most the time given, and once one wait
 * has run out, later calls stop waiting for this value and answer with what there is.
 */
internal class Arrival<T : Any> {
    @Volatile private var value: T? = null
    private val done = CountDownLatch(1)
    @Volatile private var waitedOut = false

    /** The first call settles it; [found] is null when the read failed. */
    fun arrive(found: T?) {
        synchronized(this) {
            if (done.count == 0L) return
            value = found
            done.countDown()
        }
    }

    fun await(waitMillis: Long): T? {
        if (waitMillis > 0 && !waitedOut && done.count > 0) {
            val arrived = try {
                done.await(waitMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                true
            }
            if (!arrived) waitedOut = true
        }
        return value
    }
}

/**
 * The spell checker's lists, owned by one service instance: read once per language on a background thread the
 * first time a session asks, and let go in [release], after which nothing is read again. [get] with no wait answers
 * null until the read finishes; with a wait it holds the calling binder thread for at most that long, and only the
 * first time (see [Arrival]). A [SpellCheckRules] is immutable, so the binder threads that sessions run on share one.
 */
internal class SpellCheckLists(private val open: (String) -> Reader) {
    class Lists(val rules: SpellCheckRules, val current: AcceptedWords)

    private val loads = HashMap<String, Arrival<Lists>>()
    private var released = false

    fun get(language: LanguageProfile, waitMillis: Long = 0): Lists? {
        val load = synchronized(this) {
            if (released) return null
            loads.getOrPut(language.code) {
                Arrival<Lists>().also { arrival ->
                    // A list that cannot be read leaves the language unanswered for this binding; nothing is retried.
                    thread(name = "localtype-spellcheck", isDaemon = true) { arrival.arrive(runCatching { build(language, open) }.getOrNull()) }
                }
            }
        }
        return load.await(waitMillis)
    }

    /**
     * Android unbound the service: let the lists go. A read still running finishes into a holder nobody keeps, a
     * session still answering keeps its own reference until it returns, and a later request gets nothing.
     */
    fun release() = synchronized(this) {
        released = true
        loads.clear()
    }

    companion object {
        /** The longest a request waits for the lists. The slowest read measured on a phone was 2.4 seconds. */
        const val WAIT_MILLIS = 5_000L
        private const val MODERN_ASSET = "words_en_modern.txt"

        /**
         * Builds a language's rules from the bundled lists, streaming each, the way the keyboard's `loadDictionary`
         * reads them: the suggestion list with English's modern additions, the recognized words with the modern
         * additions' words, the swear list, and for English the current words, which are known and never suggested.
         * The same as [SpellCheckRules.fromLines] on the same lines.
         */
        fun build(language: LanguageProfile, open: (String) -> Reader): Lists {
            fun <T> read(asset: String, block: (Sequence<String>) -> T): T = open(asset).buffered().useLines(block)
            val modern = if (language.english) read(MODERN_ASSET) { it.toList() } else emptyList()
            val words = read(language.wordsAsset) { WordDictionary.parse(it + modern, language) }
            val lexicon = read(language.lexiconAsset) { lines -> Lexicon.parse(lines + modern.asSequence().map { it.substringBefore('\t') }) }
            val swears = read(language.profanityAsset) { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t').trim() }.toList()
            }
            // As in the keyboard, a current-words file that does not parse is left out and the rest still loads.
            val current = if (!language.english) AcceptedWords.EMPTY else runCatching {
                AcceptedWords.of(CurrentVocabulary.parse(open(CurrentVocabulary.ASSET).use { it.readText() }).words)
            }.getOrDefault(AcceptedWords.EMPTY)
            return Lists(SpellCheckRules(language, words, lexicon, swears), current)
        }
    }
}

/**
 * Android's user dictionary, as words for each checked language, held in memory only for one service instance.
 * [start] reads it once; [refresh] reads it again (the service calls it when the dictionary changes). Reads run in
 * order on one thread, so the newest read is the one kept. A read that fails ([read] returns null or throws) keeps
 * what was there, which at first is nothing, so the checker answers as it would without the dictionary.
 */
internal class UserDictionaryWords(private val read: () -> List<UserWord>?) {
    private val first = Arrival<Map<String, AcceptedWords>>()
    @Volatile private var latest: Map<String, AcceptedWords> = emptyMap()
    @Volatile private var released = false
    private var started = false
    private val reader: ExecutorService = Executors.newSingleThreadExecutor { work ->
        Thread(work, "localtype-userwords").apply { isDaemon = true }
    }

    fun start() {
        synchronized(this) {
            if (started || released) return
            started = true
        }
        refresh()
    }

    fun refresh() {
        if (released) return
        try {
            reader.execute {
                val rows = try { read() } catch (_: RuntimeException) { null }
                val words = rows?.let { found -> SpellCheckRules.LANGUAGES.associate { it.code to AcceptedWords.forLanguage(found, it) } }
                if (words != null && !released) latest = words
                first.arrive(latest)
            }
        } catch (_: RejectedExecutionException) {
            // Released in the meantime.
        }
    }

    /** The accepted words for [language], waiting for the first read for at most [waitMillis], once. */
    fun words(language: LanguageProfile, waitMillis: Long = 0): AcceptedWords {
        if (released) return AcceptedWords.EMPTY
        first.await(waitMillis)
        if (released) return AcceptedWords.EMPTY
        return latest[language.code] ?: AcceptedWords.EMPTY
    }

    fun release() {
        released = true
        latest = emptyMap()
        reader.shutdownNow()
        first.arrive(null)
    }

    companion object {
        /** The longest the first request waits for the dictionary; reading it usually takes milliseconds. */
        const val WAIT_MILLIS = 1_000L
    }
}
