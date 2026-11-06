package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.ln

/**
 * The fixture `src/test/fixtures/corpus_prior_tiny.bin` is written by `model/prior.py` from the
 * ten-line corpus and tiny word lists in `src/test/fixtures/corpus-prior/`, from the repo root:
 *
 * ```
 * python -m model.prior --corpus app/src/test/fixtures/corpus-prior --assets app/src/test/fixtures/corpus-prior \
 *     --contexts 4 --followers 3 --discounts 0.5 1.0 1.5 --workers 1 --no-report --out app/src/test/fixtures/corpus_prior_tiny.bin
 * ```
 *
 * `model/tests/test_prior.py` fails if the fixture no longer matches what the script writes, and the
 * expected values below are what the Python reader decodes from it.
 */
class CorpusPriorTest {
    private val fixture = File("src/test/fixtures/corpus_prior_tiny.bin")

    @Test
    fun `reads the fixture the Python builder wrote`() {
        val prior = CorpusPrior.read(fixture.inputStream())

        assertEquals(listOf("<s>", "god", "it's", "later", "ok", "see", "so", "soon", "thank", "you"), prior.allWords())
        assertEquals(5, prior.contexts)
        assertFollowers(listOf("thank" to -0.9229140281677246, "see" to -1.4467058181762695, "ok" to -2.8332133293151855), prior.followers("<s>"))
        assertFollowers(listOf("it's" to -0.6456124782562256), prior.followers("god"))
        assertFollowers(listOf("you" to -0.21425461769104004), prior.followers("see"))
        assertFollowers(listOf("you" to -0.33749985694885254, "god" to -2.5867230892181396), prior.followers("thank"))
        assertFollowers(
            listOf("soon" to -0.6558830738067627, "so" to -2.2375285625457764, "later" to -2.412125825881958),
            prior.followers("you"),
        )
        assertEquals(-1.2078895568847656f, prior.logUnigram("you")!!, 1e-6f)
        assertTrue(prior.metadata.contains("\"followers_per_context\":3"))
        assertTrue(prior.metadata.contains("\"manifest_sha256\":\"fd7237f0e1e316404d0d3d578fb26b257cd1c704b3daa28ef870adb6e82f43ae\""))
    }

    @Test
    fun `fixture values are the Kneser-Ney ones within half a quantization step`() {
        val prior = CorpusPrior.read(fixture.inputStream())
        // "thank" is followed by "you" 7 times (one human-written line counts 4) and by "god" once, with discounts
        // 1.5 and 0.5; "you" follows 2 of the corpus's 20 distinct pairs' contexts.
        val expected = ln((7 - 1.5) / 8 + (1.5 + 0.5) / 8 * 2.0 / 20)
        val step = (-0.21425461769104004 - -2.8332133293151855) / 255
        assertEquals(expected, prior.followers("thank").first().logProbability.toDouble(), step / 2 + 1e-6)
    }

    @Test
    fun `sentence start and unknown words`() {
        val prior = CorpusPrior.read(fixture.inputStream())

        assertEquals(prior.followers(CorpusPrior.SENTENCE_START), prior.followers(AdaptiveNgramModel.START))
        assertTrue(prior.followers("zebra").isEmpty())
        assertTrue(prior.followers("so").isEmpty()) // in the asset as a follower only
        assertTrue("so" in prior)
        assertFalse("zebra" in prior)
        assertNull(prior.logUnigram("zebra"))
        assertNull(prior.logUnigram(CorpusPrior.SENTENCE_START))
    }

    @Test
    fun `damaged assets are refused`() {
        val good = fixture.readBytes()
        val flipped = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val truncated = good.copyOf(good.size - 10)
        val badMagic = resealed(good.copyOf().also { "XXXX".toByteArray().copyInto(it) })
        val newVersion = resealed(good.copyOf().also { it[5] = 2 })
        val trailing = resealed(good.copyOf(good.size - 4) + byteArrayOf(0, 0, 0, 0, 0))
        val empty = ByteArray(0)
        for ((name, bytes) in listOf("flipped" to flipped, "truncated" to truncated, "magic" to badMagic,
            "version" to newVersion, "trailing" to trailing, "empty" to empty)) {
            try {
                CorpusPrior.read(bytes.inputStream())
                fail("$name was accepted")
            } catch (expected: IOException) {
            }
        }
    }

    @Test
    fun `of keeps followers likeliest first within half a step`() {
        val prior = CorpusPrior.of(mapOf("good" to listOf("night" to -3f, "morning" to -1f, "luck" to -2f)))
        val step = 2f / 255

        assertEquals(listOf("morning", "luck", "night"), prior.followers("good").map { it.word })
        prior.followers("good").zip(listOf(-1f, -2f, -3f)).forEach { (follower, logP) ->
            assertEquals(logP, follower.logProbability, step / 2 + 1e-6f)
        }
        assertEquals(1, prior.contexts)
        assertEquals(-5f, CorpusPrior.of(mapOf("a" to listOf("b" to -5f))).followers("a").single().logProbability, 0f)
    }

    @Test
    fun `the bundled asset holds only dictionary words and fits its budget`() {
        val file = File("src/main/assets/${CorpusPrior.ASSET}")
        assertTrue("${file.length()} bytes", file.length() <= 1 shl 20)
        val prior = CorpusPrior.read(file.inputStream())
        val dictionary = (wordList("words_en.txt") + wordList("words_en_modern.txt")).toSet()
        val swears = wordList("words_en_profanity.txt").toSet()

        assertEquals(10_001, prior.contexts)
        val words = prior.allWords().filter { it != CorpusPrior.SENTENCE_START }
        assertTrue(words.filterNot { it in dictionary }.toString(), words.all { it in dictionary })
        assertTrue(words.none { it in swears })
        for (context in prior.allWords()) {
            val followers = prior.followers(context)
            assertTrue(context, followers.size <= 16)
            assertTrue(context, followers.all { it.word.length > 1 || it.word == "a" || it.word == "i" })
            assertEquals(context, followers.sortedByDescending { it.logProbability }, followers)
        }
        assertEquals("you", prior.followers("thank").first().word)
        assertTrue(prior.followers(AdaptiveNgramModel.START).any { it.word == "i" })
        assertTrue(prior.metadata.contains("\"manifest_sha256\":\"c620b2f3d9f06987d8eb68840ad790c386ce48b609d63b100808f578fdf59d9c\""))
        // A full count, not a smoke run written over the bundled file.
        assertTrue(prior.metadata, prior.metadata.contains("\"limit\":null"))
        assertTrue(prior.metadata, prior.metadata.contains("\"lines\":7702748"))
    }

    @Test
    fun `sha256 is the digest of the whole asset as read`() {
        for (file in listOf(fixture, File("src/main/assets/${CorpusPrior.ASSET}"))) {
            val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(file.path, expected, CorpusPrior.read(file.inputStream()).sha256)
        }
        assertNull("a prior built in memory has no asset to name", CorpusPrior.of(mapOf("a" to listOf("b" to -1f))).sha256)
    }

    private fun assertFollowers(expected: List<Pair<String, Double>>, actual: List<CorpusPrior.Follower>) {
        assertEquals(expected.map { it.first }, actual.map { it.word })
        expected.zip(actual).forEach { (e, a) -> assertEquals(e.first, e.second.toFloat(), a.logProbability, 1e-6f) }
    }

    /** Recomputes the trailing CRC, so a test reaches the check after it. */
    private fun resealed(bytes: ByteArray): ByteArray {
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        ByteBuffer.wrap(bytes).putInt(bytes.size - 4, crc.toInt())
        return bytes
    }

    private fun wordList(name: String): List<String> = File("src/main/assets/$name").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.substringBefore('\t').trim().replace('’', '\'').lowercase(Locale.ROOT) }
}
