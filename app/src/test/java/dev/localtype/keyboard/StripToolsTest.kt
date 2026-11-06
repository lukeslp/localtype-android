package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tools the strip's row shows, how many fit, and where each may run. */
class StripToolsTest {
    private val everything = StripTools.Field(voice = true, proofread = true)
    private val plain = GestureRules.State(enabled = false)

    @Test
    fun `with the lead button hidden a 360 dp strip fits five tools and More, a 411 dp Pixel six`() {
        assertEquals(6, StripTools.cells(360))
        assertEquals(listOf(StripTool.CLIPBOARD, StripTool.EMOJI, StripTool.VOICE, StripTool.UNDO, StripTool.PROOFREAD), StripTools.row(360, everything))
        assertEquals(7, StripTools.cells(411))
        assertEquals(StripTool.SELECT_ALL, StripTools.row(411, everything).last())
    }

    @Test
    fun `a tool the field does not offer leaves its place to the next one`() {
        val noVoice = StripTools.row(360, StripTools.Field(voice = false, proofread = true))
        assertEquals(listOf(StripTool.CLIPBOARD, StripTool.EMOJI, StripTool.UNDO, StripTool.PROOFREAD, StripTool.SELECT_ALL), noVoice)
    }

    @Test
    fun `a very narrow strip still has one tool and More`() {
        assertEquals(2, StripTools.cells(150))
        assertEquals(1, StripTools.row(150, everything).size)
    }

    @Test
    fun `the panel lists every offered tool once, in its group`() {
        val listed = StripTools.panel(everything).flatMap { it.second }
        assertEquals(StripTool.entries.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size)
        StripTools.panel(everything).forEach { (group, tools) -> tools.forEach { assertEquals(group, it.group) } }
        assertFalse(StripTools.panel(StripTools.Field(voice = false, proofread = false)).flatMap { it.second }.contains(StripTool.VOICE))
    }

    @Test
    fun `tools run with two-finger gestures switched off`() {
        assertTrue(StripTools.allowed(StripTool.UNDO, plain))
        assertTrue(StripTools.allowed(StripTool.EMOJI, plain))
        assertTrue(StripTools.allowed(StripTool.SETTINGS, plain))
    }

    @Test
    fun `an open panel does not stop a tool, because the panel closes first`() {
        assertTrue(StripTools.allowed(StripTool.CLIPBOARD, plain.copy(panel = true)))
    }

    @Test
    fun `tools that read text keep the gestures' field rules`() {
        val password = plain.copy(sensitive = true)
        assertFalse(StripTools.allowed(StripTool.UNDO, password))
        assertTrue(StripTools.allowed(StripTool.SELECT_ALL, password))
        assertFalse(StripTools.allowed(StripTool.PROOFREAD, password))
        assertFalse(StripTools.allowed(StripTool.UNDO, plain.copy(learning = false)))
        val terminal = plain.copy(terminal = true)
        assertFalse(StripTools.allowed(StripTool.SELECT_ALL, terminal))
        assertFalse(StripTools.allowed(StripTool.CURSOR_END, terminal))
        assertTrue(StripTools.allowed(StripTool.HIDE, terminal))
    }

    @Test
    fun `nothing runs without a field, or while emoji search has the keys`() {
        StripTool.entries.forEach {
            assertFalse(it.name, StripTools.allowed(it, plain.copy(connected = false)))
            assertFalse(it.name, StripTools.allowed(it, plain.copy(emojiSearch = true)))
        }
    }

    @Test
    fun `every tool keeps its own id, and the row order names only real tools`() {
        assertEquals(StripTool.entries.size, StripTool.entries.map { it.id }.toSet().size)
        assertTrue(StripTool.entries.containsAll(StripTools.ROW_ORDER))
    }

    @Test
    fun `new keyboard views drop every panel built for the old ones`() {
        // A panel root kept across a rotation stays inside the old key area, so the new one shows an empty panel.
        val service = java.io.File("src/main/java/dev/localtype/keyboard/LocalTypeInputMethodService.kt").readText()
        val roots = Regex("""private var (\w+PanelRoot): View\? = null""").findAll(service).map { it.groupValues[1] }.toList()
        assertTrue(roots.containsAll(listOf("emojiPanelRoot", "savedPanelRoot", "toolsPanelRoot", "arrowsPanelRoot")))
        roots.forEach { assertTrue(it, Regex("""\n\s+$it = null\n""").containsMatchIn(service)) }
    }
}
