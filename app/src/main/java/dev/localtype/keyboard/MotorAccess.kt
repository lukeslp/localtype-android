package dev.localtype.keyboard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Touch accommodations for a tremor or limited reach (Settings → Touch and timing), all off by default and read
 * once per field. [KeyboardView] applies them to direct touch only; TalkBack's explore-by-touch never reaches them.
 *
 * - [ignoreRepeatMs]: a touch that comes down within this long of the last lift is ignored, and so is a second
 *   finger while one is down, so a bounce or a brush of the palm types nothing.
 * - [holdMs]: a key types only once the finger has stayed on it this long; lifting sooner types nothing.
 * - [dwellMs]: the finger stays down and glides; resting on a key this long types it. Lifting types nothing, and
 *   the same key types again only after the finger leaves it or lifts. Replaces [holdMs] while on.
 * - [steadyKeys]: once on a key, the finger keeps it until it moves [MotorRules.STEADY_MARGIN] of a key clearly
 *   past its edge, so a shaking finger does not hop to a neighbour.
 * - [aimFromTouches]: each letter's target moves toward where this person's lifts on it land on average (the
 *   offsets spelling correction already learns), capped at [MotorRules.MAX_AIM_SHIFT] of a key.
 */
data class MotorSettings(
    val ignoreRepeatMs: Int = 0,
    val holdMs: Int = 0,
    val dwellMs: Int = 0,
    val steadyKeys: Boolean = false,
    val aimFromTouches: Boolean = false,
) {
    val dwell: Boolean get() = dwellMs > 0

    /** The hold time that applies: none while dwelling, which has its own timer. */
    val effectiveHoldMs: Int get() = if (dwell) 0 else holdMs

    /** Only one finger at a time: a second touch is a brush, not two-thumb typing. */
    val oneFinger: Boolean get() = ignoreRepeatMs > 0 || dwell

    /** The finger rests and glides, so nothing that reads travel (swipe typing, slides, two-finger swipes) starts. */
    val gliding: Boolean get() = dwell

    /** A key is not typed the moment it is touched: no tap buzz on landing, one when it is ready or typed. */
    val timed: Boolean get() = dwell || holdMs > 0

    val any: Boolean get() = ignoreRepeatMs > 0 || holdMs > 0 || dwell || steadyKeys || aimFromTouches

    companion object {
        val OFF = MotorSettings()

        /** The choices offered in settings, in milliseconds; 0 is off. */
        val REPEAT_CHOICES = listOf(0, 150, 300, 500, 1000)
        val HOLD_CHOICES = listOf(0, 100, 200, 400, 600)
        val DWELL_CHOICES = listOf(0, 400, 600, 900, 1200)

        /** A saved value that is not one of [choices] (an older build, a hand edit) is off. */
        fun choice(saved: Int, choices: List<Int>): Int = if (saved in choices) saved else 0
    }
}

/** A key's cell in view pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** A letter key's learned mean lift, in key widths right and rows down of its center ([TouchOffsets] units). */
data class AimShift(val dx: Float, val dy: Float)

/** One letter key that a touch may be aimed at: its cell, the width of one key in its row, and its learned shift. */
data class AimTarget(val box: Box, val keyWidth: Float, val shift: AimShift?)

/** The pure decisions behind [MotorSettings], so the view only feeds them times and positions. */
object MotorRules {
    /** How far past a key's edge a steady finger may wander and keep it, as a fraction of a key. */
    const val STEADY_MARGIN = 0.35f

    /** The furthest a learned shift moves a letter's target, in key widths or rows. */
    const val MAX_AIM_SHIFT = 0.35f

    /** Lifts a letter needs before its target moves: fewer are too noisy to aim by. */
    const val MIN_AIM_SAMPLES = 12

    /** Whether a touch landing at [downTime] repeats one that lifted at [lastLift]: inside the window, it is ignored. */
    fun ignoresRepeat(windowMs: Int, lastLift: Long?, downTime: Long): Boolean =
        windowMs > 0 && lastLift != null && downTime >= lastLift && downTime - lastLift < windowMs

    /** How much of a hold or dwell of [ms] has passed since [since], from 0 to 1; 1 when there is nothing to wait for. */
    fun progress(since: Long, now: Long, ms: Int): Float =
        if (ms <= 0) 1f else ((now - since).toFloat() / ms).coerceIn(0f, 1f)

    /**
     * Whether lifting types the key under the finger. With a hold time it must be [ready], having been
     * [MotorSettings.holdMs] on that key; while dwelling a lift never types, since resting typed it already or the person moved on.
     */
    fun typesOnLift(settings: MotorSettings, ready: Boolean): Boolean = when {
        settings.dwell -> false
        settings.holdMs > 0 -> ready
        else -> true
    }

    /**
     * When a held key opens its accents or other hold, measured from landing on it: after the hold time and then the
     * usual hold, so the two never meet. While dwelling only Shift has a hold (caps lock), a second dwell after the
     * first typed it; other keys have none, since resting is how they type. Null: no hold.
     */
    fun holdDelay(settings: MotorSettings, base: Long, shift: Boolean): Long? = when {
        settings.dwell -> if (shift) settings.dwellMs * 2L else null
        else -> settings.holdMs + base
    }

    /**
     * Whether holding Shift turns caps lock on. Ignoring repeats or holding before a key types makes a quick double
     * tap on Shift impossible or unreliable, so holding it does the same.
     */
    fun shiftHoldLocks(settings: MotorSettings): Boolean = settings.ignoreRepeatMs > 0 || settings.timed

    /** Whether a finger at [x],[y] keeps the key in [cell] under steady keys: inside it, or within the margin of it. */
    fun staysOn(cell: Box, x: Float, y: Float, keyWidth: Float): Boolean {
        val mx = STEADY_MARGIN * keyWidth
        val my = STEADY_MARGIN * cell.height
        return x >= cell.left - mx && x <= cell.right + mx && y >= cell.top - my && y <= cell.bottom + my
    }

    /** A learned mean lift as a target shift: none below [MIN_AIM_SAMPLES] or when not finite, capped otherwise. */
    fun shift(count: Int, dx: Float, dy: Float): AimShift? {
        if (count < MIN_AIM_SAMPLES || !dx.isFinite() || !dy.isFinite()) return null
        return AimShift(dx.coerceIn(-MAX_AIM_SHIFT, MAX_AIM_SHIFT), dy.coerceIn(-MAX_AIM_SHIFT, MAX_AIM_SHIFT))
    }

    /**
     * Which of [targets] a touch at [x],[y] is aimed at, or [raw] (the key drawn under it). Each target's cell moves
     * by its shift; a touch inside exactly one moved cell is that key's, inside several the one whose moved center is
     * nearest in key units, and inside none the one whose moved cell is nearest. With no shifts the cells tile the
     * rows as drawn, so the answer is [raw].
     */
    fun aim(x: Float, y: Float, targets: List<AimTarget>, raw: Int): Int {
        if (raw !in targets.indices) return raw
        if (targets.all { it.shift == null }) return raw
        val moved = targets.map { t ->
            val sx = (t.shift?.dx ?: 0f) * t.keyWidth
            val sy = (t.shift?.dy ?: 0f) * t.box.height
            Box(t.box.left + sx, t.box.top + sy, t.box.right + sx, t.box.bottom + sy)
        }
        val inside = moved.indices.filter { moved[it].contains(x, y) }
        fun units(i: Int, dx: Float, dy: Float): Float {
            val w = targets[i].keyWidth.takeIf { it > 0f } ?: 1f
            val h = targets[i].box.height.takeIf { it > 0f } ?: 1f
            return (dx / w) * (dx / w) + (dy / h) * (dy / h)
        }
        return when {
            inside.size == 1 -> inside[0]
            inside.size > 1 -> inside.minBy { units(it, x - moved[it].centerX, y - moved[it].centerY) }
            else -> moved.indices.minBy { i ->
                val b = moved[i]
                val dx = max(0f, max(b.left - x, x - b.right))
                val dy = max(0f, max(b.top - y, y - b.bottom))
                units(i, dx, dy)
            }
        }
    }

    /** Whether two cells are in neighbouring rows or the same one, so a letter only trades with letters beside it. */
    fun nearRows(a: Box, b: Box): Boolean = abs(a.centerY - b.centerY) <= 1.5f * min(a.height, b.height).coerceAtLeast(1f)
}
