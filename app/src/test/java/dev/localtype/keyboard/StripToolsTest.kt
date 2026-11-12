package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tools the strip's row shows, how many fit, and where each may run. */
class StripToolsTest {
    @Test fun characterToolsAreDiscoverableUnpinnedAndEffectsStayOutOfPrivateFields() {
        val field = StripTools.Field(voice = true, proofread = true)
        for (tool in listOf(StripTool.SYMBOLS, StripTool.TEXT_EFFECTS)) {
            assertTrue(tool in StripTools.panel(field).flatMap { it.second })
            assertFalse(tool in StripTools.ROW_ORDER)
        }
        val state = GestureRules.State(enabled = false)
        assertTrue(StripTools.allowed(StripTool.TEXT_EFFECTS, state))
        assertFalse(StripTools.allowed(StripTool.TEXT_EFFECTS, state.copy(sensitive = true)))
        assertFalse(StripTools.allowed(StripTool.TEXT_EFFECTS, state.copy(learning = false)))
        assertFalse(StripTools.allowed(StripTool.TEXT_EFFECTS, state.copy(terminal = true)))
    }
    private val everything = StripTools.Field(voice = true, proofread = true, passwords = true)
    /** Android 10 and older: no inline chips, so no Passwords & cards. */
    private val beforeAndroid11 = StripTools.Field(voice = true, proofread = true, passwords = false)
    private val plain = GestureRules.State(enabled = false)

    @Test
    fun `with the lead button hidden a 360 dp strip fits four tools, settings and More, a 411 dp Pixel five`() {
        assertEquals(6, StripTools.cells(360))
        assertEquals(listOf(StripTool.CLIPBOARD, StripTool.PASSWORDS, StripTool.EMOJI, StripTool.VOICE, StripTool.SETTINGS), StripTools.row(360, everything))
        assertEquals(7, StripTools.cells(411))
        assertEquals(
            listOf(StripTool.CLIPBOARD, StripTool.PASSWORDS, StripTool.EMOJI, StripTool.VOICE, StripTool.UNDO, StripTool.SETTINGS),
            StripTools.row(411, everything),
        )
    }

    @Test
    fun `Keyboard settings always ends the row, once, whatever the saved order holds`() {
        for (width in listOf(150, 320, 360, 411, 600, 900)) {
            for (order in listOf(StripTools.ROW_ORDER, emptyList(), listOf(StripTool.SETTINGS, StripTool.UNDO), StripTool.entries.take(8))) {
                val row = StripTools.row(width, everything, order)
                assertEquals(StripTool.SETTINGS, row.last())
                assertEquals(1, row.count { it == StripTool.SETTINGS })
                assertTrue(row.size <= StripTools.cells(width) - 1)
            }
        }
        // It can be opened in every field, a password box included.
        assertTrue(StripTools.allowed(StripTool.SETTINGS, plain.copy(sensitive = true)))
    }

    @Test
    fun `the built-in row begins Clipboard, Passwords and cards, Emoji, Voice, Undo, Proofread`() {
        assertEquals(
            listOf(StripTool.CLIPBOARD, StripTool.PASSWORDS, StripTool.EMOJI, StripTool.VOICE, StripTool.UNDO, StripTool.PROOFREAD),
            StripTools.ROW_ORDER.take(6),
        )
    }

    @Test
    fun `before Android 11 there is no Passwords and cards tile, and the row is as it was`() {
        assertEquals(listOf(StripTool.CLIPBOARD, StripTool.EMOJI, StripTool.VOICE, StripTool.UNDO, StripTool.SETTINGS), StripTools.row(360, beforeAndroid11))
        assertEquals(StripTool.PROOFREAD, StripTools.row(411, beforeAndroid11).dropLast(1).last())
        assertFalse(StripTools.offered(StripTool.PASSWORDS, beforeAndroid11))
        assertFalse(StripTools.panel(beforeAndroid11).flatMap { it.second }.contains(StripTool.PASSWORDS))
        // Pinned in a saved row, it still stays out where chips can't come.
        assertEquals(listOf(StripTool.UNDO, StripTool.SETTINGS), StripTools.row(411, beforeAndroid11, listOf(StripTool.PASSWORDS, StripTool.UNDO)))
    }

    @Test
    fun `a row someone saved is kept as it was, without the new tile`() {
        val saved = StripTools.rowOrder(listOf("clipboard", "emoji", "undo"))
        assertEquals(listOf(StripTool.CLIPBOARD, StripTool.EMOJI, StripTool.UNDO), saved)
        assertEquals(saved + StripTool.SETTINGS, StripTools.row(411, everything, saved))
    }

    @Test
    fun `Passwords and cards sits in Insert and runs wherever the chips can, password fields above all`() {
        assertTrue(StripTool.PASSWORDS in StripTools.panel(everything).toMap().getValue(StripTools.Group.INSERT))
        assertTrue(StripTools.allowed(StripTool.PASSWORDS, plain))
        assertTrue(StripTools.allowed(StripTool.PASSWORDS, plain.copy(sensitive = true)))
        assertTrue(StripTools.allowed(StripTool.PASSWORDS, plain.copy(learning = false)))
        assertTrue(StripTools.allowed(StripTool.PASSWORDS, plain.copy(terminal = true)))
        assertFalse(StripTools.allowed(StripTool.PASSWORDS, plain.copy(connected = false)))
    }

    @Test
    fun `a tool the field does not offer leaves its place to the next one`() {
        val noVoice = StripTools.row(360, StripTools.Field(voice = false, proofread = true))
        assertEquals(listOf(StripTool.CLIPBOARD, StripTool.EMOJI, StripTool.UNDO, StripTool.PROOFREAD, StripTool.SETTINGS), noVoice)
    }

    @Test
    fun `a very narrow strip still has settings and More`() {
        assertEquals(2, StripTools.cells(150))
        assertEquals(listOf(StripTool.SETTINGS), StripTools.row(150, everything))
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
