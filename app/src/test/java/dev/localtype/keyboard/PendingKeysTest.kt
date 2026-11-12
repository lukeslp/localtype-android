package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingKeysTest {
    private val editor = "app:7:body:131073:16777220"
    private val other = "app:9:subject:1:6"

    private fun drain(keys: PendingKeys<String>): List<String> = generateSequence { keys.next() }.toList()

    @Test
    fun `keys held for a restarting editor replay in the order typed`() {
        val keys = PendingKeys<String>()
        listOf("p", "a", " ").forEachIndexed { i, key -> assertEquals(PendingKeys.Hold.HELD, keys.hold(key, editor, 100L + i)) }
        assertEquals(PendingKeys.Start.REPLAY, keys.started(editor, restarting = true))
        assertEquals(listOf("p", "a", " "), drain(keys))
        assertTrue(keys.isEmpty)
    }

    @Test
    fun `a field with no stored id still replays when its identity matches`() {
        val noId = "app:-1::1:6"
        val keys = PendingKeys<String>()
        keys.hold("q", noId, 0)
        assertEquals(PendingKeys.Start.REPLAY, keys.started(noId, restarting = true))
    }

    @Test
    fun `another editor or a fresh session drops held keys instead of typing them there`() {
        val keys = PendingKeys<String>()
        keys.hold("x", editor, 0)
        assertEquals(PendingKeys.Start.DROPPED, keys.started(other, restarting = true))
        assertTrue(keys.isEmpty)

        keys.hold("y", editor, 0)
        assertEquals(PendingKeys.Start.DROPPED, keys.started(editor, restarting = false))
        assertTrue(keys.isEmpty)
        assertEquals(PendingKeys.Start.NONE, keys.started(editor, restarting = true))
    }

    @Test
    fun `with no restart the wait runs from the last progress, then the keys go out once`() {
        val keys = PendingKeys<String>(holdMs = 300)
        keys.hold("a", editor, 1_000)
        keys.hold("b", editor, 1_250)
        assertEquals(PendingKeys.Expiry.WAIT, keys.expire(editor, 1_299))
        assertEquals(1L, keys.remainingMs(1_299))
        keys.progressed(1_280)
        assertEquals(PendingKeys.Expiry.WAIT, keys.expire(editor, 1_500))
        assertEquals(PendingKeys.Expiry.SEND, keys.expire(editor, 1_580))
        assertEquals(listOf("a", "b"), drain(keys))
        assertEquals(PendingKeys.Expiry.NONE, keys.expire(editor, 9_999))
    }

    @Test
    fun `an editor whose reads are null while live is not probed again until a fresh session`() {
        val keys = PendingKeys<String>(holdMs = 300)
        assertTrue(keys.probes(editor))
        keys.hold("a", editor, 0)
        assertEquals(PendingKeys.Expiry.SEND, keys.expire(editor, 300))
        drain(keys)
        assertFalse(keys.probes(editor))
        assertTrue(keys.probes(other))
        keys.started(editor, restarting = true)
        assertFalse(keys.probes(editor))
        keys.started(editor, restarting = false)
        assertTrue(keys.probes(editor))
    }

    @Test
    fun `an expiry after another editor took over drops the keys`() {
        val keys = PendingKeys<String>(holdMs = 300)
        keys.hold("a", editor, 0)
        assertEquals(PendingKeys.Expiry.DROP, keys.expire(other, 400))
        assertTrue(keys.isEmpty)
        assertTrue(keys.probes(editor))
    }

    @Test
    fun `a key that met a retired connection goes back in front of later keys`() {
        val keys = PendingKeys<String>()
        keys.hold("l", editor, 0)
        keys.holdFirst("a", editor, 0)
        assertEquals(PendingKeys.Start.REPLAY, keys.started(editor, restarting = true))
        assertEquals(listOf("a", "l"), drain(keys))
        keys.holdFirst("z", editor, 5)
        assertEquals(PendingKeys.Start.REPLAY, keys.started(editor, restarting = true))
    }

    @Test
    fun `a full queue refuses more keys without losing the held ones`() {
        val keys = PendingKeys<Int>(capacity = 3)
        repeat(3) { assertEquals(PendingKeys.Hold.HELD, keys.hold(it, editor, 0)) }
        assertEquals(PendingKeys.Hold.FULL, keys.hold(3, editor, 0))
        assertEquals(3, keys.size)
        assertEquals(0, keys.next())
        assertEquals(3, keys.clear() + 1)
        assertNull(keys.next())
    }

    @Test
    fun `text keys are held, layout keys are not`() {
        assertTrue(TypingRules.editsText(KeyAction.Text("a")))
        assertTrue(TypingRules.editsText(KeyAction.Space))
        assertTrue(TypingRules.editsText(KeyAction.Backspace))
        assertTrue(TypingRules.editsText(KeyAction.Enter))
        for (action in listOf(KeyAction.Shift, KeyAction.ToSymbols, KeyAction.ToMoreSymbols, KeyAction.ToLetters,
            KeyAction.ToFieldLayout, KeyAction.SwitchKeyboard)) assertFalse(TypingRules.editsText(action))
    }

    @Test
    fun `a restart of the same editor keeps a pressed key whatever its field id`() {
        assertTrue(TypingRules.sameEditorRestart(restarting = true, identity = "app:-1::1:6", previous = "app:-1::1:6"))
        assertTrue(TypingRules.sameEditorRestart(restarting = true, identity = editor, previous = editor))
        assertFalse(TypingRules.sameEditorRestart(restarting = true, identity = other, previous = editor))
        assertFalse(TypingRules.sameEditorRestart(restarting = false, identity = editor, previous = editor))
        assertFalse(TypingRules.sameEditorRestart(restarting = true, identity = editor, previous = null))
    }

    @Test
    fun `Private for now lasts through a restart and a hide in its own field and ends in any other`() {
        // The field it was turned on in: restarting, or shown again after the keyboard was only hidden.
        assertTrue(TypingRules.staysPrivate(restarting = true, identity = editor, privateField = editor))
        assertTrue(TypingRules.staysPrivate(restarting = false, identity = editor, privateField = editor))
        // Another field takes the keyboard.
        assertFalse(TypingRules.staysPrivate(restarting = false, identity = other, privateField = editor))
        assertFalse(TypingRules.staysPrivate(restarting = false, identity = editor, privateField = null))
        // Android calls a start a restart only for the editor already open, so that one keeps it whatever it reports.
        assertTrue(TypingRules.staysPrivate(restarting = true, identity = other, privateField = editor))
    }
}
