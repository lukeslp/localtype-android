package dev.localtype.keyboard

/**
 * Where a long-press alternates popup sits and which option a finger position picks.
 *
 * The first option belongs under the finger, so a still hold picks it. A popup opened on a key near the right edge
 * cannot extend rightward from the key without leaving the screen; instead of sliding it left (which put a later
 * option under the finger, so the slightest wobble picked it), it is mirrored: the first option sits over the key
 * and the rest run leftward. A wide popup over a key mid-keyboard may fit on neither side, so it narrows and wraps
 * into more rows.
 */
internal object PopupGeometry {
    data class Placement(val originX: Float, val mirrored: Boolean, val columns: Int)

    /**
     * Option 0 always sits over the key. Runs rightward when a full row of up to [maxColumns] fits, else
     * mirrored leftward. When neither side holds a full row, it narrows to the roomier side and wraps the
     * rest into more rows, instead of clamping a later option under the finger.
     */
    fun place(anchorCenterX: Float, viewWidth: Float, count: Int, maxColumns: Int, cellWidth: Float): Placement {
        val wanted = minOf(count, maxColumns).coerceAtLeast(1)
        val rightward = anchorCenterX - cellWidth / 2
        val rightEdge = anchorCenterX + cellWidth / 2
        val roomRight = ((viewWidth - rightward.coerceAtLeast(0f)) / cellWidth).toInt()
        val roomLeft = (rightEdge.coerceAtMost(viewWidth) / cellWidth).toInt()
        fun right(cols: Int) = Placement(rightward.coerceAtLeast(0f), mirrored = false, columns = cols)
        fun left(cols: Int) = Placement(
            (rightEdge - cols * cellWidth).coerceIn(0f, (viewWidth - cols * cellWidth).coerceAtLeast(0f)),
            mirrored = true, columns = cols,
        )
        return when {
            wanted <= roomRight -> right(wanted)
            wanted <= roomLeft -> left(wanted)
            roomRight >= roomLeft -> right(roomRight.coerceIn(1, wanted))
            else -> left(roomLeft.coerceIn(1, wanted))
        }
    }

    /** The drawn column of option [index]; the first row is nearest the finger and rows grow upward. */
    fun column(index: Int, columns: Int, mirrored: Boolean): Int =
        (index % columns).let { if (mirrored) columns - 1 - it else it }

    /**
     * The option under ([x], [y]) in view coordinates; above the popup counts as its top row, below it as the first.
     *
     * [reachableTop] is the highest y a finger can report. Android 14 and earlier clamp an input method's touches to
     * its own window, whose top is the strip's top edge, so a second row drawn above it could never be picked. When
     * the popup's top is above that line, its rows share the reachable height between it and the popup's bottom
     * instead, and a finger pinned at the edge picks the top row.
     */
    fun indexAt(
        x: Float, y: Float, placement: Placement, originY: Float,
        columns: Int, rows: Int, cellWidth: Float, cellHeight: Float, count: Int,
        reachableTop: Float = Float.NEGATIVE_INFINITY,
    ): Int {
        val drawn = ((x - placement.originX) / cellWidth).toInt().coerceIn(0, columns - 1)
        val logical = if (placement.mirrored) columns - 1 - drawn else drawn
        val bottom = originY + rows * cellHeight
        val reach = bottom - reachableTop
        val rowHeight = if (rows > 1 && originY < reachableTop && reach > 0f) reach / rows else cellHeight
        val rowFromBottom = ((bottom - y) / rowHeight).toInt().coerceIn(0, rows - 1)
        return (rowFromBottom * columns + logical).coerceAtMost(count - 1)
    }
}
