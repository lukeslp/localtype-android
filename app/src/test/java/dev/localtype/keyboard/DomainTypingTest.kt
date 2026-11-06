package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class DomainTypingTest {
    private fun separator(before: String, after: String = "", value: String = " ") =
        WordEdits.separator(before, after, false, value, false, true, true, 280) { error("a domain must not ask for correction") }

    @Test fun `dotted names and emails remain literal before all word boundaries`() {
        for (word in listOf("netflx.com", "gmail.com", "zorpquill.dev", "mysite.co.uk", "my-site.xyz", "https://netflx.com", "name@netflx.com", "xn--example.test")) {
            for (delimiter in listOf(" ", "!", "?", "\n", "")) {
                val edit = separator(word, value = delimiter)
                assertEquals(word + delimiter, word.dropLast(edit.replacement.before) + edit.replacement.text)
                assertFalse(edit.corrected)
                assertFalse(edit.learningEligible)
            }
        }
    }

    @Test fun `handles hashtags and identifier-like tokens remain literal before all word boundaries`() {
        for (word in listOf("@lukesteubr", "(@thier", "#100days", "snake_csae", "_privte", "h264x", "v2beta", "3rd", "5pm", "folder/thier", "C:\\thier", "/thier", "~/thier", "#data.poems", "iPhne2")) {
            for (delimiter in listOf(" ", "!", "?", "\n", "")) {
                val edit = separator(word, value = delimiter)
                assertEquals(word + delimiter, word.dropLast(edit.replacement.before) + edit.replacement.text)
                assertFalse(word, edit.corrected)
                assertFalse(word, edit.learningEligible)
            }
        }
        // A camel-case word or #tag of letters only is never corrected, but may be learned with its capitals.
        for (word in listOf("#datapoems", "camelCsae", "iPhne", "macOS", "LocalType", "#BlackLivesMatter", "(McKenzie")) {
            for (delimiter in listOf(" ", "!", "?", "\n")) {
                val edit = separator(word, value = delimiter)
                assertEquals(word + delimiter, word.dropLast(edit.replacement.before) + edit.replacement.text)
                assertFalse(word, edit.corrected)
                assertTrue(word, edit.learningEligible)
                assertTrue(word, edit.casedToken)
            }
        }
        assertFalse(WordEdits.separator("hello", "", false, " ", false, true, true, 280) { null }.casedToken)
        // The cursor may sit inside one: what follows it counts too.
        assertTrue(DomainTyping.protected("@luk", "esteuber"))
        assertTrue(DomainTyping.protected("snake", "_case"))
        assertTrue(DomainTyping.protected("#da", "tapoems"))
        assertTrue(DomainTyping.protected("folder", "/thier"))
        assertTrue(DomainTyping.protected("folder", "\\thier"))
    }

    @Test fun `prose beside marks numbers and capitals is still ordinary text`() {
        for (before in listOf("email me @ hme", "C# is grat", "100 thigns", "its 5 oclok", "re-entr", "THe", "Im", "Thier", "a # b thier", "50% of thm", "don't")) {
            assertFalse(before, DomainTyping.protected(before, ""))
        }
        // The handle is its own token; the word after it is prose again.
        assertFalse(DomainTyping.protected("@luke thier", ""))
        assertFalse(DomainTyping.protected("#tag thier", ""))
    }

    @Test fun `editing before an existing tld is protected without suppressing separate prose`() {
        assertFalse(separator("say netflx", ".com").learningEligible)
        assertFalse(separator("netfl", "x.com").learningEligible)
        assertTrue(DomainTyping.protected("use example.", "com"))
        assertFalse(DomainTyping.protected("example.com hello", ""))
        assertFalse(DomainTyping.protected("say hello", ". next"))
        assertFalse(DomainTyping.protected("hello", ""))
    }

    @Test fun `a completion space becomes a literal dot or tld without an inserted gap`() {
        assertEquals(WordEdits.Replacement(1, 0, "."), WordEdits.attachPunctuation("gmail ", ".", true, false))
        for (tld in listOf(".com", ".org", ".dev")) {
            assertEquals(WordEdits.Replacement(1, 0, tld), WordEdits.attachPunctuation("gmail ", tld, true, false))
            assertNull(WordEdits.attachPunctuation("gmail ", tld, false, false))
            assertNull(WordEdits.attachPunctuation("gmail ", tld, true, true))
        }
    }

    @Test fun `deferred sentence correction requires exact field text selection and anchors`() {
        val snapshot = VerifiedWordTransaction.Snapshot("say teh.", " following", EditorEditState.Selection(8, 8))
        val pending = DomainTyping.Pending(1, snapshot, "teh", "the", "say ", true)
        val edit = requireNotNull(pending.edit(1, snapshot, " "))
        assertEquals(WordEdits.Replacement(4, 0, "the. "), edit.replacement)
        assertEquals("say the", edit.beforeSeparator)
        assertTrue(edit.corrected)
        val unchanged = requireNotNull(pending.copy(original = "teh", corrected = "teh").edit(1, snapshot, " "))
        assertEquals(WordEdits.Replacement(0, 0, " "), unchanged.replacement)
        assertFalse(unchanged.corrected)
        assertTrue(unchanged.learningEligible)
        assertNull(pending.edit(2, snapshot, " "))
        assertNull(pending.edit(1, snapshot.copy(before = "say teh.c"), " "))
        assertNull(pending.edit(1, snapshot.copy(after = " changed"), " "))
        assertNull(pending.edit(1, snapshot.copy(selection = EditorEditState.Selection(7, 7)), " "))
        assertNull(pending.edit(1, null, " "))
        assertNull(pending.edit(1, snapshot, "com"))
    }
}
