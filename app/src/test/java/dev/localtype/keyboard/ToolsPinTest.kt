package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolsPinTest {
    private val field = StripTools.Field(voice = true, proofread = true)

    @Test
    fun `a row never changed is the built-in one, and saved ids keep their order`() {
        assertEquals(StripTools.ROW_ORDER, StripTools.rowOrder(null))
        assertEquals(listOf(StripTool.SEARCH_WEB, StripTool.UNDO), StripTools.rowOrder(listOf("search_web", "undo")))
        assertEquals(emptyList<StripTool>(), StripTools.rowOrder(emptyList()))
    }

    @Test
    fun `unknown and repeated ids are dropped, and the row holds at most eight`() {
        assertEquals(listOf(StripTool.UNDO, StripTool.REDO), StripTools.rowOrder(listOf("undo", "gone", "undo", "redo")))
        assertEquals(StripTools.MAX_PINNED, StripTools.rowOrder(StripTool.entries.map { it.id }).size)
    }

    @Test
    fun `holding a tile adds it at the end or takes it out`() {
        val start = listOf(StripTool.CLIPBOARD, StripTool.UNDO)
        assertEquals(listOf(StripTool.CLIPBOARD, StripTool.UNDO, StripTool.REDO), StripTools.toggle(start, StripTool.REDO))
        assertEquals(listOf(StripTool.CLIPBOARD), StripTools.toggle(start, StripTool.UNDO))
        assertNull(StripTools.toggle(StripTool.entries.take(StripTools.MAX_PINNED), StripTool.HIDE))
    }

    @Test
    fun `the row follows the saved order and still fits the width`() {
        val order = listOf(StripTool.REDO, StripTool.UNDO, StripTool.TODAY, StripTool.COUNT, StripTool.HIDE, StripTool.ARROWS)
        assertEquals(listOf(StripTool.REDO, StripTool.UNDO, StripTool.TODAY, StripTool.COUNT, StripTool.HIDE), StripTools.row(360, field, order))
        assertEquals(listOf(StripTool.UNDO), StripTools.row(360, StripTools.Field(voice = false, proofread = false), listOf(StripTool.VOICE, StripTool.UNDO)))
    }

    @Test
    fun `a date or time gets a space after it unless one follows already`() {
        assertEquals("12:16 AM ", ToolText.inserted("12:16 AM", null))
        assertEquals("12:16 AM ", ToolText.inserted("12:16 AM", ""))
        assertEquals("12:16 AM ", ToolText.inserted("12:16 AM", "x"))
        assertEquals("12:16 AM", ToolText.inserted("12:16 AM", " "))
    }
}
