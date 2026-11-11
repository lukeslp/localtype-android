package dev.localtype.keyboard

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Gathers a [KeyboardCopy] from this phone's stores, and puts one into them. Main thread, like the stores; each
 * store checks what it is handed and does its own writing. Nothing here opens a connection: the copy goes to and
 * comes from the file the person picks in Android's file picker.
 *
 * Reading a copy adds to what is here. Lists and learned words merge; settings in the copy replace the same
 * settings on this phone. A word this phone blocks is never taken in, as an added word or a learned one.
 */
object CopyTransfer {
    /** What the person can tick. Learned is everything Forget learned words removes, apart from the touch offsets. */
    enum class Part { SETTINGS, WORDS, SAVED, LEARNED }

    /** How much of a copy was taken in. [incomplete] when a list here was busy, unreadable or could not be written. */
    data class Applied(
        val settings: Int = 0, val added: Int = 0, val blocked: Int = 0, val saved: Int = 0, val pairs: Int = 0, val sites: Int = 0,
        val incomplete: Boolean = false,
    )

    /**
     * Calls [done] once every [hold] has been released, or with false after [LIMIT_MS] when a list never answers
     * (a file that cannot be read is retried without end). [late] runs if the rest then finish after all.
     */
    private class Gate(private val late: () -> Unit = {}, private val done: (Boolean) -> Unit) {
        private var open = 1
        private var finished = false
        private val handler = Handler(Looper.getMainLooper())
        private val timeout = Runnable { finish(false) }

        init { handler.postDelayed(timeout, LIMIT_MS) }

        fun hold(): () -> Unit {
            open++
            return ::release
        }

        fun release() {
            if (--open > 0) return
            if (finished) late() else finish(true)
        }

        private fun finish(complete: Boolean) {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeout)
            done(complete)
        }
    }

    /**
     * The chosen [parts] of this phone, as they are once every store has loaded. [made] is today, yyyy-MM-dd.
     * Null when a list could not be read: a copy that silently lacks part of what was ticked is worse than none.
     */
    fun collect(context: Context, parts: Set<Part>, made: String, done: (KeyboardCopy?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = context.applicationContext
        val words = ArrayList<KeyboardCopy.Words>()
        val learned = ArrayList<KeyboardCopy.LearnedLanguage>()
        var saved: List<KeyboardCopy.Saved>? = null
        var sites = emptyList<KeyboardCopy.Site>()
        var failed = false
        val gate = Gate { complete ->
            if (!complete || failed) return@Gate done(null)
            val emoji = PreferenceEmojiStore(app)
            done(
                KeyboardCopy(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    made = made,
                    settings = if (Part.SETTINGS !in parts) null else KeyboardCopy.Settings(
                        KeyboardSettings.portable(app),
                        emoji.tones().entries.sortedBy { it.key }.map { KeyboardCopy.Tone(it.key, it.value) },
                        emoji.defaultTone(),
                    ),
                    words = if (Part.WORDS in parts) words.filter { it.added.isNotEmpty() || it.blocked.isNotEmpty() } else null,
                    saved = saved,
                    learned = if (Part.LEARNED !in parts) null else KeyboardCopy.Learned(
                        learned.filter { it.pairs.isNotEmpty() || it.contexts.isNotEmpty() || it.spellings.isNotEmpty() }, sites, emoji.recent(),
                    ),
                ),
            )
        }
        if (Part.WORDS in parts || Part.LEARNED in parts) for (language in LanguageProfile.WITH_WORDS) {
            // A language never used here has no files, and opening its stores would make them.
            val held = JournalLearningStore.exists(app, language) || PersonalLearningStore.exists(app, language)
            if (!held && !BlockedWordStore.exists(app, language)) continue
            val blockedStore = BlockedWordStore.get(app, language)
            val release = gate.hold()
            blockedStore.whenLoaded {
                if (!blockedStore.ready) failed = true
                val blocked = if (blockedStore.ready) blockedStore.words.sorted() else emptyList()
                if (!held) {
                    if (Part.WORDS in parts && blocked.isNotEmpty()) words += KeyboardCopy.Words(language.code, emptyList(), blocked)
                    return@whenLoaded release()
                }
                val journal = JournalLearningStore.get(app, language)
                journal.whenReady {
                    val entries = journal.entries()
                    val added = AddedWords.words(entries)
                    val spellings = WordSpellingStore.get(app, language).spellings
                    if (Part.WORDS in parts) {
                        words += KeyboardCopy.Words(language.code, added.map { KeyboardCopy.Added(it, spellings[it]) }, blocked)
                    }
                    if (Part.LEARNED !in parts) return@whenReady release()
                    val known = added.toHashSet()
                    val pairs = entries.filter { it.first != AddedWords.CONTEXT }.map { KeyboardCopy.Pair(it.first, it.second, it.third) }
                    val capitals = spellings.filterKeys { it !in known }.map { KeyboardCopy.Spelling(it.key, it.value) }
                    val personal = PersonalLearningStore.get(app, language)
                    personal.whenReady {
                        val contexts = personal.entries().map {
                            KeyboardCopy.Context(it.context, it.word, it.observations, it.explicitObservations, it.lastObserved)
                        }
                        learned += KeyboardCopy.LearnedLanguage(language.code, pairs, contexts, capitals)
                        release()
                    }
                }
            }
        }
        if (Part.SAVED in parts) {
            val release = gate.hold()
            SavedTextStore.open(app) { texts ->
                saved = texts.items.map { KeyboardCopy.Saved(it, texts.codes[it]) }
                release()
            }
        }
        if (Part.LEARNED in parts) {
            val release = gate.hold()
            DomainStore.open(app) { domains ->
                sites = domains.entries.map { KeyboardCopy.Site(it.host, it.count, it.day) }
                release()
            }
        }
        gate.release()
    }

    /** Puts the chosen [parts] of [copy] into this phone, then tells a running keyboard to reload. */
    fun apply(context: Context, copy: KeyboardCopy, parts: Set<Part>, done: (Applied) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = context.applicationContext
        var result = Applied()
        // Whatever was taken in is shown to a running keyboard, also when a slow list finishes after the limit.
        val gate = Gate(late = { KeyboardSettings.bumpLearnedGeneration(app) }) { complete ->
            KeyboardSettings.bumpLearnedGeneration(app)
            done(if (complete) result else result.copy(incomplete = true))
        }
        // Settings first: whether sites are remembered here decides below whether the copy's sites come in.
        copy.settings?.takeIf { Part.SETTINGS in parts }?.let { settings ->
            result = result.copy(settings = KeyboardSettings.applyPortable(app, settings.values))
            val emoji = PreferenceEmojiStore(app)
            val default = settings.defaultTone?.takeIf { it in 0..EmojiInventory.TONE_COUNT } ?: emoji.defaultTone()
            val held = emoji.tones()
            settings.tones.asSequence().filter { it.tone in 0..EmojiInventory.TONE_COUNT && plainEmoji(it.base) }
                .filter { it.base in held || held.size < MAX_TONES }.take(MAX_TONES)
                .forEach { emoji.setTone(it.base, it.tone, default) }
        }
        val codes = LinkedHashSet<String>()
        if (Part.WORDS in parts) copy.words.orEmpty().forEach { codes += it.language }
        if (Part.LEARNED in parts) copy.learned?.languages.orEmpty().forEach { codes += it.language }
        for (code in codes) {
            // A language this version does not have (a copy from a later one) is left out.
            val language = LanguageProfile.of(code)?.takeIf { it.words } ?: continue
            val words = copy.words.orEmpty().firstOrNull { it.language == code }?.takeIf { Part.WORDS in parts }
            val learned = copy.learned?.languages.orEmpty().firstOrNull { it.language == code }?.takeIf { Part.LEARNED in parts }
            val release = gate.hold()
            val blockedStore = BlockedWordStore.get(app, language)
            blockedStore.whenLoaded {
                if (!blockedStore.ready) {
                    // Without this phone's blocked words there is no telling what must stay out.
                    result = result.copy(incomplete = true)
                    return@whenLoaded release()
                }
                blockedStore.addAll(words?.blocked.orEmpty()) { blockedNow ->
                    result = if (blockedNow == null) result.copy(incomplete = true) else result.copy(blocked = result.blocked + blockedNow)
                    val blocked = blockedStore.words
                    val journal = JournalLearningStore.get(app, language)
                    journal.whenReady {
                        if (words != null) {
                            val taken = AddedWords.addAll(journal, words.added.map { it.display ?: it.word }, language) { BlockedWords.contains(blocked, it) }
                            if (taken.added.isNotEmpty()) {
                                val added = AddedWords.words(journal.entries()).toHashSet()
                                WordSpellingStore.get(app, language)
                                    .changeAll(taken.added.map { (word, typed) -> word to WordSpellings.added(typed) }) { it in added }
                            }
                            result = result.copy(added = result.added + taken.added.size)
                        }
                        if (learned == null) return@whenReady release()
                        // Each word is held to what typing could have taught: a word in its stored form, not an
                        // excluded one, not blocked here. The sentence-start marker is the one previous "word" that is not.
                        val checked = HashMap<String, Boolean>()
                        fun word(value: String, follower: Boolean) = checked.getOrPut(if (follower) "+$value" else value) {
                            KeyboardCopy.learnedWord(value, language, follower) && !BlockedWords.contains(blocked, value)
                        }
                        val pairs = learned.pairs.filter {
                            it.count in 1..LearnedPairState.MAX_COUNT && word(it.next, follower = true) &&
                                (it.previous == AdaptiveNgramModel.START || word(it.previous, follower = false))
                        }.take(MAX_PAIRS)
                        journal.merge(pairs.map { Triple(it.previous, it.next, it.count) })
                        result = result.copy(pairs = result.pairs + pairs.size)
                        val added = AddedWords.words(journal.entries()).toHashSet()
                        // A capital is taken only as the same word with other letter cases, the form Add word keeps,
                        // only for a word with no spelling here, and only into the room the list has left.
                        val spellings = WordSpellingStore.get(app, language)
                        val here = spellings.spellings
                        spellings.changeAll(
                            learned.spellings.asSequence()
                                .filter { it.word !in here && AdaptiveNgramModel.normalize(it.display) == it.word && word(it.word, follower = false) }
                                .take(maxOf(0, WordSpellings.MAX_WORDS - here.size)).map { it.word to WordSpellings.added(it.display) }.toList(),
                        ) { it in added }
                        val personal = PersonalLearningStore.get(app, language)
                        personal.whenReady {
                            personal.merge(
                                learned.contexts.asSequence()
                                    .filter { row -> word(row.word, follower = true) && row.context.all { word(it, follower = false) } }
                                    .take(PersonalLearningCodec.MAX_ENTRIES)
                                    .map { PersonalLearningState.Entry(it.context, it.word, it.observations, it.explicit, it.observed) }.toList(),
                            )
                            release()
                        }
                    }
                }
            }
        }
        copy.saved?.takeIf { Part.SAVED in parts }?.let { saved ->
            val release = gate.hold()
            SavedTextStore.open(app) { texts ->
                result = result.copy(saved = texts.merge(saved.map { SavedTexts.Entry(it.text, it.code) }))
                release()
            }
        }
        copy.learned?.takeIf { Part.LEARNED in parts }?.let { learned ->
            val emoji = PreferenceEmojiStore(app)
            val recent = (emoji.recent() + learned.emoji.filter(::plainEmoji)).distinct().take(RecentEmoji.MAX)
            if (recent != emoji.recent()) emoji.setRecent(recent)
            // Sites the person turned off on this phone stay off: nothing is put into a list they chose not to keep.
            if (KeyboardSettings.rememberDomains(app) && learned.sites.isNotEmpty()) {
                val release = gate.hold()
                DomainStore.open(app) { domains ->
                    val today = Math.floorDiv(System.currentTimeMillis(), 86_400_000L)
                    result = result.copy(
                        sites = domains.merge(learned.sites.map { RememberedDomains.Entry(it.host, it.count, it.day) }, today, DomainStore.tlds),
                    )
                    release()
                }
            }
        }
        gate.release()
    }

    /**
     * Short, and made only of symbols, joiners and marks: no letter, digit, space or control character, so nothing
     * that is not an emoji is kept as one, and the stores' own formats stay whole.
     */
    private fun plainEmoji(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_EMOJI_UNITS &&
        value.none { it.code < 0x200D || it.isLetterOrDigit() || it.isWhitespace() || it.isISOControl() }

    /**
     * Save a copy, start to finish: gathers the ticked parts, then writes the file on its own thread, so a screen
     * that closes meanwhile still leaves either a whole copy or no file. [done] is called on the main thread.
     */
    fun save(context: Context, target: Uri, parts: Set<Part>, made: String, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        collect(app, parts, made) { copy ->
            Thread({
                val ok = copy != null && runCatching { write(app, target, KeyboardCopy.encode(copy)) }.getOrDefault(false)
                // A file that could not be written whole is not left looking like a copy.
                if (!ok) discard(app, target)
                main.post { done(ok) }
            }, "localtype-copy").start()
        }
    }

    fun discard(context: Context, target: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
    }

    /** Writes [text] to the file the person picked. Not on the main thread. */
    fun write(context: Context, target: Uri, text: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openOutputStream(target, "wt") }.getOrElse { resolver.openOutputStream(target, "w") }
        stream?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)

    /** Reads the file the person picked, up to the size a copy can be. Null when it cannot be opened. Not on the main thread. */
    fun read(context: Context, source: Uri): KeyboardCopy.Read? = try {
        val bytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                var checked = false
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    // The first bytes say whether this is a copy at all, before the rest of a large file is taken in.
                    if (!checked && out.size() >= PREFIX_BYTES) {
                        checked = true
                        if (!startsLikeACopy(out.toByteArray())) return KeyboardCopy.Read.NotACopy
                    }
                    if (out.size() > KeyboardCopy.MAX_BYTES) return KeyboardCopy.Read.Damaged
                }
                out.toByteArray()
            }
        }.getOrNull()
        bytes?.let(::decode)
    } catch (_: OutOfMemoryError) {
        // Settings shares its process with the keyboard: a file too large to hold is refused, never fatal.
        KeyboardCopy.Read.Damaged
    }

    private fun startsLikeACopy(bytes: ByteArray): Boolean {
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val header = KeyboardCopy.HEADER.toByteArray(Charsets.US_ASCII)
        val from = if (bom) 3 else 0
        return bytes.size - from >= header.size && header.indices.all { bytes[from + it] == header[it] }
    }

    /** Strict UTF-8: a file that is not text is not a copy, and is never half read. */
    internal fun decode(bytes: ByteArray): KeyboardCopy.Read {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return KeyboardCopy.Read.NotACopy
        }
        return KeyboardCopy.decode(text.removePrefix("\uFEFF"))
    }

    /** How long Save or Read waits for this phone's lists before saying it could not finish. */
    private const val LIMIT_MS = 20_000L
    private const val PREFIX_BYTES = 32
    private const val MAX_TONES = 512
    private const val MAX_EMOJI_UNITS = 32
    /** The pair store's own bound: 1,024 previous words of 64 followers each. */
    private const val MAX_PAIRS = LearnedPairState.MAX_PREVIOUS_WORDS * LearnedPairState.MAX_FOLLOWERS
}
