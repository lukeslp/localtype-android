package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewrites and the selection rules on the facade the iPhone keyboard calls. The prompts and the reading of a
 * reply are the shared Rewriting, so they must answer the same; the selection rules are a copy of the app's
 * ProofreadRequest, so both run on the same texts here.
 */
class TypingEngineRewritesTest {
    private val styles = mapOf("shorter" to RewriteStyle.SHORTER, "formal" to RewriteStyle.FORMAL, "friendly" to RewriteStyle.FRIENDLY)

    private val selections = listOf(
        "thjs is fine", "  thjs is fine  ", "\n thjs is fine\t\n", "doesnt", " doesnt ", "🙂 👋", "🙂", "a", "ab", "a b",
        "one\ntwo", "one  two", "1 2 3", "x 1", "", " ", "long ".repeat(120), "long ".repeat(121), "é à", "שלום עולם",
        "a".repeat(600), "a".repeat(601), "🙂".repeat(300) + "ab", "🙂".repeat(300) + "abc",
    )

    @Test
    fun `the styles are the three Android has, by name`() {
        assertEquals(listOf("shorter", "formal", "friendly"), TypingEngine.Rewrites.styles)
        assertEquals(RewriteStyle.entries.toSet(), styles.values.toSet())
        assertEquals(ProofreadRequest.LIMIT, TypingEngine.Rewrites.LIMIT)
    }

    @Test
    fun `a prompt is Rewriting's, and an unknown style has none`() {
        val text = "can u send it tmrw"
        for ((name, style) in styles) {
            assertEquals(Rewriting.prompt(style, text), TypingEngine.Rewrites.prompt(name, text))
            assertTrue(TypingEngine.Rewrites.prompt(name, text)!!.endsWith("\n\n$text"))
        }
        assertNull(TypingEngine.Rewrites.prompt("louder", text))
        assertNull(TypingEngine.Rewrites.prompt("SHORTER", text))
        assertNull(TypingEngine.Rewrites.clean(text, "Can you send it tomorrow?", "louder"))
    }

    @Test
    fun `a reply is read as Rewriting reads it`() {
        val cases = listOf(
            "can you send it tomorrow" to "Here is the rewritten text: \"Could you please send it tomorrow?\"",
            "can you send it tomorrow" to "Rewritten: Send it tomorrow?",
            "can you send it tomorrow" to "Sure! Here's a friendlier version: Hey, could you send it tomorrow? Thanks!",
            "send it tomorrow ok" to "Please send it tomorrow.\n\nI made it more polite.",
            "send it tomorrow" to "   ",
            "send it" to "I'm sorry, but I can't help with rewriting this particular message for you today.",
            "send it tomorrow please" to "Send it tomorrow please, thank you very much",
            "line one\nline two" to "First line.\nSecond line.",
            "\"send it\" she said" to "\"Please send it,\" she said.\"",
        )
        for ((original, reply) in cases) for ((name, style) in styles) {
            assertEquals("$name: $reply", Rewriting.clean(original, reply, style), TypingEngine.Rewrites.clean(original, reply, name))
        }
    }

    @Test
    fun `two lines in keep two lines out, and the original's own quotes stay`() {
        assertEquals("First line.\nSecond line.", TypingEngine.Rewrites.clean("line one\nline two", "First line.\nSecond line.", "formal"))
        assertEquals("First line.", TypingEngine.Rewrites.clean("line one line two", "First line.\nSecond line.", "shorter"))
        assertEquals("\"Please send it.\"", TypingEngine.Rewrites.clean("\"send it ok\"", "\"Please send it.\"", "formal"))
        assertEquals("Please send it.", TypingEngine.Rewrites.clean("send it ok", "\"Please send it.\"", "formal"))
    }

    @Test
    fun `the allowed sizes end where Rewriting's do`() {
        val original = "a".repeat(20)
        // Shorter: 0.15 to 1.2 times the original.
        assertNotNull(TypingEngine.Rewrites.clean(original, "b".repeat(24), "shorter"))
        assertNull(TypingEngine.Rewrites.clean(original, "b".repeat(25), "shorter"))
        assertNotNull(TypingEngine.Rewrites.clean(original, "b".repeat(3), "shorter"))
        assertNull(TypingEngine.Rewrites.clean(original, "b".repeat(2), "shorter"))
        // The other two: 0.5 to 2.5 times.
        for (name in listOf("formal", "friendly")) {
            assertNotNull(TypingEngine.Rewrites.clean(original, "b".repeat(10), name))
            assertNull(TypingEngine.Rewrites.clean(original, "b".repeat(9), name))
            assertNotNull(TypingEngine.Rewrites.clean(original, "b".repeat(50), name))
            assertNull(TypingEngine.Rewrites.clean(original, "b".repeat(51), name))
        }
    }

    @Test
    fun `a selection is offered where the Android app offers it`() {
        for (text in selections) assertEquals(text, ProofreadRequest.sentenceSelection(text), TypingEngine.Rewrites.offered(text))
        assertFalse(TypingEngine.Rewrites.offered(null))
        assertTrue(TypingEngine.Rewrites.offered("thjs is fine"))
        assertFalse(TypingEngine.Rewrites.offered("doesnt"))
        assertFalse(TypingEngine.Rewrites.offered("🙂 👋"))
        assertFalse(TypingEngine.Rewrites.offered("long ".repeat(121)))
        assertTrue(TypingEngine.Rewrites.offered("one\ntwo"))
    }

    @Test
    fun `a selection is split as the Android app captures it`() {
        for (text in selections) {
            // The app reads a selection through a window of the text around it; an empty selection is a caret there.
            val request = if (text.isEmpty()) null else VerifiedWordTransaction.window("before $text after", 0, 7, 7 + text.length, ProofreadRequest.LIMIT)
                ?.let { ProofreadRequest.capture(1, it) }
            val mine = TypingEngine.Rewrites.selected(text)
            if (text.isEmpty()) { assertNull(mine); continue }
            assertEquals(text, request == null, mine == null)
            if (request != null && mine != null) {
                assertTrue(request.selected)
                assertEquals(text, request.original, mine.body)
                assertEquals(text, request.leading, mine.leading)
                assertEquals(text, request.trailing, mine.trailing)
                assertEquals(text, mine.leading + mine.body + mine.trailing)
                assertEquals(request.replacement("X").text, mine.leading + "X" + mine.trailing)
            }
        }
        val parts = TypingEngine.Rewrites.selected("  thjs is fine  ")!!
        assertEquals(listOf("  ", "thjs is fine", "  "), listOf(parts.leading, parts.body, parts.trailing))
        assertNull(TypingEngine.Rewrites.selected("a".repeat(601)))
        assertNull(TypingEngine.Rewrites.selected("🙂"))
        // One word is not offered in the strip, but an explicit request takes it, as on Android.
        assertNotNull(TypingEngine.Rewrites.selected("doesnt"))
    }
}
