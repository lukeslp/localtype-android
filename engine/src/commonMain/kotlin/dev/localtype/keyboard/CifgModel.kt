package dev.localtype.keyboard

import kotlin.math.exp
import kotlin.math.tanh

/**
 * The CIFG-LSTM next-word model from `model/cifg.py`, run on its int8 export in plain Kotlin: no
 * inference runtime, no native code, no Android classes. One step is about 450k multiply-adds
 * through the gates, and scoring every word through the tied embedding is about 1.6M more.
 *
 * The file is `cifg_int8.bin` from `model/export.py` (layout in `model/README.md`, "Export"): the
 * four int8 matrices with one float scale per row, the float bias and the vocabulary. The int8
 * values are read in place from the loaded bytes, so the weights cost the file's size in heap and
 * nothing is dequantized. Every value is computed in float32; the golden tests hold the logits
 * within 1e-3 of the Python export's float64 reference.
 *
 * Contexts are encoded with [CifgTokenizer] in sentence scope, as the model is evaluated. Candidates
 * are the words the keyboard could offer, by the rule in [AdaptiveNgramModel.predictScored]: a whole
 * [AdaptiveNgramModel.WORD_REGEX] match, two or more characters long, or "a" or "i".
 *
 * The model is immutable and can be shared across threads; each thread runs its own [Session].
 * The opt-in keyboard path loads it through [NeuralAsset] and keeps one worker-owned session for
 * the current editor field. The corpus scorer can still load any export for parity and evaluation.
 */
class CifgModel private constructor(
    val vocabSize: Int,
    val embedDim: Int,
    val hidden: Int,
    private val blob: ByteArray,
    private val embeddingAt: Int,
    private val embeddingScale: FloatArray,
    private val wxAt: Int,
    private val wxScale: FloatArray,
    private val wrAt: Int,
    private val wrScale: FloatArray,
    private val projAt: Int,
    private val projScale: FloatArray,
    private val bias: FloatArray,
    private val tokens: Array<String>,
) {
    private val idByToken = HashMap<String, Int>(tokens.size * 2)
    private val emojiByKey = HashMap<String, Int>()

    /** Offerable word ids, by id (most frequent first in a frequency-ranked vocabulary). */
    private val offerable: IntArray

    /** The same ids ordered by their words, so a typed prefix is one contiguous range. */
    private val bySpelling: IntArray
    private val spellings: Array<String>

    init {
        tokens.forEachIndexed { id, token ->
            idByToken[token] = id
            if (CifgTokenizer.isEmoji(token)) emojiByKey.getOrPut(CifgTokenizer.stripPresentation(token)) { id }
        }
        offerable = tokens.indices.filter { isOfferable(tokens[it]) }.toIntArray()
        bySpelling = offerable.sortedBy { tokens[it] }.toIntArray()
        spellings = Array(bySpelling.size) { tokens[bySpelling[it]] }
    }

    val bosId: Int get() = BOS_ID
    val unkId: Int get() = UNK_ID

    /** Offerable words in the vocabulary. */
    val offerableCount: Int get() = offerable.size

    fun token(id: Int): String = tokens[id]

    /** tokenize.Tokenizer.id_of: the token's id, an emoji's id with or without its variation selector, else `<unk>`. */
    fun idOf(token: String): Int {
        idByToken[token]?.let { return it }
        if (CifgTokenizer.isEmoji(token)) emojiByKey[CifgTokenizer.stripPresentation(token)]?.let { return it }
        return UNK_ID
    }

    /** The ids the model reads for [beforeCursor] (starting with `<s>`), and the typed prefix to filter by. */
    fun encode(beforeCursor: String): Encoded {
        val context = CifgTokenizer.context(beforeCursor)
        val ids = IntArray(context.tokens.size + 1)
        ids[0] = BOS_ID
        context.tokens.forEachIndexed { i, token -> ids[i + 1] = idOf(token) }
        return Encoded(ids, context.prefix)
    }

    class Encoded(val ids: IntArray, val prefix: String)

    data class Candidate(val word: String, val id: Int, val logit: Float)

    fun newSession(): Session = Session()

    /**
     * The recurrent state and scratch buffers for one caller. Not thread-safe. It remembers the ids
     * it has read since `<s>`, so a context that extends the last one (the next word of the same
     * sentence) costs a step per new token, usually one, and the same context with a longer prefix
     * costs no step at all. Past 63 tokens of one sentence the context window jumps forward
     * ([CifgTokenizer.windowStart]) once every 32 tokens, and the word that makes it jump re-reads
     * the new window from `<s>`: about 33 steps. The loops in [advance], [logits] and [topWords]
     * allocate nothing; only the returned lists do.
     */
    inner class Session constructor() {
        private val cell = FloatArray(hidden)
        private val out = FloatArray(embedDim)
        private val gates = FloatArray(3 * hidden)
        private val hiddenOut = FloatArray(hidden)
        private var read = IntArray(CifgTokenizer.CONTEXT_TOKENS)
        private var readCount = 0
        private var topIds = IntArray(8)
        private var topScores = FloatArray(8)

        /** Cell steps the last [advance] ran: the whole context after [reset], or only what was new. */
        var lastSteps = 0
            private set

        /** Forgets the state, so the next [advance] starts from `<s>` again. */
        fun reset() {
            cell.fill(0f)
            out.fill(0f)
            readCount = 0
        }

        /**
         * Brings the state to the end of [ids], read from the zero state. Ids already read are
         * skipped when [ids] starts with them; anything else restarts from the beginning.
         */
        fun advance(ids: IntArray) {
            var same = 0
            val shared = minOf(ids.size, readCount)
            while (same < shared && ids[same] == read[same]) same++
            if (same < readCount) {
                reset()
                same = 0
            }
            if (read.size < ids.size) read = read.copyOf(ids.size)
            for (k in same until ids.size) {
                step(ids[k])
                read[k] = ids[k]
            }
            readCount = ids.size
            lastSteps = ids.size - same
        }

        /** Every token's logit after the last [advance], into [into] (size [vocabSize]). */
        fun logits(into: FloatArray) {
            require(into.size >= vocabSize)
            for (v in 0 until vocabSize) into[v] = score(v)
        }

        /**
         * The [limit] best offerable words that start with [prefix] (normalized; empty for the next
         * word), by logit after the last [advance], ties to the lower id. Only the words in the
         * prefix's range are scored, so a typed letter makes this much cheaper than the empty prefix.
         */
        fun topWords(prefix: String, limit: Int = 3): List<Candidate> {
            require(limit > 0)
            if (topIds.size < limit) {
                topIds = IntArray(limit)
                topScores = FloatArray(limit)
            }
            var count = 0
            if (prefix.isEmpty()) {
                for (id in offerable) count = offer(id, score(id), count, limit)
            } else {
                var i = lowerBound(prefix)
                while (i < spellings.size && spellings[i].startsWith(prefix)) {
                    val id = bySpelling[i++]
                    count = offer(id, score(id), count, limit)
                }
            }
            return List(count) { Candidate(tokens[topIds[it]], topIds[it], topScores[it]) }
        }

        /** Encode, advance and rank in one call: the keyboard-shaped entry point. */
        fun predict(beforeCursor: String, limit: Int = 3): List<Candidate> {
            val encoded = encode(beforeCursor)
            advance(encoded.ids)
            return topWords(encoded.prefix, limit)
        }

        /**
         * Direct correction scores in supplied order, without ranking the vocabulary. Exact OOV,
         * non-word and duplicate entries are omitted, never mapped to <unk>. Scores are logits,
         * not intent probabilities. Context excludes the word being corrected; a trailing separator
         * completes a final context word. Use a separate worker-owned correction session.
         */
        fun scoreWords(contextBeforeWord: String, candidates: List<String>): List<Candidate> {
            require(candidates.size <= CorrectionSnapshot.MAX_CANDIDATES) { "at most 16 correction words" }
            if (candidates.isEmpty()) return emptyList()
            val encoded = encode(contextBeforeWord + " ")
            advance(encoded.ids)
            return candidates.distinct().mapNotNull { word ->
                val id = idByToken[word]
                if (id == null || !isOfferable(word)) null else Candidate(word, id, score(id))
            }
        }

        /** CIFGLM.__call__ for one token: gates from the input and the projected state, then the cell. */
        private fun step(token: Int) {
            val e = embedDim
            val h = hidden
            val g = 3 * h
            bias.copyInto(gates, 0, 0, g)
            val tokenScale = embeddingScale[token]
            val tokenRow = embeddingAt + token * e
            for (k in 0 until e) {
                val xs = blob[tokenRow + k] * tokenScale * wxScale[k]
                val rs = out[k] * wrScale[k]
                val wxRow = wxAt + k * g
                val wrRow = wrAt + k * g
                for (j in 0 until g) gates[j] += xs * blob[wxRow + j] + rs * blob[wrRow + j]
            }
            for (k in 0 until h) {
                val forget = sigmoid(gates[k])
                val c = forget * cell[k] + (1f - forget) * tanh(gates[2 * h + k])
                cell[k] = c
                hiddenOut[k] = sigmoid(gates[h + k]) * tanh(c)
            }
            out.fill(0f)
            for (k in 0 until h) {
                val hs = hiddenOut[k] * projScale[k]
                val row = projAt + k * e
                for (j in 0 until e) out[j] += hs * blob[row + j]
            }
        }

        /** The tied output layer for one token: the projected state against its embedding row. */
        private fun score(id: Int): Float {
            var acc = 0f
            val row = embeddingAt + id * embedDim
            for (k in 0 until embedDim) acc += out[k] * blob[row + k]
            return acc * embeddingScale[id]
        }

        /** Inserts into the running top list, highest logit first, ties to the lower id. */
        private fun offer(id: Int, score: Float, count: Int, limit: Int): Int {
            if (count == limit && !ahead(score, id, topScores[limit - 1], topIds[limit - 1])) return count
            var pos = if (count == limit) limit - 1 else count
            while (pos > 0 && ahead(score, id, topScores[pos - 1], topIds[pos - 1])) {
                topScores[pos] = topScores[pos - 1]
                topIds[pos] = topIds[pos - 1]
                pos--
            }
            topScores[pos] = score
            topIds[pos] = id
            return if (count == limit) count else count + 1
        }

        private fun ahead(score: Float, id: Int, otherScore: Float, otherId: Int): Boolean =
            score > otherScore || (score == otherScore && id < otherId)
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = spellings.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (spellings[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val FORMAT_VERSION = 1
        private const val BOS_ID = 1
        private const val UNK_ID = 3
        private const val HEADER_BYTES = 24
        private val MAGIC = byteArrayOf('L'.code.toByte(), 'T'.code.toByte(), 'C'.code.toByte(), 'F'.code.toByte())

        private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

        /** A word the keyboard could offer: AdaptiveNgramModel.predictScored drops other one-letter words. */
        fun isOfferable(token: String): Boolean = isOfferable(token, LanguageProfile.ENGLISH.oneLetterWords)

        /** The same test with another language's one-letter words ([LanguageProfile.oneLetterWords]). */
        fun isOfferable(token: String, oneLetterWords: Set<String>): Boolean =
            AdaptiveNgramModel.WORD_REGEX.matches(token) && (token.length >= 2 || token in oneLetterWords)

        /**
         * Parses a whole `cifg_int8.bin` without duplicating its weight buffer. Fails with
         * [IllegalArgumentException] on a wrong magic, version, size, checksum or vocabulary.
         */
        fun load(blob: ByteArray): CifgModel {
            require(blob.size >= HEADER_BYTES + 4) { "${blob.size} bytes is too short for a CIFG model" }
            require((0 until 4).all { blob[it] == MAGIC[it] }) { "not a LocalType CIFG model" }
            val buf = ByteReader(blob, littleEndian = true)
            val version = buf.getInt(4)
            require(version == FORMAT_VERSION) { "format version $version, expected $FORMAT_VERSION" }
            val v = buf.getInt(8)
            val e = buf.getInt(12)
            val h = buf.getInt(16)
            val vocabBytes = buf.getInt(20)
            require(v in CifgTokenizer.SPECIALS.size..MAX_VOCAB && e in 1..MAX_WIDTH && h in 1..MAX_WIDTH && vocabBytes > 0) {
                "implausible shape: vocabulary $v, embedding $e, hidden $h, vocabulary bytes $vocabBytes"
            }
            fun section(rows: Int, cols: Int): Long = 4L * rows + rows.toLong() * cols + padding(rows.toLong() * cols)
            val expected = HEADER_BYTES + section(v, e) + 2 * section(e, 3 * h) + section(h, e) + 12L * h + vocabBytes + 4
            require(blob.size.toLong() == expected) { "${blob.size} bytes, expected $expected for that shape" }
            val crc = crc32(blob, 0, blob.size - 4).toInt()
            require(crc == buf.getInt(blob.size - 4)) { "checksum mismatch: the file is damaged" }

            var at = HEADER_BYTES
            fun scales(rows: Int): FloatArray = FloatArray(rows) { buf.getFloat(at + 4 * it) }.also { at += 4 * rows }
            fun matrix(rows: Int, cols: Int): Int = at.also { at += rows * cols + padding(rows.toLong() * cols).toInt() }
            val embeddingScale = scales(v)
            val embeddingAt = matrix(v, e)
            val wxScale = scales(e)
            val wxAt = matrix(e, 3 * h)
            val wrScale = scales(e)
            val wrAt = matrix(e, 3 * h)
            val projScale = scales(h)
            val projAt = matrix(h, e)
            val bias = scales(3 * h)

            val words = blob.decodeToString(at, at + vocabBytes).split('\n')
            require(words.size == v + 1 && words.last().isEmpty()) { "${words.size - 1} vocabulary lines for $v rows" }
            val tokens = words.subList(0, v).toTypedArray()
            require(tokens.take(CifgTokenizer.SPECIALS.size) == CifgTokenizer.SPECIALS) { "the vocabulary must start with ${CifgTokenizer.SPECIALS}" }
            require(tokens.toHashSet().size == v) { "the vocabulary has duplicate tokens" }
            return CifgModel(v, e, h, blob, embeddingAt, embeddingScale, wxAt, wxScale, wrAt, wrScale, projAt, projScale, bias, tokens)
        }

        private const val MAX_VOCAB = 1 shl 20
        private const val MAX_WIDTH = 1 shl 14

        private fun padding(bytes: Long): Long = (4 - bytes % 4) % 4
    }
}
