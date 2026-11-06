package dev.localtype.keyboard

import kotlin.math.abs
import kotlin.math.hypot

/** The four two-finger swipes; [id] names each one in settings. */
enum class TwoFingerSwipe(val id: String) { LEFT("left"), RIGHT("right"), UP("up"), DOWN("down") }

/**
 * Tells a deliberate two-finger swipe from two thumbs typing. Pure: positions are in key widths on both axes, so
 * angles and straightness are true on the screen, and times are in milliseconds.
 */
object GestureClassifier {
    /** The second finger lands within this long of the first, or the two are typing. */
    const val PAIR_WINDOW_MS = 250L
    /** Two touches closer than this when the second lands are one finger seen twice, not two. */
    const val MIN_SEPARATION = 0.6f
    /** Both fingers this far along, within [ACTIVATE_COS] of each other: neither types from here. */
    const val ACTIVATE = 0.8f
    /** cos 45°. */
    const val ACTIVATE_COS = 0.7071f
    /** Travel of each finger along the swipe's axis. */
    const val MIN_TRAVEL = 2f
    /** Along-axis travel at least this many times the cross-axis travel: within about 27° of the axis. */
    const val AXIS_RATIO = 2f
    /** Net distance over path length, for each finger. */
    const val MIN_STRAIGHTNESS = 0.8f
    /** The shorter finger's travel over the longer's. */
    const val MIN_AGREEMENT = 0.5f
    /** From the second finger landing to the last lift; a pair not on its way by then is typing. */
    const val MAX_DURATION_MS = 800L
    /** Key widths a second, for each finger. */
    const val MIN_SPEED = 3f

    /**
     * Path length is counted in steps of at least this many key widths, so a touch screen's jitter between samples
     * (worse the faster it samples) cannot make a straight swipe look crooked.
     */
    const val PATH_STEP = 0.15f

    /** One finger's way from where the pair began: only its ends and its length are kept. */
    class Track(val startX: Float, val startY: Float) {
        var x = startX
            private set
        var y = startY
            private set
        private var anchorX = startX
        private var anchorY = startY
        private var counted = 0f
        var valid = startX.isFinite() && startY.isFinite()
            private set

        fun add(nextX: Float, nextY: Float) {
            if (!valid) return
            if (!nextX.isFinite() || !nextY.isFinite()) { valid = false; return }
            x = nextX
            y = nextY
            val step = hypot(x - anchorX, y - anchorY)
            if (step >= PATH_STEP) {
                counted += step
                anchorX = x
                anchorY = y
            }
        }

        val dx get() = x - startX
        val dy get() = y - startY
        val net get() = hypot(dx, dy)
        /** The counted steps and the last part since. */
        val length get() = counted + hypot(x - anchorX, y - anchorY)
    }

    /** Whether a second touch [gap] ms after the first, [dx] and [dy] key widths from it, may be the start of a pair. */
    fun pairs(gap: Long, dx: Float, dy: Float): Boolean =
        gap in 0..PAIR_WINDOW_MS && dx.isFinite() && dy.isFinite() && hypot(dx, dy) >= MIN_SEPARATION

    /** Both fingers are on their way together, [elapsed] ms after the second landed: from now neither types. */
    fun activates(a: Track, b: Track, elapsed: Long): Boolean {
        if (!a.valid || !b.valid || elapsed !in 0..MAX_DURATION_MS) return false
        val na = a.net
        val nb = b.net
        if (na < ACTIVATE || nb < ACTIVATE) return false
        return (a.dx * b.dx + a.dy * b.dy) / (na * nb) >= ACTIVATE_COS
    }

    /** The swipe the two tracks make, [duration] ms from the second landing to the last lift, or null. */
    fun classify(a: Track, b: Track, duration: Long): TwoFingerSwipe? {
        if (!a.valid || !b.valid || duration <= 0 || duration > MAX_DURATION_MS) return null
        val direction = direction(a) ?: return null
        if (direction(b) != direction) return null
        val ta = travel(a, direction)
        val tb = travel(b, direction)
        if (ta < MIN_TRAVEL || tb < MIN_TRAVEL) return null
        if (minOf(ta, tb) < MIN_AGREEMENT * maxOf(ta, tb)) return null
        if (straightness(a) < MIN_STRAIGHTNESS || straightness(b) < MIN_STRAIGHTNESS) return null
        val seconds = duration / 1000f
        if (ta / seconds < MIN_SPEED || tb / seconds < MIN_SPEED) return null
        return direction
    }

    /** The axis and sign a finger moved along, or null when it went diagonally. Screen y grows downward. */
    fun direction(t: Track): TwoFingerSwipe? {
        val ax = abs(t.dx)
        val ay = abs(t.dy)
        return when {
            ax > 0f && ax >= AXIS_RATIO * ay -> if (t.dx < 0f) TwoFingerSwipe.LEFT else TwoFingerSwipe.RIGHT
            ay > 0f && ay >= AXIS_RATIO * ax -> if (t.dy < 0f) TwoFingerSwipe.UP else TwoFingerSwipe.DOWN
            else -> null
        }
    }

    private fun travel(t: Track, direction: TwoFingerSwipe): Float = when (direction) {
        TwoFingerSwipe.LEFT -> -t.dx
        TwoFingerSwipe.RIGHT -> t.dx
        TwoFingerSwipe.UP -> -t.dy
        TwoFingerSwipe.DOWN -> t.dy
    }

    private fun straightness(t: Track): Float = if (t.length <= 0f) 0f else t.net / t.length
}

/**
 * Two touches that may be a swipe: pointer [first] (held back, not yet typed) and pointer [second], which landed at
 * [start]. Positions are in key widths, times in milliseconds on one clock.
 */
class TwoFingerPair(
    val first: Int, firstX: Float, firstY: Float,
    val second: Int, secondX: Float, secondY: Float,
    private val start: Long,
) {
    private val a = GestureClassifier.Track(firstX, firstY)
    private val b = GestureClassifier.Track(secondX, secondY)

    /** Both fingers are moving together: neither types any more, whatever the pair turns out to be. */
    var active = false
        private set

    fun has(pointer: Int) = pointer == first || pointer == second

    /** A sample for [pointer]; true when it made the pair active. */
    fun add(pointer: Int, x: Float, y: Float, time: Long): Boolean {
        when (pointer) {
            first -> a.add(x, y)
            second -> b.add(x, y)
            else -> return false
        }
        if (active || !GestureClassifier.activates(a, b, time - start)) return false
        active = true
        return true
    }

    /** What the pair was, read when its last finger lifts at [time]; null for anything but a swipe. */
    fun swipe(time: Long): TwoFingerSwipe? = if (active) GestureClassifier.classify(a, b, time - start) else null
}
