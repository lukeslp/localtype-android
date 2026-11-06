package dev.localtype.keyboard

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Kotlin swipe encoder against the Python one. The committed fixtures are a tiny untrained model, swipes with
 * the log-probabilities `model/swipe.py` gave them, and raw paths with what its resampling made of them; regenerate
 * with `python -I model/swipe.py fixtures`. A trained model is checked the same way when `LOCALTYPE_SWIPE_MODEL`
 * names a file from `swipe.py export --golden N`, beside its `.golden.tsv`.
 */
class SwipeModelTest {
    private fun floats(text: String) = text.trim().split(' ').map { it.toFloat() }.toFloatArray()

    private fun parity(model: SwipeModel, golden: File, tolerance: Float): Int {
        var rows = 0
        golden.forEachLine { line ->
            val (aspect, points, expected) = line.split('\t')
            val flat = floats(points)
            val n = model.points
            assertEquals(n * 3, flat.size)
            val logp = model.logProbabilities(FloatArray(n) { flat[it * 3] }, FloatArray(n) { flat[it * 3 + 1] }, FloatArray(n) { flat[it * 3 + 2] }, aspect.toFloat())
            val want = floats(expected)
            var worst = 0f
            for (t in 0 until n) for (c in 0 until SwipeModel.CLASSES) worst = maxOf(worst, abs(logp[t][c] - want[t * SwipeModel.CLASSES + c]))
            assertTrue("row $rows differs from the Python model by $worst", worst < tolerance)
            rows++
        }
        return rows
    }

    @Test
    fun `the port gives the Python model's log-probabilities`() {
        val model = File("src/test/fixtures/swipe_tiny.bin").inputStream().use(SwipeModel::read)
        assertEquals(8, model.hidden)
        assertEquals(6, parity(model, File("src/test/fixtures/swipe_tiny.golden.tsv"), 2e-3f))
    }

    @Test
    fun `the bundled trained model keeps Python parity`() {
        val bytes = File("src/main/assets/swipe_en.bin").readBytes()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(SwipeWorker.SHA256, hash)
        assertEquals(6, parity(SwipeModel.read(bytes.inputStream()), File("src/test/fixtures/swipe_en.golden.tsv"), 5e-3f))
    }

    @Test
    fun `a trained model matches too, when one is given`() {
        val path = System.getenv("LOCALTYPE_SWIPE_MODEL")?.trim().orEmpty()
        assumeTrue("set LOCALTYPE_SWIPE_MODEL to a file from swipe.py export --golden N", path.isNotEmpty())
        val file = File(path)
        val golden = File(file.parentFile, file.nameWithoutExtension + ".golden.tsv")
        // The golden points went through float16 on their way to the Python model; the port sees them as printed.
        assertTrue(parity(file.inputStream().use(SwipeModel::read), golden, 5e-3f) > 0)
    }

    @Test
    fun `resampling matches the Python resampling`() {
        var rows = 0
        File("src/test/fixtures/swipe_resample_cases.tsv").forEachLine { line ->
            val (aspect, raw, expected) = line.split('\t')
            val points = raw.split(';').map { it.split(',') }
            val xs = FloatArray(points.size) { points[it][0].toFloat() }
            val ys = FloatArray(points.size) { points[it][1].toFloat() }
            val ts = FloatArray(points.size) { points[it][2].toFloat() }
            val n = 48
            val x = FloatArray(n); val y = FloatArray(n); val tau = FloatArray(n)
            SwipeModel.resample(xs, ys, ts, aspect.toFloat(), n, x, y, tau)
            val want = floats(expected)
            for (i in 0 until n) {
                assertEquals("x at $i of row $rows", want[i * 3], x[i], 2e-4f)
                assertEquals("y at $i of row $rows", want[i * 3 + 1], y[i], 2e-4f)
                assertEquals("time at $i of row $rows", want[i * 3 + 2], tau[i], 2e-4f)
            }
            rows++
        }
        assertEquals(6, rows)
    }

    @Test
    fun `the decoder offers only words in its index, best first`() {
        val model = File("src/test/fixtures/swipe_tiny.bin").inputStream().use(SwipeModel::read)
        val index = GestureIndex(listOf("hello" to 60, "help" to 55, "its" to 60, "it's" to 70, "a" to 90))
        val decoder = SwipeDecoder(model, index)
        val swipe = GestureEvaluation.synthetic(listOf("hello"), spread = 0f, seed = 1).single()
        val read = decoder.decode(swipe.xs, swipe.ys, swipe.ts, swipe.aspect, limit = 3)
        assertEquals(3, read.size)
        assertTrue(read.all { it.word in setOf("hello", "help", "its", "it's", "a") })
        assertTrue(read.zipWithNext().all { (a, b) -> a.score >= b.score })
        // Two spellings of one path differ only by how common each is.
        val both = decoder.decode(swipe.xs, swipe.ys, swipe.ts, swipe.aspect, limit = 5).associate { it.word to it.score }
        assertEquals(1.0f, both.getValue("it's") - both.getValue("its"), 1e-3f)
        assertEquals(emptyList<GestureDecoder.Candidate>(), SwipeDecoder(model, GestureIndex(emptyList())).decode(swipe.xs, swipe.ys, swipe.ts, swipe.aspect))
    }
}
