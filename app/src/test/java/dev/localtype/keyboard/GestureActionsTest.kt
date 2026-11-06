package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The swipe-to-action mapping and where each action may run. */
class GestureActionsTest {
    private val on = GestureRules.State(enabled = true)

    @Test
    fun `the defaults are delete word, undo, caps lock and hide`() {
        assertEquals(GestureAction.DELETE_WORD, GestureRules.action(null, TwoFingerSwipe.LEFT))
        assertEquals(GestureAction.UNDO, GestureRules.action(null, TwoFingerSwipe.RIGHT))
        assertEquals(GestureAction.SAVED_TEXT, GestureRules.action(null, TwoFingerSwipe.UP))
        assertEquals(GestureAction.HIDE_KEYBOARD, GestureRules.action(null, TwoFingerSwipe.DOWN))
    }

    @Test
    fun `a saved choice wins, and a value this build does not know falls back to the default`() {
        assertEquals(GestureAction.PASTE, GestureRules.action("paste", TwoFingerSwipe.LEFT))
        assertEquals(GestureAction.NONE, GestureRules.action("none", TwoFingerSwipe.LEFT))
        assertEquals(GestureAction.DELETE_WORD, GestureRules.action("teleport", TwoFingerSwipe.LEFT))
        assertEquals(GestureAction.DELETE_WORD, GestureRules.action("", TwoFingerSwipe.LEFT))
    }

    @Test
    fun `every action saves and reads back under its own id`() {
        assertEquals(GestureAction.entries.size, GestureAction.entries.map { it.id }.toSet().size)
        GestureAction.entries.forEach { assertEquals(it, GestureAction.of(it.id)) }
        assertEquals(TwoFingerSwipe.entries.size, TwoFingerSwipe.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `swipes are watched only with the keys showing and nothing else owning the touches`() {
        assertTrue(GestureRules.watches(on))
        assertFalse("off in settings", GestureRules.watches(GestureRules.State(enabled = false)))
        assertFalse("TalkBack's own two-finger swipes", GestureRules.watches(on.copy(touchExploration = true)))
        assertFalse(GestureRules.watches(on.copy(panel = true)))
        assertFalse(GestureRules.watches(on.copy(emojiSearch = true)))
        assertFalse(GestureRules.watches(on.copy(proofreading = true)))
        assertFalse(GestureRules.watches(on.copy(shown = false)))
        assertFalse(GestureRules.watches(on.copy(connected = false)))
        // A password field still watches: hiding the keyboard or caps lock read nothing.
        assertTrue(GestureRules.watches(on.copy(sensitive = true, learning = false)))
    }

    @Test
    fun `actions that read text are off in password, incognito and terminal fields`() {
        val reading = listOf(GestureAction.DELETE_WORD, GestureAction.UNDO, GestureAction.PASTE)
        assertEquals(reading.toSet(), GestureAction.entries.filter(GestureRules::readsText).toSet())
        for (action in reading) {
            assertTrue(action.name, GestureRules.allowed(action, on))
            assertFalse(action.name, GestureRules.allowed(action, on.copy(sensitive = true)))
            assertFalse(action.name, GestureRules.allowed(action, on.copy(learning = false)))
            assertFalse(action.name, GestureRules.allowed(action, on.copy(terminal = true)))
        }
        val password = on.copy(sensitive = true, learning = false)
        for (action in listOf(GestureAction.CURSOR_START, GestureAction.CURSOR_END, GestureAction.SELECT_ALL,
                GestureAction.HIDE_KEYBOARD, GestureAction.EMOJI, GestureAction.SAVED_TEXT, GestureAction.SYMBOLS, GestureAction.CAPS_LOCK))
            assertTrue(action.name, GestureRules.allowed(action, password))
    }

    @Test
    fun `caret and selection need a text field, caps lock needs letters, nothing is never run`() {
        val terminal = on.copy(terminal = true)
        assertFalse(GestureRules.allowed(GestureAction.CURSOR_START, terminal))
        assertFalse(GestureRules.allowed(GestureAction.CURSOR_END, terminal))
        assertFalse(GestureRules.allowed(GestureAction.SELECT_ALL, terminal))
        assertTrue(GestureRules.allowed(GestureAction.HIDE_KEYBOARD, terminal))
        assertFalse(GestureRules.allowed(GestureAction.CAPS_LOCK, on.copy(letters = false)))
        assertTrue(GestureRules.allowed(GestureAction.SYMBOLS, on.copy(letters = false)))
        assertFalse(GestureRules.allowed(GestureAction.NONE, on))
    }

    @Test
    fun `a panel, emoji search or proofreading stops every action, TalkBack does not`() {
        for (action in GestureAction.entries) {
            assertFalse(GestureRules.allowed(action, on.copy(panel = true)))
            assertFalse(GestureRules.allowed(action, on.copy(emojiSearch = true)))
            assertFalse(GestureRules.allowed(action, on.copy(proofreading = true)))
            assertFalse(GestureRules.allowed(action, on.copy(enabled = false)))
            assertEquals(action.name, GestureRules.allowed(action, on), GestureRules.allowed(action, on.copy(touchExploration = true)))
        }
    }

    @Test
    fun `TalkBack is offered each allowed action once, in swipe order`() {
        val actions = mapOf(
            TwoFingerSwipe.LEFT to GestureAction.DELETE_WORD, TwoFingerSwipe.RIGHT to GestureAction.NONE,
            TwoFingerSwipe.UP to GestureAction.DELETE_WORD, TwoFingerSwipe.DOWN to GestureAction.HIDE_KEYBOARD,
        )
        assertEquals(listOf(TwoFingerSwipe.LEFT to GestureAction.DELETE_WORD, TwoFingerSwipe.DOWN to GestureAction.HIDE_KEYBOARD),
            GestureRules.offered(actions, on.copy(touchExploration = true)))
        assertEquals(listOf(TwoFingerSwipe.DOWN to GestureAction.HIDE_KEYBOARD),
            GestureRules.offered(actions, on.copy(sensitive = true)))
        assertEquals(emptyList<Pair<TwoFingerSwipe, GestureAction>>(), GestureRules.offered(actions, GestureRules.State(enabled = false)))
    }
}
