package dev.localtype.keyboard

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The trained swipe encoder (`model/swipe.py`, stage A2 of docs/gesture-typing-2026-10.md), in plain
 * Kotlin with no inference runtime, as [CifgModel] is. A swipe is resampled to [points] points evenly
 * spaced along its length; at each one a two-layer bidirectional GRU says which letter key the finger is
 * indicating, or that it is between keys. [SwipeDecoder] turns that into words.
 *
 * The arithmetic copies MLX's `nn.GRU` and `swipe.features` exactly; `SwipeModelTest` checks it against
 * log-probabilities the Python model wrote, on a tiny model in every run and on a trained one when given.
 * Float weights for now: the trained file is about a megabyte, and int8 can follow once a size is chosen.
 */
class SwipeModel private constructor(
    val hidden: Int, val layers: Int, val points: Int,
    private val inputWeight: FloatArray, private val inputBias: FloatArray,
    private val forward: List<Gru>, private val backward: List<Gru>,
    private val outputWeight: FloatArray, private val outputBias: FloatArray,
) {
    /** MLX's GRU: [wx] is 3H by D, [wh] 3H by H, rows in the order reset, update, new. */
    class Gru(val inputs: Int, val hidden: Int, val wx: FloatArray, val wh: FloatArray, val b: FloatArray, val bhn: FloatArray) {
        /** Runs over [steps] rows of [input] (each [inputs] wide) into [output] at [offset] in each 2H-wide row. */
        fun run(input: FloatArray, steps: Int, reversed: Boolean, output: FloatArray, width: Int, offset: Int) {
            val h = FloatArray(hidden)
            val xp = FloatArray(3 * hidden)
            val hp = FloatArray(3 * hidden)
            for (i in 0 until steps) {
                val t = if (reversed) steps - 1 - i else i
                val row = t * inputs
                for (g in 0 until 3 * hidden) {
                    var sum = b[g]
                    val at = g * inputs
                    for (d in 0 until inputs) sum += wx[at + d] * input[row + d]
                    xp[g] = sum
                    var carried = 0f
                    val from = g * hidden
                    for (d in 0 until hidden) carried += wh[from + d] * h[d]
                    hp[g] = carried
                }
                for (j in 0 until hidden) {
                    val reset = sigmoid(xp[j] + hp[j])
                    val update = sigmoid(xp[hidden + j] + hp[hidden + j])
                    val fresh = tanh(xp[2 * hidden + j] + reset * (hp[2 * hidden + j] + bhn[j]))
                    h[j] = (1f - update) * fresh + update * h[j]
                }
                System.arraycopy(h, 0, output, t * width + offset, hidden)
            }
        }
    }

    /**
     * Log-probabilities, [points] rows of [CLASSES] (blank, then a to z), for a swipe already resampled:
     * [x] in key widths, [y] in rows, [tau] how far through the swipe's time each point falls, with a row
     * [aspect] key widths tall.
     */
    fun logProbabilities(x: FloatArray, y: FloatArray, tau: FloatArray, aspect: Float): Array<FloatArray> {
        val steps = points
        var width = hidden
        var current = FloatArray(steps * hidden)
        val feature = FloatArray(FEATURES)
        for (t in 0 until steps) {
            val yi = y[t] * aspect
            val last = t == steps - 1
            feature[0] = x[t] / 5f - 1f
            feature[1] = y[t] / 1.5f - 1f
            feature[2] = if (last) 0f else (x[t + 1] - x[t]) * 4f
            feature[3] = if (last) 0f else (y[t + 1] * aspect - yi) * 4f
            feature[4] = min(if (last) 0f else (tau[t + 1] - tau[t]) * steps, 8f) / 4f - 1f
            for (key in 0 until KEYS) {
                val dx = x[t] - KEY_X[key]
                val dy = yi - KEY_ROW[key] * aspect
                feature[5 + key] = exp(-(dx * dx + dy * dy) / NEAR)
            }
            for (j in 0 until hidden) {
                var sum = inputBias[j]
                val at = j * FEATURES
                for (d in 0 until FEATURES) sum += inputWeight[at + d] * feature[d]
                current[t * hidden + j] = tanh(sum)
            }
        }
        for (layer in 0 until layers) {
            val next = FloatArray(steps * 2 * hidden)
            forward[layer].run(current, steps, reversed = false, next, 2 * hidden, 0)
            backward[layer].run(current, steps, reversed = true, next, 2 * hidden, hidden)
            current = next
            width = 2 * hidden
        }
        return Array(steps) { t ->
            val logits = FloatArray(CLASSES) { c ->
                var sum = outputBias[c]
                val at = c * width
                for (d in 0 until width) sum += outputWeight[at + d] * current[t * width + d]
                sum
            }
            val top = logits.max()
            var total = 0.0
            for (value in logits) total += exp((value - top).toDouble())
            val norm = top + ln(total).toFloat()
            FloatArray(CLASSES) { logits[it] - norm }
        }
    }

    companion object {
        const val CLASSES = 27
        const val KEYS = 26
        const val FEATURES = 5 + KEYS
        private const val NEAR = 2f * 0.6f * 0.6f
        private val KEY_X = FloatArray(KEYS) { KeyGeometry.center('a' + it)!!.x }
        private val KEY_ROW = FloatArray(KEYS) { KeyGeometry.center('a' + it)!!.y }

        private fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))

        /** Reads what `swipe.py export` writes: "LTSW", six ints, then float32 arrays, little-endian. */
        fun read(input: InputStream): SwipeModel {
            val bytes = DataInputStream(input).readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            require(String(magic, Charsets.US_ASCII) == "LTSW") { "not a swipe model" }
            val format = buffer.int
            val hidden = buffer.int
            val layers = buffer.int
            val points = buffer.int
            val features = buffer.int
            val classes = buffer.int
            require(format == 1 && features == FEATURES && classes == CLASSES && hidden in 1..1024 && layers in 1..8 && points in 2..512) { "unsupported swipe model" }
            fun floats(count: Int) = FloatArray(count).also { buffer.asFloatBuffer().get(it); buffer.position(buffer.position() + 4 * count) }
            val inputWeight = floats(hidden * FEATURES)
            val inputBias = floats(hidden)
            val forward = ArrayList<Gru>()
            val backward = ArrayList<Gru>()
            for (layer in 0 until layers) {
                val inputs = if (layer == 0) hidden else 2 * hidden
                for (list in listOf(forward, backward)) {
                    list += Gru(inputs, hidden, floats(3 * hidden * inputs), floats(3 * hidden * hidden), floats(3 * hidden), floats(hidden))
                }
            }
            val outputWeight = floats(CLASSES * 2 * hidden)
            val outputBias = floats(CLASSES)
            require(!buffer.hasRemaining()) { "swipe model has ${buffer.remaining()} bytes too many" }
            return SwipeModel(hidden, layers, points, inputWeight, inputBias, forward, backward, outputWeight, outputBias)
        }

        /**
         * [count] points evenly spaced along a swipe's length, as `swipe.resample` makes them: [xs] in key
         * widths, [ys] in rows, [ts] in milliseconds, distances measured with a row [aspect] key widths
         * tall. Fills [outX], [outY] and [outTau] (the share of the swipe's time at each point).
         */
        fun resample(xs: FloatArray, ys: FloatArray, ts: FloatArray, aspect: Float, count: Int, outX: FloatArray, outY: FloatArray, outTau: FloatArray) {
            val n = xs.size
            val span = if (n > 1) (ts[n - 1] - ts[0]).toDouble() else 0.0
            fun tau(i: Int) = if (span > 0) (ts[i] - ts[0]) / span else 0.0
            val along = DoubleArray(n)
            for (i in 1 until n) {
                val dx = (xs[i] - xs[i - 1]).toDouble()
                val dy = (ys[i] - ys[i - 1]).toDouble() * aspect
                along[i] = along[i - 1] + sqrt(dx * dx + dy * dy)
            }
            if (n < 2 || along[n - 1] <= 0.0) {
                for (i in 0 until count) {
                    outX[i] = xs[0]; outY[i] = ys[0]; outTau[i] = i.toFloat() / (count - 1)
                }
                return
            }
            // A finger that stops adds time and no length; a hair of length per sample keeps the positions increasing.
            for (i in 0 until n) along[i] += i * 1e-6
            val total = along[n - 1]
            var segment = 1
            for (i in 0 until count) {
                val at = total * i / (count - 1)
                while (segment < n - 1 && along[segment] < at) segment++
                val length = along[segment] - along[segment - 1]
                val share = if (length > 0) ((at - along[segment - 1]) / length).coerceIn(0.0, 1.0) else 0.0
                outX[i] = (xs[segment - 1] + (xs[segment] - xs[segment - 1]) * share).toFloat()
                outY[i] = (ys[segment - 1] + (ys[segment] - ys[segment - 1]) * share).toFloat()
                outTau[i] = (tau(segment - 1) + (tau(segment) - tau(segment - 1)) * share).toFloat()
            }
        }
    }
}

/**
 * Reads a swipe as words with a [SwipeModel]: every key path in the vocabulary is scored under the
 * model's per-point output in one pass over a tree of the paths, and each word adds how common it is.
 * Unlike [GestureDecoder] nothing is pruned by where the swipe starts or ends.
 *
 * A path's score is its single best alignment (a maximum at each node) rather than the sum over all
 * alignments that training used: a comparison where the sum needs a logarithm and an exponential, for
 * about half a point of first-choice accuracy on dev.
 */
class SwipeDecoder(private val model: SwipeModel, private val index: GestureIndex, private val frequencyWeight: Float = 0.1f) {
    private val parents: IntArray
    private val letters: IntArray
    private val groupNode: IntArray
    private val x = FloatArray(model.points)
    private val y = FloatArray(model.points)
    private val tau = FloatArray(model.points)

    init {
        val nodes = HashMap<String, Int>().apply { put("", 0) }
        val parentList = arrayListOf(0)
        val letterList = arrayListOf(0)
        groupNode = IntArray(index.groups.size) { id ->
            val keys = index.groups[id].keys
            val prefix = StringBuilder()
            var node = 0
            for (key in keys) {
                prefix.append('a' + key.toInt())
                node = nodes.getOrPut(prefix.toString()) {
                    parentList += node
                    letterList += key.toInt() + 1
                    parentList.size - 1
                }
            }
            node
        }
        parents = parentList.toIntArray()
        letters = letterList.toIntArray()
    }

    val nodes: Int get() = parents.size

    /** The [limit] best words for a swipe: [xs] in key widths, [ys] in rows, [ts] in milliseconds. */
    fun decode(
        xs: FloatArray, ys: FloatArray, ts: FloatArray, aspect: Float, limit: Int = 3,
        context: ((String) -> Float)? = null,
    ): List<GestureDecoder.Candidate> {
        if (xs.isEmpty() || limit <= 0 || index.groups.isEmpty()) return emptyList()
        SwipeModel.resample(xs, ys, ts, aspect, model.points, x, y, tau)
        val logp = model.logProbabilities(x, y, tau, aspect)
        val count = parents.size
        var on = FloatArray(count) { NEVER }
        var off = FloatArray(count) { NEVER }
        var nextOn = FloatArray(count)
        var nextOff = FloatArray(count)
        // A node's parent always has a smaller index, so one pass in order reads only the step before.
        for (node in 1 until count) if (parents[node] == 0) on[node] = logp[0][letters[node]]
        off[0] = logp[0][0]
        for (step in 1 until logp.size) {
            val row = logp[step]
            val blank = row[0]
            nextOn[0] = NEVER
            nextOff[0] = off[0] + blank
            for (node in 1 until count) {
                val parent = parents[node]
                val stay = on[node]
                nextOn[node] = max(stay, max(on[parent], off[parent])) + row[letters[node]]
                nextOff[node] = max(stay, off[node]) + blank
            }
            val swapOn = on; on = nextOn; nextOn = swapOn
            val swapOff = off; off = nextOff; nextOff = swapOff
        }
        val best = ArrayList<GestureDecoder.Candidate>(limit + 1)
        for (id in index.groups.indices) {
            val node = groupNode[id]
            val fit = max(on[node], off[node])
            if (fit <= NEVER / 2) continue
            val group = index.groups[id]
            for (w in group.words.indices) {
                val word = group.words[w]
                val score = fit + frequencyWeight * group.scores[w] + (context?.invoke(word) ?: 0f)
                if (best.size == limit && score <= best.last().score) continue
                var at = best.size
                while (at > 0 && best[at - 1].score < score) at--
                best.add(at, GestureDecoder.Candidate(word, score))
                if (best.size > limit) best.removeAt(limit)
            }
        }
        return best
    }

    private companion object {
        const val NEVER = -1e9f
    }
}
