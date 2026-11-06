package dev.localtype.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Read aloud's sentence and voice, the arrow pad's keys, and where the newer tools may run. */
class ToolsStepTwoTest {
    private val plain = GestureRules.State(enabled = false)

    @Test
    fun `read aloud takes the sentence before the caret`() {
        assertEquals("Two three.", ToolText.lastSentence("One. Two three."))
        assertEquals("and this", ToolText.lastSentence("First line\nand this"))
        assertEquals("Still going", ToolText.lastSentence("Still going"))
        assertEquals("Yes", ToolText.lastSentence("Ok. Is it? Yes"))
        assertEquals("Done.", ToolText.lastSentence("Start. Done.  "))
        assertEquals("", ToolText.lastSentence("   "))
    }

    private fun voice(name: String, tag: String, offline: Boolean = true, installed: Boolean = true, quality: Int = 300) =
        ReadAloud.VoiceOption(name, Locale.forLanguageTag(tag), offline, installed, quality)

    @Test
    fun `read aloud speaks only through an installed voice that needs no network`() {
        val us = Locale.forLanguageTag("en-US")
        assertEquals(null, ReadAloud.choose(listOf(voice("net", "en-US", offline = false)), listOf(us)))
        assertEquals(null, ReadAloud.choose(listOf(voice("later", "en-US", installed = false)), listOf(us)))
        assertEquals(null, ReadAloud.choose(emptyList(), listOf(us)))
        val local = voice("local", "en-US")
        assertEquals(local, ReadAloud.choose(listOf(voice("net", "en-US", offline = false, quality = 500), local), listOf(us)))
    }

    @Test
    fun `read aloud picks the text's language first, then falls back`() {
        val spanish = voice("es-local", "es-ES")
        val english = voice("en-local", "en-US")
        val voices = listOf(english, spanish)
        assertEquals(spanish, ReadAloud.choose(voices, listOf(Locale.forLanguageTag("es-MX"), Locale.forLanguageTag("en-US"))))
        // No offline Portuguese voice: the default language's.
        assertEquals(english, ReadAloud.choose(voices, listOf(Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("en-US"))))
        assertEquals(null, ReadAloud.choose(voices, listOf(Locale.forLanguageTag("pt-BR"))))
        // An engine that names the language by its three-letter code still matches.
        val eng = ReadAloud.VoiceOption("eng", Locale("eng", "USA"), offline = true, installed = true)
        assertEquals(eng, ReadAloud.choose(listOf(eng), listOf(Locale.forLanguageTag("en-US"))))
    }

    @Test
    fun `within a language the engine's default comes first, then the country, then quality`() {
        val gb = voice("gb", "en-GB", quality = 400)
        val us = voice("us", "en-US", quality = 300)
        val usBetter = voice("us-better", "en-US", quality = 400)
        val wanted = listOf(Locale.forLanguageTag("en-US"))
        assertEquals(gb, ReadAloud.choose(listOf(us, gb, usBetter), wanted, defaultName = "gb"))
        assertEquals(usBetter, ReadAloud.choose(listOf(us, gb, usBetter), wanted))
        // A default that needs the network is passed over.
        assertEquals(usBetter, ReadAloud.choose(listOf(us, usBetter, voice("cloud", "en-US", offline = false)), wanted, defaultName = "cloud"))
    }

    @Test
    fun `read aloud refuses text longer than the engine takes`() {
        assertTrue(ReadAloud.fits("a".repeat(4_000), 4_000))
        assertFalse(ReadAloud.fits("a".repeat(4_001), 4_000))
    }

    @Test
    fun `the arrow pad is three by three with Select in the middle`() {
        assertEquals(9, ArrowPad.GRID.size)
        assertEquals(ArrowMove.SELECT, ArrowPad.GRID[4])
        assertEquals(ArrowMove.UP, ArrowPad.GRID[1])
        assertEquals(ArrowMove.DOWN, ArrowPad.GRID[7])
    }

    @Test
    fun `word moves and the field's ends add Ctrl, selecting adds Shift`() {
        assertEquals(0, ArrowPad.meta(ArrowMove.LEFT, selecting = false))
        assertTrue(ArrowPad.meta(ArrowMove.WORD_LEFT, false) and KeyEvent.META_CTRL_ON != 0)
        assertTrue(ArrowPad.meta(ArrowMove.END, false) and KeyEvent.META_CTRL_ON != 0)
        val selecting = ArrowPad.meta(ArrowMove.RIGHT, true)
        assertTrue(selecting and KeyEvent.META_SHIFT_ON != 0)
        assertFalse(selecting and KeyEvent.META_CTRL_ON != 0)
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, ArrowMove.START.keyCode)
    }

    @Test
    fun `selecting wraps the move in Shift's own press, a plain move is just the arrow`() {
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT), ArrowPad.keys(ArrowMove.LEFT, false).map { it.second })
        val selecting = ArrowPad.keys(ArrowMove.LEFT, true)
        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT),
            selecting.map { it.second })
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_UP), selecting.map { it.first })
        assertTrue(selecting[1].third and KeyEvent.META_SHIFT_ON != 0)
    }

    @Test
    fun `read aloud and proofreading never run in a password field, arrows do`() {
        val password = plain.copy(sensitive = true)
        assertFalse(StripTools.allowed(StripTool.READ_ALOUD, password))
        assertTrue(StripTools.allowed(StripTool.ARROWS, password))
        assertTrue(StripTools.allowed(StripTool.PRIVATE, password))
        assertFalse(StripTools.allowed(StripTool.READ_ALOUD, plain.copy(terminal = true)))
        assertFalse(StripTools.allowed(StripTool.REDO, plain.copy(terminal = true)))
    }

    @Test
    fun `redo is offered exactly where undo is`() {
        listOf(plain, plain.copy(sensitive = true), plain.copy(terminal = true), plain.copy(learning = false),
            plain.copy(connected = false), plain.copy(emojiSearch = true)).forEach {
            assertEquals(it.toString(), StripTools.allowed(StripTool.UNDO, it), StripTools.allowed(StripTool.REDO, it))
        }
    }
}
