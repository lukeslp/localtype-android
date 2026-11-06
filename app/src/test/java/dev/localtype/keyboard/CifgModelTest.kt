package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

/**
 * Parity between [CifgModel] and the Python export it reads. The golden files hold what
 * `model/export.py` computes in float64 from the same int8 weights: the context ids, the top-10
 * ids and logits over the whole vocabulary, and the top-6 offerable words without and with the
 * typed prefix. The tiny random model under `src/test/fixtures` runs every time; the real model
 * runs when the model-data volume is mounted, from `<root>/kotlin/smoke-10min-v2` (written by
 * `export.py --name smoke-10min-v2 --bin-only --out …`), or from `LOCALTYPE_CIFG_EXPORT`.
 */
class CifgModelTest {
    private val tiny: CifgModel by lazy { File(FIXTURES, "cifg_tiny.bin").inputStream().use { CifgModel.load(it) } }

    @Test
    fun `the tokenizer matches model tokenize on the hand-written cases`() {
        val rows = CifgGolden.rows(File(FIXTURES, "cifg_tokenizer_cases.tsv"), "T")
        assertTrue("cases", rows.size > 100)
        checkTokenRows(rows)
    }

    @Test
    fun `the tiny model matches its Python golden file`() {
        val worst = checkGolden(tiny, File(FIXTURES, "cifg_tiny_golden.tsv"))
        println("tiny model: largest top-10 logit difference from Python ${"%.2e".format(worst)}")
    }

    @Test
    fun `the real export matches Python on every golden context`() {
        val export = realExport()
        assumeTrue("model-data volume not mounted: $export", File(export, "cifg_golden.tsv").exists())
        val model = File(export, "cifg_int8.bin").inputStream().use { CifgModel.load(it) }
        val worst = checkGolden(model, File(export, "cifg_golden.tsv"))
        println("real model: largest top-10 logit difference from Python ${"%.2e".format(worst)}")
    }

    @Test
    fun `the tokenizer matches model tokenize on dev lines`() {
        val file = File(realExport(), "cifg_golden_tokens.tsv")
        assumeTrue("model-data volume not mounted: $file", file.exists())
        val rows = CifgGolden.rows(file, "T")
        assertTrue("dev lines", rows.size >= 1000)
        checkTokenRows(rows)
    }

    @Test
    fun `a session steps only over what is new`() {
        val fresh = tiny.newSession()
        val reused = tiny.newSession()
        val expected = FloatArray(tiny.vocabSize)
        val actual = FloatArray(tiny.vocabSize)
        val texts = listOf("hello ", "hello the ", "hello the h", "hello the help ", "thanks ", "")
        val steps = mutableListOf<Int>()
        for (text in texts) {
            val ids = tiny.encode(text).ids
            fresh.reset()
            fresh.advance(ids)
            fresh.logits(expected)
            reused.advance(ids)
            reused.logits(actual)
            steps += reused.lastSteps
            assertTrue("same logits for '$text'", expected.contentEquals(actual))
        }
        // <s> hello; then the; nothing new for a prefix; help; a new sentence restarts; then a bare <s>.
        assertEquals(listOf(2, 1, 0, 1, 2, 1), steps)
    }

    @Test
    fun `past 63 tokens a session re-reads only when the window jumps`() {
        val words = listOf("we", "help", "you", "to", "the", "hey", "it", "thanks")
        val reused = tiny.newSession()
        val fresh = tiny.newSession()
        val expected = FloatArray(tiny.vocabSize)
        val actual = FloatArray(tiny.vocabSize)
        val text = StringBuilder()
        val rereads = mutableListOf<Pair<Int, Int>>()
        for (n in 1..140) {
            text.append(words[(n - 1) % words.size]).append(' ')
            val ids = tiny.encode(text.toString()).ids
            assertEquals("ids after $n words", n - CifgTokenizer.windowStart(n) + 1, ids.size)
            reused.advance(ids)
            if (reused.lastSteps != 1) rereads += n to reused.lastSteps
            fresh.reset()
            fresh.advance(ids)
            fresh.logits(expected)
            reused.logits(actual)
            assertTrue("same logits after $n words", expected.contentEquals(actual))
        }
        // One step per word, except the first word (<s> too) and the words that move the window to 32, 64 and 96.
        assertEquals(listOf(1 to 2, 64 to 33, 96 to 33, 128 to 33), rereads)
        assertEquals(listOf(0, 0, 32, 32, 64, 96), listOf(1, 63, 64, 95, 96, 140).map { CifgTokenizer.windowStart(it) })
        assertEquals("a hop of 1 slides", listOf(0, 1, 77), listOf(63, 64, 140).map { CifgTokenizer.windowStart(it, hop = 1) })
    }

    @Test
    fun `candidates are offerable words that start with the prefix`() {
        val session = tiny.newSession()
        for (text in listOf("", "hello ", "Hello th", "we h", "I don’t t", "zz")) {
            val prefix = tiny.encode(text).prefix
            val words = session.predict(text, limit = 5)
            assertTrue("at most five for '$text'", words.size <= 5)
            assertTrue("offerable for '$text': $words", words.all { CifgModel.isOfferable(it.word) && it.word.startsWith(prefix) })
            assertTrue("best first for '$text'", words.zipWithNext().all { (a, b) -> a.logit >= b.logit })
        }
        assertEquals(emptyList<CifgModel.Candidate>(), session.predict("zz"))
        assertFalse(CifgModel.isOfferable("u"))
        assertTrue(CifgModel.isOfferable("a") && CifgModel.isOfferable("i") && CifgModel.isOfferable("don't"))
        assertFalse(CifgModel.isOfferable("❤️") || CifgModel.isOfferable(".") || CifgModel.isOfferable("<unk>"))
    }

    @Test
    fun `an emoji without its variation selector finds the vocabulary's form`() {
        val heart = tiny.idOf("❤️")
        assertEquals(heart, tiny.idOf("❤"))
        assertEquals(tiny.unkId, tiny.idOf("🦄"))
        assertEquals(tiny.unkId, tiny.idOf("zebra"))
        assertEquals(listOf(tiny.bosId, tiny.idOf("hey"), heart), tiny.encode("hey ❤ ").ids.toList())
    }

    @Test
    fun `direct words omit OOV and nonwords and reuse only their context`() {
        val session = tiny.newSession()
        val words = listOf("help", "hello", "zebra", "<unk>", "help", "HELLO")
        val direct = session.scoreWords("hello", words)
        assertEquals(listOf("help", "hello"), direct.map { it.word })
        val fresh = tiny.newSession()
        fresh.advance(tiny.encode("hello ").ids)
        val logits = FloatArray(tiny.vocabSize)
        fresh.logits(logits)
        direct.forEach { assertEquals(logits[it.id], it.logit, 0f) }
        session.scoreWords("hello", words)
        assertEquals(0, session.lastSteps)
        session.scoreWords("thanks", words)
        assertEquals(2, session.lastSteps)
        assertTrue(session.scoreWords("hello", emptyList()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { session.scoreWords("", List(17) { "help" }) }
    }

    @Test
    fun `a damaged or foreign file is refused`() {
        val good = File(FIXTURES, "cifg_tiny.bin").readBytes()
        fun load(bytes: ByteArray) = CifgModel.load(ByteArrayInputStream(bytes))
        load(good)
        assertThrows(IllegalArgumentException::class.java) { load(good.copyOf().also { it[100] = (it[100] + 1).toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { load(good.copyOf(good.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { load(good.copyOf().also { it[4] = 2 }) }
        assertThrows(IllegalArgumentException::class.java) { load(good.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { load(ByteArray(8)) }
    }

    /** Checks every C row; returns the largest |logit - Python| over the top-10 entries. */
    private fun checkGolden(model: CifgModel, file: File): Double {
        val header = CifgGolden.header(file)
        assertEquals("golden format", listOf("localtype-cifg-golden", "1"), header["format"])
        assertEquals("shape", listOf(model.vocabSize, model.embedDim, model.hidden).map { it.toString() }, header["shape"])
        val rows = CifgGolden.rows(file, "C")
        assertTrue("golden rows", rows.isNotEmpty())
        val session = model.newSession()
        val logits = FloatArray(model.vocabSize)
        var worst = 0.0
        for (row in rows) {
            val text = row[0]
            val encoded = model.encode(text)
            assertEquals("prefix of '$text'", row[1], encoded.prefix)
            assertEquals("ids of '$text'", CifgGolden.ints(row[2]), encoded.ids.toList())
            session.reset()
            session.advance(encoded.ids)
            session.logits(logits)
            val top = logits.indices.sortedWith(compareByDescending<Int> { logits[it] }.thenBy { it }).take(10)
            assertEquals("top-10 ids of '$text'", CifgGolden.ints(row[3]), top)
            CifgGolden.words(row[4]).map { it.toDouble() }.zip(top).forEach { (expected, id) ->
                val diff = abs(logits[id] - expected)
                worst = maxOf(worst, diff)
                assertTrue("logit of ${model.token(id)} after '$text': ${logits[id]} vs $expected", diff <= 1e-3)
            }
            // Direct scoring uses the same context but excludes the uncompleted prefix.
            val directWords = top.map(model::token).filter(CifgModel::isOfferable)
            val direct = model.newSession().scoreWords(text.dropLast(encoded.prefix.length), directWords)
            assertEquals(directWords, direct.map { it.word })
            direct.forEach { candidate ->
                assertEquals("direct logit for ${candidate.word}", logits[candidate.id], candidate.logit, 0f)
                val goldenIndex = top.indexOf(candidate.id)
                assertEquals(CifgGolden.words(row[4])[goldenIndex].toFloat(), candidate.logit, 1e-3f)
            }
            assertEquals("next words after '$text'", CifgGolden.words(row[5]), session.topWords("", 6).map { it.word })
            assertEquals("words for '${encoded.prefix}' after '$text'", CifgGolden.words(row[6]), session.topWords(encoded.prefix, 6).map { it.word })
        }
        return worst
    }

    private fun checkTokenRows(rows: List<List<String>>) {
        for (row in rows) {
            val text = row[0]
            assertEquals("tokens of '$text'", CifgGolden.words(row[1]), CifgTokenizer.tokenize(text))
            val context = CifgTokenizer.context(text)
            assertEquals("prefix of '$text'", row[2], context.prefix)
            assertEquals("context of '$text'", CifgGolden.words(row[3]), context.tokens)
        }
    }

    private fun realExport(): File = System.getenv("LOCALTYPE_CIFG_EXPORT")?.let(::File)
        ?: File(System.getenv("LOCALTYPE_MODEL_DATA") ?: (System.getProperty("user.home") + "/.local/share/localtype-model"), "kotlin/smoke-10min-v2")

    private companion object {
        val FIXTURES = File("src/test/fixtures")
    }
}

/** Reads the golden files `model/export.py` writes: tab-separated rows, a kind letter first, backslash escapes. */
object CifgGolden {
    fun header(file: File): Map<String, List<String>> = file.readLines()
        .map { it.split('\t') }
        .filter { it.first() == "H" }
        .associate { it[1] to it.drop(2) }

    /** The fields after the kind letter, unescaped, of every row of [kind]. */
    fun rows(file: File, kind: String): List<List<String>> = file.readLines()
        .map { it.split('\t') }
        .filter { it.first() == kind }
        .map { fields -> fields.drop(1).map(::unescape) }

    fun words(field: String): List<String> = if (field.isEmpty()) emptyList() else field.split(' ')

    fun ints(field: String): List<Int> = words(field).map { it.toInt() }

    fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') {
                out.append(c)
                continue
            }
            when (val e = s[i++]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                'u' -> out.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                else -> error("unknown escape \\$e")
            }
        }
        return out.toString()
    }
}
