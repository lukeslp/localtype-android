package dev.localtype.keyboard

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CharacterToolsTest {
    @Test fun catalogSearchFindsNamesAcrossLanguagesAndKeepsPresentationExplicit() {
        for (query in listOf("trademark", "marca comercial", "tm")) {
            val matches = PlainSymbols.search(query)
            assertTrue(matches.any { it.text == "™" && it.kind == PlainSymbols.Kind.TEXT })
            assertTrue(matches.any { it.text == "™\uFE0F" && it.kind == PlainSymbols.Kind.EMOJI })
        }
        assertTrue(PlainSymbols.search("direitos autorais").any { it.text == "©" })
        assertTrue(PlainSymbols.search("clave de fa").any { it.text == "𝄢" })
        assertTrue(PlainSymbols.search("fonética").any { it.text == "ə" })
        assertTrue(PlainSymbols.search("less than").any { it.text == "≤" })
        assertTrue(PlainSymbols.search("no such invented symbol").isEmpty())
        assertEquals(PlainSymbols.entries.size, PlainSymbols.entries.map { it.text }.distinct().size)
    }

    @Test fun catalogNeverAddsPresentationSelectorsToTextEntries() {
        for (entry in PlainSymbols.entries) {
            if (entry.kind == PlainSymbols.Kind.TEXT) assertFalse(entry.text.any { it == '\uFE0E' || it == '\uFE0F' })
            else assertTrue(entry.text.endsWith('\uFE0F'))
            for (lang in listOf("en", "es", "pt")) assertTrue(entry.name(lang).isNotBlank())
        }
    }

    @Test fun localKeysBoundAndDeleteWholeCharacters() {
        assertEquals("ab", LocalPanelInput.edit("ab", KeyAction.Text("😀"), 3))
        assertEquals("ab😀", LocalPanelInput.edit("ab", KeyAction.Text("😀"), 4))
        assertEquals("ab", LocalPanelInput.edit("ab😀", KeyAction.Backspace, 4))
        assertEquals("", LocalPanelInput.edit("e\u0301", KeyAction.Backspace, 8))
        assertEquals("a", LocalPanelInput.edit("a", KeyAction.Text("\u0000"), 8))
        assertNull(LocalPanelInput.edit("a", KeyAction.Enter, 8))
        assertNull(LocalPanelInput.edit("a", KeyAction.Voice, 8))
    }

    @Test fun effectsAreExplicitCopiesAndKeepUnsupportedCharacters() {
        val original = "Ab9 é😀"
        assertEquals("Ａｂ９　é😀", TextEffects.preview(original, TextEffects.Style.FULLWIDTH))
        assertEquals("𝙰𝚋𝟿 é😀", TextEffects.preview(original, TextEffects.Style.MONOSPACE))
        assertEquals("ᴀʙ9 é😀", TextEffects.preview(original, TextEffects.Style.SMALL_CAPS))
        assertEquals("ᵃᵇ9 é😀", TextEffects.preview(original, TextEffects.Style.SUPERSCRIPT))
        assertEquals("Ab9 é😀", original)
        for (style in TextEffects.Style.entries) {
            assertEquals("™\uFE0F", TextEffects.preview("™\uFE0F", style))
        }
    }

    @Test fun cursedMarksDoNotAccumulateAndExpansionIsBounded() {
        val once = TextEffects.preview("ab", TextEffects.Style.CURSED)!!
        assertEquals("a\u0301\u0332b\u0301\u0332", once)
        assertEquals(once, TextEffects.preview(once, TextEffects.Style.CURSED))
        assertEquals("e\u0301", TextEffects.preview("e\u0301", TextEffects.Style.CURSED))
        for (style in TextEffects.Style.entries) {
            assertNull(TextEffects.preview("a".repeat(257), style))
            assertNull(TextEffects.preview("a\u0301\u0302\u0303", style))
            assertNull(TextEffects.preview("\uD800", style))
            assertNull(TextEffects.preview("a\u0000", style))
            assertNull(TextEffects.preview("", style))
            assertTrue(TextEffects.preview("a".repeat(256), style)!!.length <= TextEffects.MAX_OUTPUT)
        }
    }

    @Test fun localDraftRoutingPrecedesVoiceAndEditorDispatch() {
        val source = File("src/main/java/dev/localtype/keyboard/LocalTypeInputMethodService.kt").readText()
        val start = source.indexOf("override fun onKey(action: KeyAction)")
        val route = source.indexOf("if (localInputKind != null)", start)
        val voice = source.indexOf("if (action == KeyAction.Voice)", start)
        val dispatch = source.indexOf("try { dispatchKey", start)
        assertTrue(start < route && route < voice && voice < dispatch)
        val input = source.substring(source.indexOf("private fun characterToolKey"), source.indexOf("// region Candidates"))
        assertFalse(input.contains("currentInputConnection"))
        assertFalse(input.contains("commitText"))
        assertFalse(input.contains("logOffer"))
    }
}
