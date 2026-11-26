package dev.localtype.keyboard

import android.view.View
import dev.localtype.keyboard.KeyboardLayout.Mode
import dev.localtype.keyboard.KeyboardView.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalkBackRulesTest {
    @Test
    fun `a layer change says the keys now showing, and no change says nothing`() {
        assertEquals(TalkBackRules.Layer.SYMBOLS, TalkBackRules.layerShown(Mode.LETTERS, Mode.SYMBOLS))
        assertEquals(TalkBackRules.Layer.LETTERS, TalkBackRules.layerShown(Mode.SYMBOLS, Mode.LETTERS))
        assertEquals(TalkBackRules.Layer.MORE_SYMBOLS, TalkBackRules.layerShown(Mode.SYMBOLS, Mode.MORE_SYMBOLS))
        assertEquals(TalkBackRules.Layer.DIACRITICS, TalkBackRules.layerShown(Mode.SYMBOLS, Mode.MORE_SYMBOLS, diacritics = true))
        assertEquals(TalkBackRules.Layer.NUMBER_PAD, TalkBackRules.layerShown(Mode.SYMBOLS, Mode.NUMBER))
        assertEquals(TalkBackRules.Layer.PHONE_PAD, TalkBackRules.layerShown(Mode.LETTERS, Mode.PHONE))
        assertEquals(TalkBackRules.Layer.DATE_TIME_PAD, TalkBackRules.layerShown(Mode.SYMBOLS, Mode.DATETIME))
        for (mode in Mode.entries) assertNull(TalkBackRules.layerShown(mode, mode))
    }

    @Test
    fun `each layer has its own announcement`() {
        val ids = TalkBackRules.Layer.entries.map(TalkBackRules::announcement)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(R.string.layer_symbols, TalkBackRules.announcement(TalkBackRules.Layer.SYMBOLS))
    }

    @Test
    fun `the tools handle over a panel says it closes the panel, with no open or closed state`() {
        assertEquals(R.string.tools_handle_close_panel to null, TalkBackRules.toolsHandle(panelOpen = true, toolsOpen = false))
        assertEquals(R.string.tools_title to R.string.tools_closed, TalkBackRules.toolsHandle(panelOpen = false, toolsOpen = false))
        assertEquals(R.string.tools_title to R.string.tools_open, TalkBackRules.toolsHandle(panelOpen = false, toolsOpen = true))
    }

    @Test
    fun `an empty word slot is skipped`() {
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, TalkBackRules.slotImportance(false))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, TalkBackRules.slotImportance(true))
    }

    @Test
    fun `turning Shift or caps lock off is announced, turning it on is left to the key's new name`() {
        assertEquals("Shift, off", TypingRules.shiftAnnouncement(ShiftState.ON, ShiftState.OFF))
        assertEquals("Caps lock, off", TypingRules.shiftAnnouncement(ShiftState.LOCKED, ShiftState.OFF))
        assertNull(TypingRules.shiftAnnouncement(ShiftState.OFF, ShiftState.ON))
        assertNull(TypingRules.shiftAnnouncement(ShiftState.ON, ShiftState.LOCKED))
        assertNull(TypingRules.shiftAnnouncement(ShiftState.OFF, ShiftState.OFF))
    }

    @Test
    fun `delete previous word takes one word from a fresh read, and nothing without one`() {
        assertEquals("teh2".length, CursorGestures.wordDeletion("Tomorrow teh2", selected = false))
        assertEquals("world. ".length, CursorGestures.wordDeletion("hello world. ", selected = false))
        assertEquals(0, CursorGestures.wordDeletion(null, selected = false))
        assertEquals(0, CursorGestures.wordDeletion("hello", selected = true))
        assertEquals(0, CursorGestures.wordDeletion("", selected = false))
    }
}
