package dev.localtype.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice key on the keys: where it goes, what it takes, and where it never goes. */
class VoiceKeyTest {
    private fun bottom(layout: KeyboardLayout) = layout.rows.last()
    private fun width(row: List<Key>) = row.sumOf { it.width.toDouble() }.toFloat()

    @Test
    fun `the voice key sits right before Return and the space bar gives up its width`() {
        val plain = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false)
        val voiced = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, voiceKey = true)
        val row = bottom(voiced)
        val enter = row.indexOfFirst { it.action == KeyAction.Enter }
        assertEquals(KeyAction.Voice, row[enter - 1].action)
        assertEquals("Voice input", row[enter - 1].spokenLabel)
        val spaceBefore = bottom(plain).first { it.action == KeyAction.Space }.width
        val spaceAfter = row.first { it.action == KeyAction.Space }.width
        assertEquals(spaceBefore - KeyboardLayout.VOICE_KEY_WIDTH, spaceAfter, 0.001f)
        assertEquals(KeyboardLayout.ROW_UNITS, width(row), 0.001f)
        // The letter rows don't move, so key geometry and correction are unchanged.
        assertEquals(plain.rows.dropLast(1), voiced.rows.dropLast(1))
    }

    @Test
    fun `both symbol pages carry it, and every space bar width still fills the row`() {
        for (mode in listOf(KeyboardLayout.Mode.LETTERS, KeyboardLayout.Mode.SYMBOLS, KeyboardLayout.Mode.MORE_SYMBOLS)) {
            for (bar in SpaceBar.entries) {
                for (type in listOf(InputType.TYPE_CLASS_TEXT, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)) {
                    val row = bottom(KeyboardLayout.build(mode, showSwitchKey = true, inputType = type, spaceBar = bar, voiceKey = true))
                    assertTrue("$mode $bar", row.any { it.action == KeyAction.Voice })
                    assertEquals("$mode $bar", KeyboardLayout.ROW_UNITS, width(row), 0.001f)
                    assertTrue("$mode $bar space", row.first { it.action == KeyAction.Space }.width >= 1.5f)
                }
            }
        }
    }

    @Test
    fun `the number and phone pads never get one`() {
        for (mode in listOf(KeyboardLayout.Mode.NUMBER, KeyboardLayout.Mode.PHONE)) {
            val layout = KeyboardLayout.build(mode, showSwitchKey = false, inputType = InputType.TYPE_CLASS_NUMBER, voiceKey = true)
            assertFalse(layout.rows.flatten().any { it.action == KeyAction.Voice })
        }
    }

    @Test
    fun `an unknown or missing saved placement is off`() {
        assertEquals(VoiceKey.OFF, VoiceKey.of(null))
        assertEquals(VoiceKey.OFF, VoiceKey.of("SOMEWHERE"))
        assertEquals(VoiceKey.KEYS, VoiceKey.of("KEYS"))
    }

    @Test
    fun `the voice key is not a key that edits text`() {
        assertFalse(TypingRules.editsText(KeyAction.Voice))
        assertEquals(null, EmojiSearch.edit("cat", KeyAction.Voice))
    }
}
