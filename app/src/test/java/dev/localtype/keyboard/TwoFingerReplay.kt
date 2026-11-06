package dev.localtype.keyboard

import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Touch streams through the two-finger pairing rules of [KeyboardView], without a view: which key presses type, in
 * what order, how much later a held-back key appears, and which pairs became swipes. It copies the view's rules
 * (`onTouchEvent`, `pairable`, and the hold that settles an undecided pair as typing), so change both together.
 * Positions are in key widths on both axes, times in milliseconds. Every press here is on a key that may be held
 * back; Delete, layer keys and the swipe-typing state are left out, which only makes pairs commoner than in the view.
 */
object TwoFingerReplay {
    sealed class Touch {
        abstract val id: Int
        abstract val time: Long
        abstract val x: Float
        abstract val y: Float
    }
    /** [press] numbers the key presses in the order the fingers came down. */
    data class Down(override val id: Int, override val time: Long, override val x: Float, override val y: Float, val press: Int) : Touch()
    data class Move(override val id: Int, override val time: Long, override val x: Float, override val y: Float) : Touch()
    data class Up(override val id: Int, override val time: Long, override val x: Float, override val y: Float) : Touch()

    class Result {
        /** Presses in the order they typed. */
        val typed = ArrayList<Int>()
        val swipes = ArrayList<TwoFingerSwipe>()
        var pairs = 0
        var activations = 0
        /** For each held-back key, how much later it typed than it would have with gestures off. */
        val held = ArrayList<Long>()
    }

    /** [KeyboardView]'s long-press delay: a second finger still down this long settles its pair as typing. */
    private const val HOLD_MS = 380L

    private class Pointer(val press: Int, var x: Float, var y: Float, val down: Long, var consumed: Boolean = false)

    fun run(touches: List<Touch>, gestures: Boolean = true): Result {
        val result = Result()
        val pointers = LinkedHashMap<Int, Pointer>()
        var pair: TwoFingerPair? = null
        var pairStart = 0L
        fun commit(id: Int) {
            val pointer = pointers.remove(id) ?: return
            if (!pointer.consumed) result.typed += pointer.press
        }
        fun settle(time: Long) {
            val undecided = pair ?: return
            pair = null
            result.held += time - pairStart
            commit(undecided.first)
        }
        fun activate(paired: TwoFingerPair) {
            result.activations++
            pointers[paired.first]?.consumed = true
            pointers[paired.second]?.consumed = true
        }
        for (touch in touches) {
            pair?.let { undecided ->
                val second = pointers[undecided.second]
                if (!undecided.active && second != null && touch.time - second.down >= HOLD_MS) settle(second.down + HOLD_MS)
            }
            when (touch) {
                is Down -> {
                    val first = pointers.entries.singleOrNull()
                    if (gestures && pair == null && first != null && !first.value.consumed &&
                        GestureClassifier.pairs(touch.time - first.value.down, touch.x - first.value.x, touch.y - first.value.y)) {
                        pair = TwoFingerPair(first.key, first.value.x, first.value.y, touch.id, touch.x, touch.y, touch.time)
                        pairStart = touch.time
                        result.pairs++
                    } else {
                        settle(touch.time)
                        pointers.keys.toList().forEach(::commit)
                    }
                    pointers[touch.id] = Pointer(touch.press, touch.x, touch.y, touch.time)
                }
                is Move -> {
                    val pointer = pointers[touch.id] ?: continue
                    val paired = pair?.takeIf { it.has(touch.id) }
                    if (paired != null) {
                        if (paired.add(touch.id, touch.x, touch.y, touch.time)) activate(paired)
                        if (paired.active || touch.id == paired.first) continue
                    }
                    if (pointer.consumed) continue
                    pointer.x = touch.x
                    pointer.y = touch.y
                }
                is Up -> {
                    val paired = pair?.takeIf { it.has(touch.id) }
                    if (paired != null) {
                        if (paired.add(touch.id, touch.x, touch.y, touch.time)) activate(paired)
                        if (paired.active) {
                            pointers.remove(touch.id)
                            if (pointers.containsKey(if (touch.id == paired.first) paired.second else paired.first)) continue
                            pair = null
                            paired.swipe(touch.time)?.let { result.swipes += it }
                            continue
                        }
                        pair = null
                        result.held += touch.time - pairStart
                        commit(paired.first)
                        if (touch.id == paired.first) continue
                    }
                    commit(touch.id)
                }
            }
        }
        return result
    }

    /** One press: where it lands and lifts, and when. */
    class Press(val down: Long, val up: Long, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val path: List<Triple<Long, Float, Float>> = emptyList())

    /**
     * The touches for [presses], numbered in the order they come down, with pointer ids handed out as Android does
     * (the lowest free one), a move every 8 ms along a straight line unless the press carries its own [Press.path].
     */
    fun touches(presses: List<Press>): List<Touch> {
        val ordered = presses.sortedBy { it.down }
        val busyUntil = HashMap<Int, Long>()
        val out = ArrayList<Touch>()
        for ((index, press) in ordered.withIndex()) {
            val id = generateSequence(0) { it + 1 }.first { (busyUntil[it] ?: Long.MIN_VALUE) < press.down }
            busyUntil[id] = press.up
            out += Down(id, press.down, press.x0, press.y0, index)
            if (press.path.isNotEmpty()) press.path.forEach { (t, x, y) -> if (t > press.down && t < press.up) out += Move(id, t, x, y) }
            else {
                var t = press.down + 8
                while (t < press.up) {
                    val f = (t - press.down).toFloat() / (press.up - press.down)
                    out += Move(id, t, press.x0 + (press.x1 - press.x0) * f, press.y0 + (press.y1 - press.y0) * f)
                    t += 8
                }
            }
            out += Up(id, press.up, press.x1, press.y1)
        }
        // At one instant a lift comes before a move, and a move before a landing.
        return out.sortedWith(compareBy<Touch> { it.time }.thenBy { when (it) { is Up -> 0; is Move -> 1; is Down -> 2 } })
    }

    /** A letter row is this many key widths tall: 54 dp rows over 40.5 dp keys on a 411 dp wide phone. */
    const val ROW = 54f / 40.5f

    /** Key centres in key widths, with the letter rows indented as [KeyboardLayout] draws them, and the space bar. */
    fun centre(c: Char): Pair<Float, Float>? {
        val rows = listOf("qwertyuiop" to 0.5f, "asdfghjkl" to 1f, "zxcvbnm" to 2f)
        for ((row, keys) in rows.withIndex()) {
            val at = keys.first.indexOf(c)
            if (at >= 0) return keys.second + at to (row + 0.5f) * ROW
        }
        return if (c == ' ') 5f to 3.5f * ROW else null
    }

    /** How fast two thumbs type: the median time between presses and how long each is held. */
    data class Speed(val name: String, val betweenMs: Double, val holdMs: Double, val holdSd: Double)

    val SPEEDS = listOf(
        Speed("relaxed", 300.0, 95.0, 25.0),
        Speed("brisk", 180.0, 100.0, 30.0),
        Speed("fast", 110.0, 120.0, 35.0),
    )

    /**
     * Two thumbs typing [text] at [speed]: letters left of the middle with the left thumb, the rest and the space bar
     * with the right unless it is still down. A thumb cannot press again until it has lifted. Landings scatter 0.2 key
     * widths around the centre and each finger drifts a little before it lifts. The timing is drawn, not recorded.
     */
    fun typing(text: String, speed: Speed, random: Random, start: Long = 0): List<Press> {
        val free = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)
        var last = start
        val presses = ArrayList<Press>()
        for (c in text.lowercase()) {
            val (cx, cy) = centre(c) ?: continue
            val thumb = when {
                c == ' ' -> if (free[1] <= free[0]) 1 else 0
                cx < 5f -> 0
                else -> 1
            }
            val gap = (speed.betweenMs * exp(0.35 * random.nextGaussian())).coerceAtLeast(30.0).toLong()
            val down = maxOf(last + gap, free[thumb] + 15)
            val hold = (speed.holdMs + speed.holdSd * random.nextGaussian()).coerceIn(40.0, 300.0).toLong()
            val x0 = cx + 0.2f * random.nextGaussian().toFloat()
            val y0 = cy + 0.2f * random.nextGaussian().toFloat()
            val drift = { (0.08f * random.nextGaussian().toFloat()).coerceIn(-0.3f, 0.3f) }
            presses += Press(down, down + hold, x0, y0, x0 + drift(), y0 + drift())
            free[thumb] = down + hold
            last = down
        }
        return presses
    }

    /** A recorded swipe as a press: x in key widths, y from rows to key widths with its own [GestureEvaluation.Swipe.aspect]. */
    fun recorded(swipe: GestureEvaluation.Swipe, start: Long): Press? {
        if (swipe.xs.size < 2) return null
        val path = swipe.xs.indices.map { Triple(start + swipe.ts[it].toLong(), swipe.xs[it], swipe.ys[it] * swipe.aspect) }
        val end = path.last().first.coerceAtLeast(start + 1)
        return Press(start, end, path.first().second, path.first().third, path.last().second, path.last().third, path)
    }

    /**
     * A deliberate two-finger swipe, drawn: [direction], each finger's travel and timing varied, a slight bow, a few
     * degrees off the axis, and jitter of 0.02 key widths a sample (more than a touch screen's). Not a person's gesture; a check that the thresholds
     * leave room for one.
     */
    fun drawnSwipe(direction: TwoFingerSwipe, random: Random, start: Long = 0): List<Press> {
        val horizontal = direction == TwoFingerSwipe.LEFT || direction == TwoFingerSwipe.RIGHT
        val sign = if (direction == TwoFingerSwipe.LEFT || direction == TwoFingerSwipe.UP) -1f else 1f
        val travel = if (horizontal) 2.5f + 2f * random.nextFloat() else 2.4f + 1.2f * random.nextFloat()
        val duration = 150 + random.nextInt(350)
        val separation = 1.2f + 2.3f * random.nextFloat()
        val angle = Math.toRadians(-10.0 + 20.0 * random.nextDouble())
        val gap = random.nextInt(80).toLong()
        val bow = -0.25f + 0.5f * random.nextFloat()
        val lift = random.nextInt(60).toLong()
        val firstLiftsFirst = random.nextBoolean()
        val base = if (horizontal) (if (sign < 0) 8f else 1.5f) to 1.5f * ROW else 3f to (if (sign < 0) 3.4f else 0.6f) * ROW
        return (0..1).map { finger ->
            val ratio = 0.9f + 0.2f * random.nextFloat()
            val ux = (if (horizontal) sign else 0f) * travel * ratio
            val uy = (if (horizontal) 0f else sign) * travel * ratio
            val dx = (ux * cos(angle) - uy * sin(angle)).toFloat()
            val dy = (ux * sin(angle) + uy * cos(angle)).toFloat()
            val x0 = base.first + if (horizontal) 0f else finger * separation
            val y0 = base.second + if (horizontal) finger * separation * 0.6f else 0f
            val down = start + finger * gap
            val up = down + duration + if ((finger == 0) == firstLiftsFirst) 0 else lift
            val path = ArrayList<Triple<Long, Float, Float>>()
            var t = down + 8
            while (t < up) {
                val f = ((t - down).toFloat() / (up - down)).coerceAtMost(1f)
                // Ease in and out, as a flick speeds up and slows.
                val s = (f * f * (3 - 2 * f))
                val side = bow * 4 * s * (1 - s)
                val nx = if (horizontal) 0f else side
                val ny = if (horizontal) side else 0f
                path += Triple(t, x0 + dx * s + nx + 0.02f * random.nextGaussian().toFloat(), y0 + dy * s + ny + 0.02f * random.nextGaussian().toFloat())
                t += 8
            }
            Press(down, up, x0, y0, x0 + dx, y0 + dy, path)
        }
    }
}
