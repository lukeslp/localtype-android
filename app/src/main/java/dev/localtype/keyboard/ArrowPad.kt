package dev.localtype.keyboard

import android.view.KeyEvent

/**
 * A move on the arrow pad: the key it sends, and whether Ctrl goes with it (a word at a time, or the whole field's
 * start and end). [SELECT] sends nothing; it turns selecting on and off, which adds Shift to every other move.
 */
enum class ArrowMove(val label: Int, val keyCode: Int, val ctrl: Boolean, val glyph: String? = null) {
    WORD_LEFT(R.string.arrow_word_left, KeyEvent.KEYCODE_DPAD_LEFT, true),
    UP(R.string.arrow_up, KeyEvent.KEYCODE_DPAD_UP, false, "↑"),
    WORD_RIGHT(R.string.arrow_word_right, KeyEvent.KEYCODE_DPAD_RIGHT, true),
    LEFT(R.string.arrow_left, KeyEvent.KEYCODE_DPAD_LEFT, false, "←"),
    SELECT(R.string.arrow_select, 0, false),
    RIGHT(R.string.arrow_right, KeyEvent.KEYCODE_DPAD_RIGHT, false, "→"),
    START(R.string.arrow_start, KeyEvent.KEYCODE_MOVE_HOME, true),
    DOWN(R.string.arrow_down, KeyEvent.KEYCODE_DPAD_DOWN, false, "↓"),
    END(R.string.arrow_end, KeyEvent.KEYCODE_MOVE_END, true),
}

/** The pad's grid and what each move sends. Pure, so the mapping is tested. */
object ArrowPad {
    const val COLUMNS = 3

    /** Three rows of three, read left to right: word moves and Start and End at the corners, Select in the middle. */
    val GRID: List<ArrowMove> = ArrowMove.entries

    /** Shift while selecting, Ctrl for a word or an end of the field; zero for a plain arrow. */
    fun meta(move: ArrowMove, selecting: Boolean): Int {
        var meta = 0
        if (move.ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (selecting) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return meta
    }

    /**
     * The key presses for one move, as (action, key code, meta state). Selecting wraps the move in Shift's own press,
     * because a standard text field extends the selection only while it has seen Shift go down, not from the arrow's
     * meta state alone.
     */
    fun keys(move: ArrowMove, selecting: Boolean): List<Triple<Int, Int, Int>> {
        val meta = meta(move, selecting)
        val arrow = listOf(Triple(KeyEvent.ACTION_DOWN, move.keyCode, meta), Triple(KeyEvent.ACTION_UP, move.keyCode, meta))
        if (!selecting) return arrow
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return listOf(Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, shift)) + arrow +
            Triple(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
    }
}
