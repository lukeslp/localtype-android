package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewritingTest {
    private val plain = GestureRules.State(enabled = false)

    @Test
    fun `each style asks for its change and ends with the text`() {
        val text = "can u send it tmrw"
        RewriteStyle.entries.forEach { style ->
            val prompt = Rewriting.prompt(style, text)
            assertTrue(prompt.endsWith("\n\n$text"))
            assertTrue("Reply with only the rewritten text" in prompt)
        }
        assertTrue("shorter" in Rewriting.prompt(RewriteStyle.SHORTER, text))
        assertTrue("formal" in Rewriting.prompt(RewriteStyle.FORMAL, text))
        assertTrue("friendlier" in Rewriting.prompt(RewriteStyle.FRIENDLY, text))
    }

    @Test
    fun `a lead-in and added quotes come off`() {
        val original = "can you send it tomorrow"
        assertEquals("Could you please send it tomorrow?",
            Rewriting.clean(original, "Here is the rewritten text: \"Could you please send it tomorrow?\"", RewriteStyle.FORMAL))
        assertEquals("Send it tomorrow?", Rewriting.clean(original, "Rewritten: Send it tomorrow?", RewriteStyle.SHORTER))
        assertEquals("Hey, could you send it tomorrow? Thanks!",
            Rewriting.clean(original, "Sure! Here's a friendlier version: Hey, could you send it tomorrow? Thanks!", RewriteStyle.FRIENDLY))
    }

    @Test
    fun `an explanation after a one-line rewrite is dropped`() {
        assertEquals("Please send it tomorrow.",
            Rewriting.clean("send it tomorrow ok", "Please send it tomorrow.\n\nI made it more polite.", RewriteStyle.FORMAL))
    }

    @Test
    fun `empty or implausibly sized answers are refused`() {
        assertNull(Rewriting.clean("send it tomorrow", "   ", RewriteStyle.FORMAL))
        assertNull(Rewriting.clean("send it", "I'm sorry, but I can't help with rewriting this particular message for you today.", RewriteStyle.FORMAL))
        assertNull(Rewriting.clean("send it tomorrow please", "Send it tomorrow please, thank you very much", RewriteStyle.SHORTER))
    }

    @Test
    fun `rewrite tools go where proofreading goes`() {
        listOf(StripTool.REWRITE_SHORTER, StripTool.REWRITE_FORMAL, StripTool.REWRITE_FRIENDLY).forEach {
            assertTrue(StripTools.allowed(it, plain))
            assertEquals(StripTools.allowed(StripTool.PROOFREAD, plain.copy(sensitive = true)), StripTools.allowed(it, plain.copy(sensitive = true)))
            assertEquals(StripTools.allowed(StripTool.PROOFREAD, plain.copy(terminal = true)), StripTools.allowed(it, plain.copy(terminal = true)))
            assertEquals(false, StripTools.offered(it, StripTools.Field(voice = true, proofread = false)))
        }
    }
}
