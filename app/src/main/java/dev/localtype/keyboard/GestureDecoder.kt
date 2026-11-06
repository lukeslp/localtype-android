package dev.localtype.keyboard

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where the letter keys sit, for reading a swipe: each key's centre, x in key widths from the left
 * edge and y in rows from the top, as [KeyGeometry] has them. A swipe carries its own [aspect] (a
 * row's height in key widths), because a row is taller than a key is wide and distances must be
 * measured in one unit. A table, not [KeyGeometry]'s constants, so a layout with other keys can
 * supply its own.
 */
class GestureLayout(centers: Map<Char, Touch>) {
    val x = FloatArray(LETTERS) { centers[('a' + it)]?.x ?: Float.NaN }
    val row = FloatArray(LETTERS) { centers[('a' + it)]?.y ?: Float.NaN }

    companion object {
        const val LETTERS = 26
        val QWERTY = GestureLayout(('a'..'z').associateWith { KeyGeometry.center(it)!! })
    }
}

/**
 * The words a swipe can be read as, grouped by the keys their path passes through. A swipe cannot
 * tell a doubled letter from a single one, an accent from none, or an apostrophe from nothing, so
 * "its" and "it's", or "pero" and "perro", draw one path. Every spelling is kept under its path and
 * ranked by its own frequency; none is dropped.
 */
class GestureIndex(words: Iterable<Pair<String, Int>>, val layout: GestureLayout = GestureLayout.QWERTY) {
    /** [keys] are letter indices (0 for a) with repeats collapsed; [words] are most frequent first. */
    class Group(val keys: ByteArray, val words: Array<String>, val scores: IntArray)

    val groups: Array<Group>
    private val buckets: Array<IntArray>
    private val byPath: Map<String, Int>

    init {
        val grouped = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
        for ((word, score) in words) {
            val path = path(word)
            if (path.isEmpty() || path.any { layout.x[it - 'a'].isNaN() }) continue
            grouped.getOrPut(path) { ArrayList() } += word to score
        }
        groups = grouped.map { (path, entries) ->
            val sorted = entries.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            Group(ByteArray(path.length) { (path[it] - 'a').toByte() }, Array(sorted.size) { sorted[it].first }, IntArray(sorted.size) { sorted[it].second })
        }.toTypedArray()
        byPath = grouped.keys.withIndex().associate { (index, path) -> path to index }
        val lists = Array(LETTERS * LETTERS) { ArrayList<Int>() }
        groups.forEachIndexed { index, group -> lists[group.keys.first() * LETTERS + group.keys.last()] += index }
        buckets = Array(lists.size) { lists[it].toIntArray() }
    }

    val wordCount: Int get() = groups.sumOf { it.words.size }

    /** The groups whose path starts on key [first] and ends on key [last]. */
    fun bucket(first: Int, last: Int): IntArray = buckets[first * LETTERS + last]

    /** The group [word] belongs to, or -1 when it is not in the index. */
    fun groupOf(word: String): Int = byPath[path(word)]?.takeIf { word in groups[it].words } ?: -1

    companion object {
        private const val LETTERS = GestureLayout.LETTERS
        private val MARKS = Regex("\\p{Mn}+")

        /** The letters a to z of [word], lowercased, with accents removed and everything else dropped. */
        fun fold(word: String): String {
            val plain = MARKS.replace(Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
            return plain.filter { it in 'a'..'z' }
        }

        /** [fold] with each run of one letter collapsed: the keys a finger passes, in order. */
        fun path(word: String): String {
            val folded = fold(word)
            val keys = StringBuilder(folded.length)
            for (c in folded) if (keys.isEmpty() || keys.last() != c) keys.append(c)
            return keys.toString()
        }
    }
}

/**
 * Reads a swipe as a word by template matching, in the family of SHARK2 (Kristensson and Zhai, 2004):
 * each candidate word's ideal path runs straight from key centre to key centre; the swipe and the
 * ideal path are resampled to the same number of evenly spaced points and compared twice, once where
 * they lie on the keyboard (location) and once as shapes moved and scaled onto each other (shape).
 * Candidates are the words that start near the swipe's first point and end near its last, with a
 * path of comparable length. The geometry is combined with how common the word is, and with
 * [context] when the caller has a previous word.
 *
 * Pure: no Android, no state between calls except scratch buffers, so one decoder is for one thread.
 */
class GestureDecoder(private val index: GestureIndex, private val settings: Settings = Settings()) {
    /**
     * Distances are in key widths. [locationSigma] and [shapeSigma] say how far a real swipe strays
     * from the ideal one in each comparison; [frequencyWeight] is what one point of
     * [WordDictionary.score] is worth against them. The three were searched on the first 6,000 swipes
     * of FUTO's dev split on 2026-10-13; first-choice accuracy is flat (within half a point) over a wide
     * region around these values.
     */
    data class Settings(
        val points: Int = 32,
        val locationSigma: Float = 0.5f,
        val shapeSigma: Float = 0.15f,
        val frequencyWeight: Float = 0.035f,
        val endRadius: Float = 1.5f,
        val endKeys: Int = 4,
        val lengthSlack: Float = 1.2f,
        val lengthRatio: Float = 0.45f,
        val minShapeSize: Float = 0.75f,
    )

    data class Candidate(val word: String, val score: Float)

    /** [scored] is how many paths were compared; [probed] is whether the probe word's path was one of them. */
    class Reading(val candidates: List<Candidate>, val scored: Int, val probed: Boolean)

    private val n = settings.points
    // The swipe resampled, where it lies on the keys (raw) and moved and scaled for the shape comparison.
    private val gxRaw = FloatArray(n)
    private val gyRaw = FloatArray(n)
    private val gx = FloatArray(n)
    private val gy = FloatArray(n)
    private val tx = FloatArray(n)
    private val ty = FloatArray(n)
    private val kx = FloatArray(GestureLayout.LETTERS)
    private val ky = FloatArray(GestureLayout.LETTERS)
    private val px = FloatArray(MAX_KEYS)
    private val py = FloatArray(MAX_KEYS)

    /**
     * The [limit] best words for a swipe of [count] points: [xs] in key widths, [ys] in rows, with a
     * row [aspect] key widths tall. [context] adds a bonus for a word, in the same units as the
     * score. [probe] asks whether one word's path survived the pruning, for measuring it.
     */
    fun decode(
        xs: FloatArray, ys: FloatArray, count: Int, aspect: Float, limit: Int = 3,
        context: ((String) -> Float)? = null, probe: String? = null,
    ): Reading {
        if (count <= 0 || limit <= 0) return Reading(emptyList(), 0, false)
        val layout = index.layout
        for (key in 0 until GestureLayout.LETTERS) {
            kx[key] = layout.x[key]
            ky[key] = layout.row[key] * aspect
        }
        val scaled = FloatArray(count) { ys[it] * aspect }
        val length = resample(xs, scaled, count, gxRaw, gyRaw)
        val starts = keysNear(gxRaw[0], gyRaw[0])
        val ends = keysNear(gxRaw[n - 1], gyRaw[n - 1])
        System.arraycopy(gxRaw, 0, gx, 0, n)
        System.arraycopy(gyRaw, 0, gy, 0, n)
        val probeGroup = probe?.let(index::groupOf) ?: -1
        var probed = false
        var scored = 0
        val best = ArrayList<Candidate>(limit + 1)
        val gestureSize = normalize(gx, gy)
        for (start in starts) for (end in ends) {
            for (id in index.bucket(start, end)) {
                val group = index.groups[id]
                val keys = group.keys
                if (keys.size > MAX_KEYS) continue
                var ideal = 0f
                for (i in keys.indices) {
                    px[i] = kx[keys[i].toInt()]
                    py[i] = ky[keys[i].toInt()]
                    if (i > 0) ideal += distance(px[i - 1], py[i - 1], px[i], py[i])
                }
                if (abs(ideal - length) > settings.lengthSlack + settings.lengthRatio * max(ideal, length)) continue
                scored++
                if (id == probeGroup) probed = true
                resample(px, py, keys.size, tx, ty)
                var location = 0f
                for (i in 0 until n) location += distance(gxRaw[i], gyRaw[i], tx[i], ty[i])
                location /= n
                var cost = square(location / settings.locationSigma)
                val templateSize = normalize(tx, ty)
                if (gestureSize >= settings.minShapeSize && templateSize >= settings.minShapeSize) {
                    var shape = 0f
                    for (i in 0 until n) shape += distance(gx[i], gy[i], tx[i], ty[i])
                    cost += square(shape / n / settings.shapeSigma)
                }
                val geometry = -0.5f * cost
                // A lower bound on what any spelling here can score would let most groups skip this loop;
                // it is not worth it at these sizes.
                for (w in group.words.indices) {
                    val word = group.words[w]
                    val score = geometry + settings.frequencyWeight * group.scores[w] + (context?.invoke(word) ?: 0f)
                    if (best.size == limit && score <= best.last().score) continue
                    var at = best.size
                    while (at > 0 && best[at - 1].score < score) at--
                    best.add(at, Candidate(word, score))
                    if (best.size > limit) best.removeAt(limit)
                }
            }
        }
        return Reading(best, scored, probed)
    }

    /** Moves the points' centre to the origin and scales their longer side to 1. Returns that side's length before. */
    private fun normalize(x: FloatArray, y: FloatArray): Float {
        var minX = x[0]; var maxX = x[0]; var minY = y[0]; var maxY = y[0]
        var sumX = 0f; var sumY = 0f
        for (i in 0 until n) {
            if (x[i] < minX) minX = x[i]
            if (x[i] > maxX) maxX = x[i]
            if (y[i] < minY) minY = y[i]
            if (y[i] > maxY) maxY = y[i]
            sumX += x[i]; sumY += y[i]
        }
        val size = max(maxX - minX, maxY - minY)
        val scale = if (size > 1e-3f) 1f / size else 0f
        val cx = sumX / n
        val cy = sumY / n
        for (i in 0 until n) {
            x[i] = (x[i] - cx) * scale
            y[i] = (y[i] - cy) * scale
        }
        return size
    }

    /** Up to [Settings.endKeys] keys within [Settings.endRadius] of a point, nearest first; always the nearest. */
    private fun keysNear(x: Float, y: Float): IntArray {
        val keys = IntArray(settings.endKeys)
        val distances = FloatArray(settings.endKeys)
        var found = 0
        var nearest = -1
        var nearestDistance = Float.MAX_VALUE
        for (key in 0 until GestureLayout.LETTERS) {
            if (kx[key].isNaN()) continue
            val d = distance(x, y, kx[key], ky[key])
            if (d < nearestDistance) { nearestDistance = d; nearest = key }
            if (d > settings.endRadius) continue
            var at = found
            while (at > 0 && distances[at - 1] > d) at--
            if (at >= keys.size) continue
            val last = minOf(found, keys.size - 1)
            for (i in last downTo at + 1) { keys[i] = keys[i - 1]; distances[i] = distances[i - 1] }
            keys[at] = key
            distances[at] = d
            if (found < keys.size) found++
        }
        return if (found == 0) intArrayOf(nearest) else keys.copyOf(found)
    }

    /** [n] points evenly spaced along the line through the first [count] of ([x], [y]). Returns the line's length. */
    private fun resample(x: FloatArray, y: FloatArray, count: Int, outX: FloatArray, outY: FloatArray): Float {
        var total = 0f
        for (i in 1 until count) total += distance(x[i - 1], y[i - 1], x[i], y[i])
        if (count == 1 || total <= 0f) {
            outX.fill(x[0]); outY.fill(y[0])
            return 0f
        }
        val step = total / (n - 1)
        var segment = 1
        var before = 0f
        var segmentLength = distance(x[0], y[0], x[1], y[1])
        for (i in 0 until n) {
            val target = if (i == n - 1) total else step * i
            while (segment < count - 1 && before + segmentLength < target) {
                before += segmentLength
                segment++
                segmentLength = distance(x[segment - 1], y[segment - 1], x[segment], y[segment])
            }
            val along = if (segmentLength > 0f) ((target - before) / segmentLength).coerceIn(0f, 1f) else 0f
            outX[i] = x[segment - 1] + (x[segment] - x[segment - 1]) * along
            outY[i] = y[segment - 1] + (y[segment] - y[segment - 1]) * along
        }
        return total
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun square(value: Float) = value * value

    private companion object {
        /** No word's path passes more keys than this; a longer one is not a word anyone swipes. */
        const val MAX_KEYS = 40
    }
}
