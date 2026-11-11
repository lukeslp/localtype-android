package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGeometryTest {
    private val cell = 38f
    private val height = 50f
    private val width = 411f
    private val fingerY = 400f

    private fun place(centerX: Float, count: Int, viewWidth: Float) =
        PopupGeometry.place(anchorCenterX = centerX, viewWidth = viewWidth, count = count, maxColumns = 8, cellWidth = cell)

    /** The option a finger held still on the key picks; the popup sits above the key, as in KeyboardView. */
    private fun stillPick(centerX: Float, placement: PopupGeometry.Placement, count: Int): Int {
        val rows = (count + placement.columns - 1) / placement.columns
        val originY = fingerY - rows * height - 30f
        return PopupGeometry.indexAt(centerX, fingerY, placement, originY, placement.columns, rows, cell, height, count)
    }

    /** Key centers in dp, laid out as KeyboardView.computeKeyBounds does with its 3dp side padding. */
    private fun keyCenters(layout: KeyboardLayout, viewWidth: Float): List<Float> {
        val usable = viewWidth - 6f
        return layout.rows.flatMap { row ->
            val rowUnits = row.sumOf { it.width.toDouble() }.toFloat()
            val unit = usable / maxOf(KeyboardLayout.ROW_UNITS, rowUnits)
            var x = 3f + (usable - rowUnits * unit) / 2
            row.mapNotNull { key ->
                val center = x + key.width * unit / 2
                x += key.width * unit
                center.takeUnless { key.isSpacer }
            }
        }
    }

    @Test
    fun `a key with room to its right opens the popup rightward from the finger`() {
        val placement = PopupGeometry.place(anchorCenterX = 40f, viewWidth = width, count = 5, maxColumns = 8, cellWidth = cell)
        assertFalse(placement.mirrored)
        assertEquals(21f, placement.originX, 0.001f)
        assertEquals(0, PopupGeometry.indexAt(40f, 200f, placement, 100f, 5, 1, cell, height, 5))
    }

    @Test
    fun `the period key near the right edge keeps its first option under a still finger`() {
        // "." carries eight alternates; before the fix the popup slid left and a finger at 330 sat over option 5, "…".
        val placement = PopupGeometry.place(anchorCenterX = 330f, viewWidth = width, count = 8, maxColumns = 8, cellWidth = cell)
        assertTrue(placement.mirrored)
        val index = PopupGeometry.indexAt(330f, 200f, placement, 100f, 8, 1, cell, height, 8)
        assertEquals(0, index)
        // The drawn column of option 0 is the one under the finger.
        val left = placement.originX + PopupGeometry.column(0, 8, placement.mirrored) * cell
        assertTrue(330f >= left && 330f < left + cell)
    }

    @Test
    fun `sliding left in a mirrored popup walks through the later options in order`() {
        val placement = PopupGeometry.place(anchorCenterX = 330f, viewWidth = width, count = 8, maxColumns = 8, cellWidth = cell)
        val picked = (0..7).map { step -> PopupGeometry.indexAt(330f - step * cell, 200f, placement, 100f, 8, 1, cell, height, 8) }
        assertEquals((0..7).toList(), picked)
    }

    @Test
    fun `a wide popup mid-keyboard narrows and wraps instead of moving a later option under the finger`() {
        // Number row off, 411dp: holding "r" opens eight options, 4 first. Before the fix the popup fit on neither
        // side, clamped to the left edge, and a still finger at 144.75 sat over option 4, "ɾ".
        val placement = place(144.75f, count = 8, viewWidth = width)
        assertFalse(placement.mirrored)
        assertEquals(7, placement.columns)
        assertEquals(125.75f, placement.originX, 0.001f)
        assertEquals(0, stillPick(144.75f, placement, count = 8))
    }

    @Test
    fun `top row digits stay under a still finger at common phone widths`() {
        // "t" at 411dp (7 options), "u" at 393dp (11) and "e" at 360dp (12), number row off.
        for ((centerX, count, viewWidth) in listOf(Triple(185.25f, 7, 411f), Triple(254.55f, 11, 393f), Triple(91.5f, 12, 360f))) {
            val placement = place(centerX, count, viewWidth)
            assertEquals("$centerX at $viewWidth", 0, stillPick(centerX, placement, count))
            assertTrue(placement.originX >= 0f && placement.originX + placement.columns * cell <= viewWidth)
        }
        // "e" at 360dp wraps into two rows; straight up from the finger is the first option of the second row.
        val placement = place(91.5f, count = 12, viewWidth = 360f)
        val rows = (12 + placement.columns - 1) / placement.columns
        assertEquals(2, rows)
        val originY = fingerY - rows * height - 30f
        val secondRowY = originY + height / 2
        assertEquals(placement.columns, PopupGeometry.indexAt(91.5f, secondRowY, placement, originY, placement.columns, rows, cell, height, 12))
    }

    @Test
    fun `every key at every width keeps its first option under a still finger and the popup on screen`() {
        val layouts = listOf(true, false).flatMap { switchKey ->
            listOf(
                KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, switchKey, numberRow = false),
                KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, switchKey, numberRow = true),
                KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, switchKey),
                KeyboardLayout.build(KeyboardLayout.Mode.MORE_SYMBOLS, switchKey),
            )
        }
        for (viewWidth in (300..1000).map(Int::toFloat)) {
            val centers = layouts.flatMap { keyCenters(it, viewWidth) }.distinct()
            for (centerX in centers) for (count in 2..17) {
                val placement = place(centerX, count, viewWidth)
                val label = "key at $centerX, $count options, ${viewWidth}dp"
                assertEquals(label, 0, stillPick(centerX, placement, count))
                // The drawn cell of option 0 is the one under the finger.
                val left = placement.originX + PopupGeometry.column(0, placement.columns, placement.mirrored) * cell
                assertTrue(label, centerX >= left && centerX < left + cell)
                assertTrue(label, placement.originX >= 0f)
                assertTrue(label, placement.originX + placement.columns * cell <= viewWidth + 0.001f)
                assertTrue(label, (count + placement.columns - 1) / placement.columns <= 5)
            }
        }
    }

    @Test
    fun `drawing and picking agree for every option in both directions`() {
        for (mirrored in listOf(false, true)) {
            val placement = PopupGeometry.Placement(originX = 20f, mirrored = mirrored, columns = 8)
            for (index in 0 until 11) {
                val columns = 8
                val rows = 2
                val x = placement.originX + PopupGeometry.column(index, columns, mirrored) * cell + cell / 2
                val rowFromTop = rows - 1 - index / columns
                val y = 100f + rowFromTop * height + height / 2
                assertEquals(index, PopupGeometry.indexAt(x, y, placement, 100f, columns, rows, cell, height, 11))
            }
        }
    }

    @Test
    fun `a finger clamped to the window's top still reaches the upper row`() {
        val placement = PopupGeometry.Placement(originX = 0f, mirrored = false, columns = 8)
        // Two 50-high rows from y = -95 to 5; the window's top is at -60, inside the upper row.
        val originY = -95f
        val pick = { y: Float -> PopupGeometry.indexAt(cell / 2, y, placement, originY, 8, 2, cell, height, 12, reachableTop = -60f) }
        assertEquals(0, pick(0f))
        assertEquals(8, pick(-60f))
        assertEquals(8, pick(-40f))
        assertEquals(0, pick(-20f))
        // Without clamping the drawn rows decide, as before.
        assertEquals(0, PopupGeometry.indexAt(cell / 2, -40f, placement, originY, 8, 2, cell, height, 12))
        assertEquals(8, PopupGeometry.indexAt(cell / 2, -60f, placement, originY, 8, 2, cell, height, 12))
    }

    @Test
    fun `a popup inside the reachable area keeps its drawn rows`() {
        val placement = PopupGeometry.Placement(originX = 0f, mirrored = false, columns = 8)
        for (y in listOf(-90f, -50f, -45f, 0f)) assertEquals(
            PopupGeometry.indexAt(cell / 2, y, placement, -95f, 8, 2, cell, height, 12),
            PopupGeometry.indexAt(cell / 2, y, placement, -95f, 8, 2, cell, height, 12, reachableTop = -200f),
        )
    }
}
