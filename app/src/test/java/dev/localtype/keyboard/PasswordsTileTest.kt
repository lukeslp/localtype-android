package dev.localtype.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Passwords & cards tile: when it shows, what its panel says, its dot, and what its button opens. */
class PasswordsTileTest {
    private val manager = "dev.localtype.fixture"

    private fun state(drawn: Int = 0, expected: Int = drawn, inlineOn: Boolean = true, touch: Boolean = false, manager: String? = this.manager) =
        PasswordsTile.state(drawn, expected, inlineOn, touch, manager)

    @Test
    fun `the tile needs Android 11, the first to hand a keyboard inline chips`() {
        assertFalse(PasswordsTile.available(26))
        assertFalse(PasswordsTile.available(29))
        assertTrue(PasswordsTile.available(30))
        assertTrue(PasswordsTile.available(35))
    }

    @Test
    fun `chips that are drawn are shown, whatever else is true`() {
        assertEquals(PasswordsTile.State.CHIPS, state(drawn = 2))
        assertEquals(PasswordsTile.State.CHIPS, state(drawn = 1, expected = 3))
        assertEquals(PasswordsTile.State.CHIPS, state(drawn = 1, touch = true, manager = null))
    }

    @Test
    fun `with nothing offered the panel says so plainly`() {
        assertEquals(PasswordsTile.State.NONE, state())
        // An answer still being drawn is not "nothing".
        assertEquals(PasswordsTile.State.WAITING, state(drawn = 0, expected = 2))
    }

    @Test
    fun `where the manager's own list replaces the chips, the panel says why`() {
        assertEquals(PasswordsTile.State.OFF, state(inlineOn = false))
        assertEquals(PasswordsTile.State.OFF, state(inlineOn = false, touch = true, manager = null))
        assertEquals(PasswordsTile.State.TALKBACK, state(touch = true))
        assertEquals(PasswordsTile.State.TALKBACK, state(touch = true, manager = null))
    }

    @Test
    fun `no autofill service chosen says that rather than that nothing was offered`() {
        assertEquals(PasswordsTile.State.NO_MANAGER, state(manager = null))
        assertEquals(PasswordsTile.State.NO_MANAGER, state(expected = 2, drawn = 0, manager = null))
    }

    @Test
    fun `the dot means chips are waiting`() {
        assertFalse(PasswordsTile.dot(0))
        assertTrue(PasswordsTile.dot(1))
        assertTrue(PasswordsTile.dot(6))
    }

    @Test
    fun `the manager is the package of the autofill setting's component`() {
        assertEquals("dev.localtype.fixture", PasswordsTile.managerPackage("dev.localtype.fixture/.FixtureAutofillService"))
        assertEquals("com.example.vault", PasswordsTile.managerPackage(" com.example.vault/com.example.vault.autofill.Service "))
        listOf(null, "", "null", "   ", "/x", "com.example.vault/", "vault/.Service", "1com.example/.S", "com..example/.S").forEach {
            assertNull(it, PasswordsTile.managerPackage(it))
        }
    }

    @Test
    fun `the button opens the manager's app, or Android's autofill choice where there is none`() {
        assertEquals(PasswordsTile.Opens.MANAGER_APP, PasswordsTile.opens(manager, launchable = true))
        assertEquals(PasswordsTile.Opens.AUTOFILL_SETTINGS, PasswordsTile.opens(manager, launchable = false))
        assertEquals(PasswordsTile.Opens.AUTOFILL_SETTINGS, PasswordsTile.opens(null, launchable = false))
        assertEquals(PasswordsTile.Opens.AUTOFILL_SETTINGS, PasswordsTile.opens(null, launchable = true))
    }

    /**
     * Android lets each suggestion be inflated once, so the panel holds the strip's own chip views and never a second
     * copy; and the keyboard never asks for a fill. Only InlineAutofill handles suggestions.
     */
    @Test
    fun `chips are inflated in one place, once, and nothing asks for a fill`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }
        val inflating = sources.filterValues { Regex("""\bsuggestion\.inflate\(""").containsMatchIn(it) }.keys
        assertEquals(setOf("InlineAutofill.kt"), inflating)
        assertEquals(1, Regex("""\.inflate\(context, size""").findAll(sources.getValue("InlineAutofill.kt")).count())
        val asking = sources.filterValues { Regex("""\brequestAutofill\(|\bAutofillManager\b""").containsMatchIn(it) }.keys
        assertEquals(emptySet<String>(), asking)
    }
}
