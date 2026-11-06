package dev.localtype.keyboard

import java.io.BufferedReader
import java.io.Reader
import java.security.MessageDigest

/**
 * One line of the typing log. The file is one record per line, TAB-separated, first field the
 * record tag; [PredictionLogCodec] turns records into lines and back. Every string field is stored
 * escaped, so a record's strings may hold any character. Booleans are written 0/1, and an empty
 * string means "none" (for example [Word.prev] at a sentence start).
 */
sealed class LogRecord {
    abstract val tag: String

    /** Start of a table block; [reason] may carry the `-partial` suffix when the row cap was hit. */
    data class Snapshot(val reason: String, val rows: Int) : LogRecord() {
        override val tag get() = "SNAP"
        val partial: Boolean get() = reason.endsWith(PredictionLogCodec.SNAP_PARTIAL_SUFFIX)
        val same: Boolean get() = reason == PredictionLogCodec.SNAP_SAME
    }

    data class Unigram(val word: String, val count: Int) : LogRecord() {
        override val tag get() = "U"
    }

    /** [prev] is [AdaptiveNgramModel.START] for a sentence start. */
    data class Bigram(val prev: String, val next: String, val count: Int) : LogRecord() {
        override val tag get() = "B"
    }

    data object SnapshotEnd : LogRecord() {
        override val tag get() = "END"
    }

    /** Blocks that arrived before any snapshot existed for this file and were discarded. */
    data class Dropped(val blocks: Int) : LogRecord() {
        override val tag get() = "DROP"
    }

    /** A field start that passed the gate; [model] is 0 none, 1 automatic requests, 2 on tap only. */
    data class Field(
        val seq: Int,
        val restart: Boolean,
        val autocorrect: Boolean,
        val profanity: Boolean,
        val model: Int,
        val emoji: Boolean,
        /** Optional trailing field, present only for a neural opt-in field; legacy/off rows stay identical. */
        val neuralSha256: String? = null,
    ) : LogRecord() {
        override val tag get() = "F"
    }

    /** One word instance: the verified sentence tail before it and what the device computed from it. */
    data class Word(
        val wid: Int,
        val fieldSeq: Int,
        val sentence: String,
        val cut: Boolean,
        val ss: Boolean,
        val prev: String,
        val revisit: Boolean,
    ) : LogRecord() {
        override val tag get() = "W"
    }

    /** One strip refresh: the raw ranked list, the three slots as shown, and the timings. */
    data class Offer(
        val wid: Int,
        val p: Int,
        val prefix: String,
        val mode: Char,
        val branch: Char,
        val shown: Boolean,
        val ranked: List<Pair<String, Int>>,
        val slots: List<String>,
        val src3: String,
        val model: Int,
        val dict: Int,
        val us: Int,
        val rus: Int,
    ) : LogRecord() {
        override val tag get() = "O"
    }

    data class Tap(val wid: Int, val p: Int, val idx: Int, val word: String, val src: Char, val ps: Boolean) : LogRecord() {
        override val tag get() = "T"
    }

    data class Typed(
        val wid: Int,
        val typed: String,
        val committed: String,
        val sep: Char,
        val corrected: Boolean,
        val learned: Boolean,
        val ps: Boolean,
    ) : LogRecord() {
        override val tag get() = "Y"
    }

    /** Optional correction outcome. No raw context; wid names a verified, closed word. */
    data class Repair(val wid: Int, val original: String, val replacement: String, val outcome: Char,
                      val learned: Boolean, val prev: String) : LogRecord() {
        override val tag get() = "FIX"
    }

    /** Closed without an outcome: [why] is N (a new word key arrived) or F (the field ended). */
    data class Abandon(val wid: Int, val why: Char) : LogRecord() {
        override val tag get() = "A"
    }

    /** A revisit opened at the key of instance [wid], which had closed with a tap or a typed word. */
    data class Revisit(val wid: Int) : LogRecord() {
        override val tag get() = "R"
    }

    /**
     * A device mutation the file has no instance for. [word] is empty when not verified (a hidden
     * mutation); [prev] is [AdaptiveNgramModel.START] when the device credited a sentence start, the
     * verified previous word when it used that, and empty when what it used is unknown.
     */
    data class Mutation(val fieldSeq: Int, val word: String, val prev: String) : LogRecord() {
        override val tag get() = "X"
    }

    data class FieldEnd(
        val seq: Int,
        val dup: Int,
        val stale: Int,
        val single: Int,
        val unverifiedShort: Int,
        val unverifiedForeign: Int,
        val unknown: Int,
        val revisit: Int,
        val x: Int,
    ) : LogRecord() {
        override val tag get() = "EF"
    }
}

/**
 * The on-disk format of the typing log, shared verbatim by the keyboard's writer and the offline
 * harness. Pure Kotlin: no Android types, no I/O of its own beyond reading lines from a [Reader].
 *
 * Guarantees: `decode(encode(r)) == r` for every record, and `encode(decode(line)) == line` for
 * every line the codec wrote. Integers are written in canonical decimal and read back only in that
 * form, so a line that decodes is exactly the line that would be re-encoded. The one asymmetry is a
 * list holding a single empty element, which is indistinguishable from an empty list.
 */
object PredictionLogCodec {
    const val MAGIC_KEY = "localtype-log"
    const val VERSION = 1

    const val SNAP_PROCESS = "process"
    const val SNAP_FILE = "file"
    const val SNAP_REBUILD = "rebuild"
    const val SNAP_UNLOGGED = "unlogged"
    const val SNAP_SAME = "same"
    const val SNAP_PARTIAL_SUFFIX = "-partial"

    /**
     * Header keys in file order, after the magic line. `prior`, `priorWeights` and `seeds` came with
     * the corpus prior (spec amendment 2026-10-04); a file without `prior` was ranked with no prior.
     */
    val HEADER_KEYS = listOf(
        "app", "code", "flavor", "sdk", "file", "dict", "contextScope", "contextChars", "keyChars", "candidates",
        "prior", "priorWeights", "seeds", "neural", "correctionPolicy", "autocorrect", "webAutocorrect", "addedWords",
        "vocabulary", "vocabularyPolicy", "newsBoost", "personalLearning",
    )

    /** `#prior` when the keyboard ranked with no corpus prior, which is also what a header without the key means. */
    const val PRIOR_NONE = "none"

    const val NEURAL_NONE = "none"
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** Neural mode is fixed per field and per file; a missing legacy header means off. */
    fun neuralHeader(sha256: String?): Map<String, String> {
        require(sha256 == null || SHA256.matches(sha256)) { "invalid neural asset SHA-256" }
        return mapOf("neural" to (sha256 ?: NEURAL_NONE))
    }

    fun neuralDigest(header: Map<String, String>): String? =
        header["neural"]?.takeIf { it != NEURAL_NONE }

    /** Includes mode changes even when the n-gram prior is unchanged. */
    fun sameRanking(header: Map<String, String>, current: Map<String, String>): Boolean =
        samePrior(header, current) && neuralDigest(header) == neuralDigest(current) &&
            (CORRECTION_KEYS + VOCABULARY_KEYS).all { header[it] == current[it] } && header["addedWords"] == current["addedWords"] && header["personalLearning"] == current["personalLearning"]

    /**
     * Whether the file was started by this build. A file has one header and no row names a version, so appending
     * after an update would leave one file describing two builds with nothing to tell their rows apart.
     */
    fun sameBuild(header: Map<String, String>, versionCode: Int): Boolean = header["code"] == versionCode.toString()

    internal val CORRECTION_KEYS = listOf("correctionPolicy", "autocorrect", "webAutocorrect")
    internal val VOCABULARY_KEYS = listOf("vocabulary", "vocabularyPolicy", "newsBoost")

    fun correctionHeader(policy: String, autocorrect: Boolean, webAutocorrect: Boolean): Map<String, String> {
        require(policy.isNotBlank() && policy.length <= 80 && policy.none { it == '\n' || it == '\r' || it == '\t' })
        return mapOf("correctionPolicy" to policy, "autocorrect" to bit(autocorrect), "webAutocorrect" to bit(webAutocorrect))
    }

    internal fun repairWord(word: String): Boolean = word.length in 1..80 && AdaptiveNgramModel.WORD_REGEX.matches(word)

    /**
     * The `#seeds` value: SHA-256 hex of the seed phrases, one per line, then a NUL line, then the
     * sentence starters, one per line. The prior's fade leaves seeded counts out, so a replay with
     * a prior has to use the seeds the device used, and a build that changes them ranks differently.
     */
    fun seedsDigest(seedPhrases: List<String>, sentenceStarters: List<String>): String {
        require((seedPhrases + sentenceStarters).none { '\n' in it || '\u0000' in it }) { "a seed phrase or starter holds a newline or NUL" }
        val text = seedPhrases.joinToString("") { "$it\n" } + "\u0000\n" + sentenceStarters.joinToString("") { "$it\n" }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** [seedsDigest] of [AdaptiveNgramModel.DEFAULT_SEED_PHRASES] and [AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS], what the keyboard seeds with. */
    val SHIPPED_SEEDS: String by lazy {
        seedsDigest(AdaptiveNgramModel.DEFAULT_SEED_PHRASES, AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS)
    }

    /**
     * The header values naming what the ranker adds to a snapshot's tables: `prior` is the SHA-256
     * of `corpus_prior.bin` as the keyboard read it ([CorpusPrior.sha256]), or [PRIOR_NONE],
     * `priorWeights` is `weight,floor,fade`, and `seeds` is the [seedsDigest] of the seed phrases
     * and starters the model was built with. A replay ranks with exactly these, and one file holds
     * one setting: the keyboard stops writing to a file whose header names another.
     */
    fun priorHeader(
        sha256: String?,
        weights: AdaptiveNgramModel.PriorWeights,
        seeds: String = SHIPPED_SEEDS,
    ): Map<String, String> = linkedMapOf(
        "prior" to (sha256 ?: PRIOR_NONE),
        "priorWeights" to "${weights.weight},${weights.floor},${weights.fade}",
        "seeds" to seeds,
    )

    /** The prior's SHA-256 that [header] names, or null for none, including a file from before the key existed. */
    fun priorDigest(header: Map<String, String>): String? = header["prior"]?.takeIf { it.isNotEmpty() && it != PRIOR_NONE }

    /**
     * The weights [header] names: the shipped default when it names no `priorWeights` (they only
     * matter with a prior), null when the value doesn't parse as three valid numbers.
     */
    fun priorWeights(header: Map<String, String>): AdaptiveNgramModel.PriorWeights? {
        val value = header["priorWeights"] ?: return AdaptiveNgramModel.PriorWeights.DEFAULT
        val parts = value.split(',').map { it.toFloatOrNull() ?: return null }
        if (parts.size != 3) return null
        return runCatching { AdaptiveNgramModel.PriorWeights(parts[0], parts[1], parts[2]) }.getOrNull()
    }

    /**
     * True when a file whose header is [header] was ranked with the prior that [current] (a
     * [priorHeader]) names. The weights and the seeds count only when there is a prior.
     */
    fun samePrior(header: Map<String, String>, current: Map<String, String>): Boolean {
        val digest = priorDigest(header)
        if (digest != priorDigest(current)) return false
        return digest == null || (header["priorWeights"] == current["priorWeights"] && header["seeds"] == current["seeds"])
    }

    /**
     * The #flavor value. There is one build now; it keeps the value it always wrote, so its logs
     * compare across versions. Logs from the removed model-free build say "local".
     */
    const val FLAVOR = "llm"

    private const val START = '\u0002'
    private const val TAB = '\t'
    private const val LIST_SEPARATOR = ','

    /** Counts of skipped lines, filled in as [read]'s sequence is consumed or by [decode]. */
    class DecodeStats(var malformed: Int = 0, var unknown: Int = 0) {
        val total: Int get() = malformed + unknown
    }

    /** Backslash, TAB, LF, CR, comma and U+0002 become two-character escapes; everything else is kept. */
    fun escape(s: String): String {
        if (s.none { it == '\\' || it == TAB || it == '\n' || it == '\r' || it == LIST_SEPARATOR || it == START }) return s
        val out = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> out.append("\\\\")
            TAB -> out.append("\\t")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            LIST_SEPARATOR -> out.append("\\,")
            START -> out.append("\\s")
            else -> out.append(c)
        }
        return out.toString()
    }

    /** Reverses [escape]; null for a dangling backslash or an escape the codec never writes. */
    fun unescape(s: String): String? {
        if (s.indexOf('\\') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (i >= s.length) return null
            out.append(
                when (s[i++]) {
                    '\\' -> '\\'
                    't' -> TAB
                    'n' -> '\n'
                    'r' -> '\r'
                    ',' -> LIST_SEPARATOR
                    's' -> START
                    else -> return null
                },
            )
        }
        return out.toString()
    }

    /** One line, without its newline. */
    fun encode(r: LogRecord): String {
        val f = ArrayList<String>(14)
        f += r.tag
        when (r) {
            is LogRecord.Snapshot -> {
                f += escape(r.reason)
                f += r.rows.toString()
            }
            is LogRecord.Unigram -> {
                f += escape(r.word)
                f += r.count.toString()
            }
            is LogRecord.Bigram -> {
                f += escape(r.prev)
                f += escape(r.next)
                f += r.count.toString()
            }
            LogRecord.SnapshotEnd -> Unit
            is LogRecord.Dropped -> f += r.blocks.toString()
            is LogRecord.Field -> {
                f += r.seq.toString()
                f += bit(r.restart)
                f += bit(r.autocorrect)
                f += bit(r.profanity)
                f += r.model.toString()
                f += bit(r.emoji)
                r.neuralSha256?.let {
                    require(SHA256.matches(it)) { "invalid field neural asset SHA-256" }
                    f += it
                }
            }
            is LogRecord.Word -> {
                f += r.wid.toString()
                f += r.fieldSeq.toString()
                f += escape(r.sentence)
                f += bit(r.cut)
                f += bit(r.ss)
                f += escape(r.prev)
                f += bit(r.revisit)
            }
            is LogRecord.Offer -> {
                f += r.wid.toString()
                f += r.p.toString()
                f += escape(r.prefix)
                f += escape(r.mode.toString())
                f += escape(r.branch.toString())
                f += bit(r.shown)
                f += r.ranked.joinToString(LIST_SEPARATOR.toString()) { (word, score) -> escape(word) + ':' + score }
                f += encodeList(r.slots)
                f += escape(r.src3)
                f += r.model.toString()
                f += r.dict.toString()
                f += r.us.toString()
                f += r.rus.toString()
            }
            is LogRecord.Tap -> {
                f += r.wid.toString()
                f += r.p.toString()
                f += r.idx.toString()
                f += escape(r.word)
                f += escape(r.src.toString())
                f += bit(r.ps)
            }
            is LogRecord.Typed -> {
                f += r.wid.toString()
                f += escape(r.typed)
                f += escape(r.committed)
                f += escape(r.sep.toString())
                f += bit(r.corrected)
                f += bit(r.learned)
                f += bit(r.ps)
            }
            is LogRecord.Repair -> {
                require(r.wid > 0 && repairWord(r.original) && repairWord(r.replacement) && r.outcome in "OPJU" && (!r.learned || r.outcome == 'P') &&
                    (r.prev.isEmpty() || r.prev == AdaptiveNgramModel.START || repairWord(r.prev)))
                f += r.wid.toString()
                f += escape(r.original)
                f += escape(r.replacement)
                f += r.outcome.toString()
                f += bit(r.learned)
                f += escape(r.prev)
            }
            is LogRecord.Abandon -> {
                f += r.wid.toString()
                f += escape(r.why.toString())
            }
            is LogRecord.Revisit -> f += r.wid.toString()
            is LogRecord.Mutation -> {
                f += r.fieldSeq.toString()
                f += escape(r.word)
                f += escape(r.prev)
            }
            is LogRecord.FieldEnd -> {
                f += r.seq.toString()
                f += r.dup.toString()
                f += r.stale.toString()
                f += r.single.toString()
                f += r.unverifiedShort.toString()
                f += r.unverifiedForeign.toString()
                f += r.unknown.toString()
                f += r.revisit.toString()
                f += r.x.toString()
            }
        }
        return f.joinToString(TAB.toString())
    }

    /** The records as lines, each ending in LF, ready to append as one block. */
    fun encodeBlock(records: List<LogRecord>): String {
        val out = StringBuilder()
        for (r in records) out.append(encode(r)).append('\n')
        return out.toString()
    }

    /**
     * The record on [line], or null when the tag is unknown or the fields don't fit (wrong count,
     * a non-canonical integer, a boolean other than 0/1, a bad escape, a multi-character char).
     * When [stats] is given, the reason is counted there.
     */
    fun decode(line: String, stats: DecodeStats? = null): LogRecord? {
        if (line.isEmpty()) {
            stats?.let { it.malformed++ }
            return null
        }
        // Escaped tabs are the two characters backslash-t, so a raw split never lands inside a field.
        val fields = line.split(TAB)
        val record = try {
            decodeFields(fields)
        } catch (e: MalformedLine) {
            null
        }
        if (record == null) stats?.let { if (fields[0] in TAGS) it.malformed++ else it.unknown++ }
        return record
    }

    private fun decodeFields(fields: List<String>): LogRecord? {
        val f = Fields(fields)
        return when (fields[0]) {
            "SNAP" -> f.count(3).let { LogRecord.Snapshot(f.str(1), f.int(2)) }
            "U" -> f.count(3).let { LogRecord.Unigram(f.str(1), f.int(2)) }
            "B" -> f.count(4).let { LogRecord.Bigram(f.str(1), f.str(2), f.int(3)) }
            "END" -> f.count(1).let { LogRecord.SnapshotEnd }
            "DROP" -> f.count(2).let { LogRecord.Dropped(f.int(1)) }
            "F" -> {
                if (fields.size != 7 && fields.size != 8) throw MalformedLine()
                val neural = if (fields.size == 8) f.str(7).also { if (!SHA256.matches(it)) throw MalformedLine() } else null
                LogRecord.Field(f.int(1), f.bool(2), f.bool(3), f.bool(4), f.int(5), f.bool(6), neural)
            }
            "W" -> f.count(8).let { LogRecord.Word(f.int(1), f.int(2), f.str(3), f.bool(4), f.bool(5), f.str(6), f.bool(7)) }
            "O" -> f.count(14).let {
                LogRecord.Offer(
                    wid = f.int(1),
                    p = f.int(2),
                    prefix = f.str(3),
                    mode = f.char(4),
                    branch = f.char(5),
                    shown = f.bool(6),
                    ranked = f.ranked(7),
                    slots = f.list(8),
                    src3 = f.str(9),
                    model = f.int(10),
                    dict = f.int(11),
                    us = f.int(12),
                    rus = f.int(13),
                )
            }
            "T" -> f.count(7).let { LogRecord.Tap(f.int(1), f.int(2), f.int(3), f.str(4), f.char(5), f.bool(6)) }
            "Y" -> f.count(8).let { LogRecord.Typed(f.int(1), f.str(2), f.str(3), f.char(4), f.bool(5), f.bool(6), f.bool(7)) }
            "FIX" -> f.count(7).let {
                val r = LogRecord.Repair(f.int(1), f.str(2), f.str(3), f.char(4), f.bool(5), f.str(6))
                if (r.wid <= 0 || !repairWord(r.original) || !repairWord(r.replacement) || r.outcome !in "OPJU" ||
                    (r.learned && r.outcome != 'P') || (r.prev.isNotEmpty() && r.prev != AdaptiveNgramModel.START && !repairWord(r.prev))) throw MalformedLine()
                r
            }
            "A" -> f.count(3).let { LogRecord.Abandon(f.int(1), f.char(2)) }
            "R" -> f.count(2).let { LogRecord.Revisit(f.int(1)) }
            "X" -> f.count(4).let { LogRecord.Mutation(f.int(1), f.str(2), f.str(3)) }
            "EF" -> f.count(10).let {
                LogRecord.FieldEnd(f.int(1), f.int(2), f.int(3), f.int(4), f.int(5), f.int(6), f.int(7), f.int(8), f.int(9))
            }
            else -> null
        }
    }

    /**
     * The header lines, magic first, each `#key=value` ending in LF. [fields] are written in
     * iteration order; a magic entry in it is ignored because the codec always writes its own.
     */
    fun encodeHeader(fields: Map<String, String>): String {
        val out = StringBuilder()
        out.append('#').append(MAGIC_KEY).append('=').append(VERSION).append('\n')
        for ((key, value) in fields) {
            if (key == MAGIC_KEY) continue
            require(key.isNotEmpty() && key.none { it == '=' || it == '#' || it == '\n' || it == '\r' || it == TAB }) { "bad header key: $key" }
            require(value.none { it == '\n' || it == '\r' || it == TAB }) { "bad header value for $key" }
            out.append('#').append(key).append('=').append(value).append('\n')
        }
        return out.toString()
    }

    /** The `#key=value` lines at the top of [reader], magic included, stopping at the first record line. */
    fun readHeader(reader: Reader): Map<String, String> {
        val lines = reader as? BufferedReader ?: BufferedReader(reader)
        val header = LinkedHashMap<String, String>()
        while (true) {
            val line = lines.readLine() ?: break
            if (!line.startsWith('#')) break
            parseHeader(line)?.let { (key, value) -> header[key] = value }
        }
        return header
    }

    /** The key and value of one `#key=value` line, or null for anything else. */
    fun parseHeader(line: String): Pair<String, String>? {
        if (!line.startsWith('#')) return null
        val eq = line.indexOf('=')
        if (eq <= 1) return null
        return line.substring(1, eq) to line.substring(eq + 1)
    }

    /**
     * Reads the header eagerly and returns the records as a lazy, single-pass sequence over the
     * rest of [reader], which must stay open until the sequence is consumed. Skipped lines are
     * counted in [stats] as they are met. Refuses a file whose magic value is not [VERSION].
     */
    fun read(reader: Reader, stats: DecodeStats = DecodeStats()): Pair<Map<String, String>, Sequence<LogRecord>> {
        val lines = reader as? BufferedReader ?: BufferedReader(reader)
        val header = LinkedHashMap<String, String>()
        var pending: String? = null
        while (true) {
            val line = lines.readLine() ?: break
            if (!line.startsWith('#')) {
                pending = line
                break
            }
            val entry = parseHeader(line)
            if (entry == null) stats.malformed++ else header[entry.first] = entry.second
        }
        val magic = header[MAGIC_KEY]
        require(magic == VERSION.toString()) { "not a typing log I can read: #$MAGIC_KEY=${magic ?: "missing"}" }
        val records = sequence {
            var line = pending
            while (line != null) {
                decode(line, stats)?.let { yield(it) }
                line = lines.readLine()
            }
        }.constrainOnce()
        return header to records
    }

    /** SHA-256 hex of [rows] (encoded lines), each followed by LF; the same rows always hash the same. */
    fun blockHash(rows: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (row in rows) {
            digest.update(row.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val TAGS = setOf("SNAP", "U", "B", "END", "DROP", "F", "W", "O", "T", "Y", "A", "R", "X", "EF", "FIX")

    private fun bit(b: Boolean) = if (b) "1" else "0"

    private fun encodeList(items: List<String>) = items.joinToString(LIST_SEPARATOR.toString()) { escape(it) }

    /** Thrown inside [decodeFields] for any field that doesn't fit; carries no stack trace. */
    private class MalformedLine : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Fields(private val fields: List<String>) {
        fun count(n: Int) {
            if (fields.size != n) throw MalformedLine()
        }

        fun str(i: Int): String = unescape(fields[i]) ?: throw MalformedLine()

        fun int(i: Int): Int = canonicalInt(fields[i]) ?: throw MalformedLine()

        fun bool(i: Int): Boolean = when (fields[i]) {
            "0" -> false
            "1" -> true
            else -> throw MalformedLine()
        }

        fun char(i: Int): Char = str(i).takeIf { it.length == 1 }?.get(0) ?: throw MalformedLine()

        fun list(i: Int): List<String> {
            val field = fields[i]
            if (field.isEmpty()) return emptyList()
            return splitList(field).map { unescape(it) ?: throw MalformedLine() }
        }

        fun ranked(i: Int): List<Pair<String, Int>> {
            val field = fields[i]
            if (field.isEmpty()) return emptyList()
            return splitList(field).map { entry ->
                val colon = entry.lastIndexOf(':')
                if (colon < 0) throw MalformedLine()
                val word = unescape(entry.substring(0, colon)) ?: throw MalformedLine()
                val score = canonicalInt(entry.substring(colon + 1)) ?: throw MalformedLine()
                word to score
            }
        }

        /** Only the decimal form [Int.toString] produces, so a decoded line re-encodes byte for byte. */
        private fun canonicalInt(s: String): Int? = s.toIntOrNull()?.takeIf { it.toString() == s }

        /** Splits on commas that are not escaped: an escaped comma is still a raw comma after its backslash. */
        private fun splitList(field: String): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < field.length) {
                val c = field[i++]
                when (c) {
                    '\\' -> {
                        current.append(c)
                        if (i < field.length) current.append(field[i++])
                    }
                    LIST_SEPARATOR -> {
                        out += current.toString()
                        current.setLength(0)
                    }
                    else -> current.append(c)
                }
            }
            out += current.toString()
            return out
        }
    }
}
