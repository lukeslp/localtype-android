package dev.localtype.keyboard

import kotlin.math.hypot

/** Bounded transient touch data. Coordinates use the actual QWERTY cells, including an optional number row. */
data class SwipePath(val x: FloatArray, val y: FloatArray, val time: FloatArray, val aspect: Float)

class SwipeGesture(val aspect: Float) {
    data class Point(val x: Float, val y: Float, val time: Float)
    val points = ArrayList<Point>()
    var active = false
        private set
    var cancelled = false
        private set
    private var distance = 0f
    var moved = false
        private set

    fun add(rawX: Float, rawY: Float, time: Float) {
        if (cancelled) return
        if (!rawX.isFinite() || !rawY.isFinite() || !time.isFinite() || aspect !in .5f..3f ||
            time < (points.lastOrNull()?.time ?: 0f) || time > 8_000f) {
            cancelled = true
            return
        }
        // A finger that runs past the letters, into the strip, onto Space or beyond q or p, is still swiping:
        // its point stays on the nearest edge of the letter rows, so overshooting a first or last letter keeps
        // the word, and a tap that drifts off the edge as it lifts still types its key.
        val x = rawX.coerceIn(0f, 10f)
        val y = rawY.coerceIn(0f, 3f)
        points.lastOrNull()?.let { distance += hypot(x - it.x, (y - it.y) * aspect) }
        // Every point up to eight seconds of a fast touch screen is kept; only past that is the path thinned.
        if (points.size == MAX_POINTS) {
            val retained = points.filterIndexed { i, _ -> i % 2 == 0 }
            points.clear(); points.addAll(retained)
        }
        points += Point(x, y, time)
        val first = points.first()
        if (hypot(x - first.x, (y - first.y) * aspect) >= .2f) moved = true
        if (time >= 40f && distance >= .9f && hypot(x - first.x, (y - first.y) * aspect) >= .8f) active = true
    }

    fun finish(): SwipePath? = if (!active || cancelled) null else SwipePath(
        FloatArray(points.size) { points[it].x }, FloatArray(points.size) { points[it].y },
        FloatArray(points.size) { points[it].time }, aspect)

    companion object {
        /** Eight seconds at 240 touch reports a second, plus room. */
        const val MAX_POINTS = 2048
    }
}

/** The trail a swipe draws: only its newest stretch, fading toward the older end, so a long word never fills the keys with lines. */
object SwipeTrail {
    /** How much of the path the trail shows, in key widths. */
    const val LENGTH = 5f

    /** The index of the oldest point the trail draws from. */
    fun start(points: List<SwipeGesture.Point>, aspect: Float, length: Float = LENGTH): Int {
        var along = 0f
        for (i in points.size - 1 downTo 1) {
            along += hypot(points[i].x - points[i - 1].x, (points[i].y - points[i - 1].y) * aspect)
            if (along >= length) return i - 1
        }
        return 0
    }
}
