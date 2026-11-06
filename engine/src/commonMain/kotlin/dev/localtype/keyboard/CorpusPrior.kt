package dev.localtype.keyboard

import kotlin.math.roundToInt

/**
 * Which words tend to follow which, counted from the licensed training corpus by `model/prior.py`
 * and bundled as `corpus_prior.bin`, so the ranker has context before it has learned anything.
 * Read-only and immutable once read, so it can be built off the main thread and handed over.
 *
 * Each context is a previous word, normalized as [AdaptiveNgramModel.normalize] does, or the
 * sentence start ([AdaptiveNgramModel.START], spelled [SENTENCE_START] in the asset). Its followers
 * are its likeliest next words with smoothed log-probabilities (natural log, interpolated modified
 * Kneser-Ney), likeliest first. Every word is in the keyboard dictionary; none is a swear word, and
 * no one-letter word other than "a" and "i" is a follower.
 *
 * The asset carries its own sorted word list, so it doesn't depend on the order of `words_en.txt`.
 * Layout, version 1, big-endian:
 *
 * ```
 * 4    magic "LTCP"
 * 2    version (1)
 * 2    flags (0)
 * 4    metadata length M, then M bytes of UTF-8 JSON (corpus manifest hash, build parameters)
 * 4    word count W, then W words: u8 byte length + UTF-8, strictly ascending
 * 4+4  unigram scale (float32 lo, float32 step), then W bytes: ln of each word's corpus share
 * 4    context count C
 * 4+4  follower scale (float32 lo, float32 step)
 * C×3  contexts: u16 word index, u8 follower count; ascending word index
 * F×3  followers: u16 word index, u8 log-probability code; per context in context order
 * 4    CRC-32 of every byte before it
 * ```
 *
 * A code q stands for `lo + q * step`. The memory held is the word strings plus three bytes per
 * follower: about 156,000 followers over 11,000 words for the bundled asset.
 */
class CorpusPrior private constructor(
    private val words: Array<String>,
    private val unigramCodes: ByteArray,
    private val unigramLevels: FloatArray,
    /** Followers of word i are entries `offsets[i] until offsets[i + 1]`. */
    private val offsets: IntArray,
    private val followerWords: ShortArray,
    private val followerCodes: ByteArray,
    private val followerLevels: FloatArray,
    /** The asset's JSON header as written, for the typing log and for checking provenance. */
    val metadata: String,
    /**
     * SHA-256 of the whole asset as read, lowercase hex, which the typing log's `#prior` header
     * records so a replay ranks with the same file. Null for a prior built in memory by [of].
     */
    val sha256: String?,
) {
    /** Words in the asset, contexts and followers together. */
    val size: Int get() = words.size

    /** How many words (counting the sentence start) have followers. */
    val contexts: Int = (0 until words.size).count { offsets[it + 1] > offsets[it] }

    /** A next word and the natural log of its smoothed probability after the context. */
    data class Follower(val word: String, val logProbability: Float)

    /**
     * The followers of [previous], likeliest first, or nothing for a word the prior doesn't hold.
     * [previous] is a normalized word, or [AdaptiveNgramModel.START] for a sentence start.
     */
    fun followers(previous: String): List<Follower> {
        val index = indexOf(if (previous == AdaptiveNgramModel.START) SENTENCE_START else previous)
        if (index < 0) return emptyList()
        return (offsets[index] until offsets[index + 1]).map { i ->
            Follower(words[followerWords[i].toInt() and 0xFFFF], followerLevels[followerCodes[i].toInt() and 0xFF])
        }
    }

    /** The natural log of [word]'s share of all corpus words that had a context, or null if absent. */
    fun logUnigram(word: String): Float? {
        val index = indexOf(word)
        return if (index < 0 || word == SENTENCE_START) null else unigramLevels[unigramCodes[index].toInt() and 0xFF]
    }

    operator fun contains(word: String): Boolean = indexOf(word) >= 0

    /** Every word in the asset, in its stored order. */
    fun allWords(): List<String> = words.asList()

    private fun indexOf(word: String): Int = words.asList().binarySearch(word).let { if (it >= 0) it else -1 }

    companion object {
        const val ASSET = "corpus_prior.bin"

        /** How the asset spells the sentence start; no real word can, since it isn't a letter run. */
        const val SENTENCE_START = "<s>"
        private const val VERSION = 1
        private const val MAGIC = 0x4C544350 // "LTCP"
        private const val MAX_FOLLOWERS = 255

        /** Reads a whole asset. Throws [IOException] if it is damaged, truncated or a version this code doesn't know. */
        fun read(bytes: ByteArray): CorpusPrior {
            if (bytes.size < 32) throw IOException("corpus prior: too short")
            val crc = crc32(bytes, 0, bytes.size - 4)
            val buffer = ByteReader(bytes) // big-endian
            if (crc != (buffer.getInt(bytes.size - 4).toLong() and 0xFFFFFFFFL)) throw IOException("corpus prior: checksum mismatch")
            val digest = hex(sha256(bytes))
            return try {
                parse(buffer, bytes.size - 4, digest)
            } catch (e: ByteReader.Underflow) {
                throw IOException("corpus prior: truncated", e)
            } catch (e: IndexOutOfBoundsException) {
                throw IOException("corpus prior: truncated", e)
            }
        }

        private fun parse(buffer: ByteReader, end: Int, sha256: String): CorpusPrior {
            buffer.limit(end)
            ensure(buffer.getInt() == MAGIC, "not a corpus prior")
            val version = buffer.getShort().toInt() and 0xFFFF
            ensure(version == VERSION, "unknown version $version")
            buffer.getShort() // flags, none defined
            val metadataLength = buffer.getInt()
            ensure(metadataLength in 0..buffer.remaining(), "bad metadata length")
            val metadata = ByteArray(metadataLength).also { buffer.get(it) }.decodeToString()

            val wordCount = buffer.getInt()
            ensure(wordCount in 1..0x10000, "bad word count $wordCount")
            val words = Array(wordCount) {
                val length = buffer.get().toInt() and 0xFF
                ByteArray(length).also { bytes -> buffer.get(bytes) }.decodeToString()
            }
            for (i in 1 until wordCount) ensure(words[i - 1] < words[i], "words out of order at $i")
            val unigramLevels = levels(buffer.getFloat(), buffer.getFloat())
            val unigramCodes = ByteArray(wordCount).also { buffer.get(it) }

            val contextCount = buffer.getInt()
            ensure(contextCount in 0..wordCount, "bad context count $contextCount")
            val followerLevels = levels(buffer.getFloat(), buffer.getFloat())
            val counts = IntArray(wordCount)
            var previous = -1
            repeat(contextCount) {
                val index = buffer.getShort().toInt() and 0xFFFF
                val count = buffer.get().toInt() and 0xFF
                ensure(index in (previous + 1) until wordCount, "contexts out of order")
                ensure(count in 1..MAX_FOLLOWERS, "bad follower count")
                counts[index] = count
                previous = index
            }
            val offsets = IntArray(wordCount + 1)
            for (i in 0 until wordCount) offsets[i + 1] = offsets[i] + counts[i]
            val total = offsets[wordCount]
            val followerWords = ShortArray(total)
            val followerCodes = ByteArray(total)
            for (i in 0 until wordCount) {
                for (j in offsets[i] until offsets[i + 1]) {
                    val word = buffer.getShort()
                    ensure((word.toInt() and 0xFFFF) < wordCount, "follower out of range")
                    followerWords[j] = word
                    followerCodes[j] = buffer.get()
                    // Likeliest first: quantizing keeps the order, so codes never rise within a context.
                    if (j > offsets[i]) ensure((followerCodes[j].toInt() and 0xFF) <= (followerCodes[j - 1].toInt() and 0xFF), "followers out of order")
                }
            }
            ensure(buffer.remaining() == 0, "trailing bytes")
            return CorpusPrior(words, unigramCodes, unigramLevels, offsets, followerWords, followerCodes, followerLevels, metadata, sha256)
        }

        /**
         * A prior holding exactly [followers] (context to likeliest-first followers), for tests and
         * tools. Log-probabilities are quantized to 8 bits as the asset stores them.
         */
        fun of(followers: Map<String, List<Pair<String, Float>>>, metadata: String = "{}"): CorpusPrior {
            val words = (followers.keys + followers.values.flatten().map { it.first }).distinct().sorted().toTypedArray()
            val values = followers.values.flatten().map { it.second }
            val lo = values.minOrNull() ?: 0f
            val step = if (values.isEmpty() || values.max() == lo) 0f else (values.max() - lo) / 255f
            val counts = IntArray(words.size)
            followers.forEach { (context, list) -> counts[words.asList().binarySearch(context)] = list.size }
            val offsets = IntArray(words.size + 1)
            for (i in words.indices) offsets[i + 1] = offsets[i] + counts[i]
            val followerWords = ShortArray(offsets[words.size])
            val followerCodes = ByteArray(offsets[words.size])
            followers.forEach { (context, list) ->
                val start = offsets[words.asList().binarySearch(context)]
                list.sortedByDescending { it.second }.forEachIndexed { k, (word, logProbability) ->
                    followerWords[start + k] = words.asList().binarySearch(word).toShort()
                    followerCodes[start + k] = (if (step == 0f) 0 else ((logProbability - lo) / step).roundToInt()).toByte()
                }
            }
            return CorpusPrior(words, ByteArray(words.size), levels(0f, 0f), offsets, followerWords, followerCodes, levels(lo, step), metadata, null)
        }

        private fun levels(lo: Float, step: Float) = FloatArray(256) { lo + it * step }

        private fun ensure(condition: Boolean, message: String) {
            if (!condition) throw IOException("corpus prior: $message")
        }
    }
}
