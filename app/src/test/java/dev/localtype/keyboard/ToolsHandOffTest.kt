package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsHandOffTest {
    private val plain = GestureRules.State(enabled = false)

    @Test
    fun `the word at the caret takes both sides and leaves spaces out`() {
        assertEquals("keyboard", ToolText.wordAt("a private keyb", "oard here"))
        assertEquals("keyboard", ToolText.wordAt("a private keyboard", " here"))
        assertEquals("here", ToolText.wordAt("a private keyboard ", "here"))
        assertEquals("", ToolText.wordAt("two  ", " spaces"))
        assertEquals("don't", ToolText.wordAt("I don'", "t know"))
        assertEquals("word", ToolText.wordAt("'word", "'"))
    }

    @Test
    fun `change case goes upper, lower, title, upper`() {
        val start = "Hello wide world"
        assertEquals(ToolText.Case.UPPER, ToolText.nextCase(start))
        val upper = ToolText.inCase(start, ToolText.Case.UPPER)
        assertEquals("HELLO WIDE WORLD", upper)
        assertEquals(ToolText.Case.LOWER, ToolText.nextCase(upper))
        val lower = ToolText.inCase(upper, ToolText.Case.LOWER)
        assertEquals(ToolText.Case.TITLE, ToolText.nextCase(lower))
        assertEquals("Hello Wide World", ToolText.inCase(lower, ToolText.Case.TITLE))
        assertEquals(ToolText.Case.UPPER, ToolText.nextCase("Hello Wide World"))
    }

    @Test
    fun `title case keeps apostrophes inside words and starts after punctuation`() {
        assertEquals("Don't Stop (Now)-Here", ToolText.inCase("don't stop (now)-here", ToolText.Case.TITLE))
        assertEquals("Ōlelo Māori", ToolText.inCase("ŌLELO MĀORI", ToolText.Case.TITLE))
        // Text with no letters has no case to change.
        assertEquals(ToolText.Case.TITLE, ToolText.nextCase("123 !"))
        assertEquals("123 !", ToolText.inCase("123 !", ToolText.Case.TITLE))
    }

    @Test
    fun `change case takes a leftwards selection start first, and only one that matches the selected text`() {
        assertEquals(4 to 9, ToolText.caseRange(4, 9, 5))
        // Made leftwards with the arrow pad: the editor reports the anchor first.
        assertEquals(4 to 9, ToolText.caseRange(9, 4, 5))
        assertEquals(null, ToolText.caseRange(9, 4, 4))
        assertEquals(null, ToolText.caseRange(4, 4, 0))
        assertEquals(null, ToolText.caseRange(-1, 4, 5))
        assertEquals(null, ToolText.caseRange(4, -1, 5))
    }

    @Test
    fun `count gives words and characters, line breaks left out`() {
        assertEquals(0 to 0, ToolText.count(""))
        assertEquals(0 to 3, ToolText.count("   "))
        assertEquals(3 to 13, ToolText.count("one two\nthree "))
        assertEquals(1 to 2, ToolText.count("👍🏽"))
    }

    @Test
    fun `count refuses more than its cap, a selection included`() {
        assertEquals(2 to 7, ToolText.countWithin("one two"))
        assertEquals(1 to ToolText.COUNT_CHARS, ToolText.countWithin("a".repeat(ToolText.COUNT_CHARS)))
        assertEquals(null, ToolText.countWithin("a".repeat(ToolText.COUNT_CHARS + 1)))
    }

    @Test
    fun `hand-offs stay out of password, incognito, private and terminal fields`() {
        listOf(StripTool.SEARCH_WEB, StripTool.SEND_TO_APP).forEach {
            assertTrue(it.name, StripTools.allowed(it, plain))
            assertFalse(it.name, StripTools.allowed(it, plain.copy(sensitive = true)))
            assertFalse(it.name, StripTools.allowed(it, plain.copy(learning = false)))
            assertFalse(it.name, StripTools.allowed(it, plain.copy(terminal = true)))
        }
    }

    @Test
    fun `case and count read text, so never in a password field, and dates go anywhere`() {
        listOf(StripTool.CHANGE_CASE, StripTool.COUNT).forEach {
            assertTrue(it.name, StripTools.allowed(it, plain))
            assertTrue(it.name, StripTools.allowed(it, plain.copy(learning = false)))
            assertFalse(it.name, StripTools.allowed(it, plain.copy(sensitive = true)))
        }
        listOf(StripTool.TODAY, StripTool.TIME_NOW).forEach {
            assertTrue(it.name, StripTools.allowed(it, plain.copy(sensitive = true)))
        }
    }

    @Test
    fun `the panel has a hand-off section with both`() {
        val sections = StripTools.panel(StripTools.Field(voice = true, proofread = true)).toMap()
        assertEquals(listOf(StripTool.SEARCH_WEB, StripTool.SEND_TO_APP), sections[StripTools.Group.HAND_OFF])
    }
}
