package dev.localtype.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine generalized over a language's letters, keys and accents (docs/languages-route-a-2026-10-17.md). */
class LanguageEngineTest {
    private val english = LanguageProfile.ENGLISH
    private val spanish = LanguageProfile.SPANISH

    // ---------------------------------------------------------------- geometry from the layout

    @Test
    fun `english geometry read off every letters layout is the qwerty grid the corrector was tuned on`() {
        val types = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        for (switchKey in listOf(false, true)) for (numberRow in listOf(false, true)) for (type in types) {
            val layout = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, switchKey, numberRow, type)
            assertEquals("switch=$switchKey numberRow=$numberRow type=$type", LetterGeometry.QWERTY, LetterGeometry.from(layout))
        }
        assertEquals(LetterGeometry.QWERTY, english.geometry)
        for (c in 'a'..'z') assertEquals(KeyGeometry.center(c), english.geometry.center(c))
        assertEquals(('a'..'z').toList(), english.geometry.keys)
    }

    @Test
    fun `spanish puts its own n key at the end of a ten-key home row`() {
        val g = spanish.geometry
        assertEquals(Touch(0.5f, 1.5f), g.center('a'))
        assertEquals(Touch(8.5f, 1.5f), g.center('l'))
        assertEquals(Touch(9.5f, 1.5f), g.center('ñ'))
        assertEquals(Touch(0.5f, 0.5f), g.center('q'))
        assertEquals(Touch(2.0f, 2.5f), g.center('z'))
        assertNull(g.center('é'))  // held on e, not a key
        assertEquals(27, g.keys.size)
        assertEquals('ñ', g.keys.last())
    }

    @Test
    fun `spanish letters layout fills every row and labels the new key`() {
        for (numberRow in listOf(false, true)) {
            val layout = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = numberRow, language = spanish)
            layout.rows.forEach { row -> assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.0001f) }
            val home = layout.rows[if (numberRow) 2 else 1]
            assertEquals("asdfghjklñ".map { it.toString() }, home.map { it.label })
            assertEquals("ñ", home.last().spokenLabel)
            assertFalse(home.any { it.isSpacer })
        }
    }

    // ---------------------------------------------------------------- costs

    @Test
    fun `a missing accent costs almost nothing in spanish and a whole slip in english`() {
        assertEquals(0.1f, Corrector.typingCost("tambien", "también", language = spanish), 1e-5f)
        assertEquals(0.1f, Corrector.typingCost("nino", "niño", language = spanish), 1e-5f)
        assertEquals(0.1f, Corrector.typingCost("cancion", "canción", language = spanish), 1e-5f)
        assertEquals(1.0f, Corrector.typingCost("tambien", "también"), 1e-5f)
        // A slip onto the next key plus a missing accent costs both.
        assertEquals(0.8f, Corrector.typingCost("tsmbien", "también", language = spanish), 1e-5f)
        // Meaning a held letter from the key beside its own key: the slip to "e", then the accent.
        assertEquals(0.8f, Corrector.typingCost("tambirn", "también", language = spanish), 1e-5f)
    }

    @Test
    fun `neighbours come from the language's keys`() {
        assertTrue(Corrector.areNeighbors('l', 'ñ', spanish))
        assertTrue(Corrector.areNeighbors('p', 'ñ', spanish))
        assertTrue(Corrector.areNeighbors('é', 'w', spanish))  // é sits on e
        assertFalse(Corrector.areNeighbors('l', 'ñ'))
        assertEquals(0.7f, Corrector.typingCost("selor", "señor", language = spanish), 1e-5f)
        assertEquals(1.0f, Corrector.typingCost("selor", "señor"), 1e-5f)
    }

    private val lines = sequenceOf(
        "también\t2000000", "tambor\t20000", "mañana\t800000", "manzana\t60000", "aquí\t1600000",
        "esta\t1000000", "está\t2400000", "estaba\t300000", "estás\t900000", "ángel\t30000", "señor\t500000",
    )

    @Test
    fun `the spanish corrector restores missing accents and keeps words that are words`() {
        val words = WordDictionary.parse(lines, spanish)
        val corrector = Corrector({ words }, language = { spanish })
        assertEquals("también", corrector.correct("tambien"))
        assertEquals("mañana", corrector.correct("manana"))
        assertEquals("aquí", corrector.correct("aqui"))
        assertNull(corrector.correct("esta"))
        assertEquals("ángel", corrector.correct("angel"))
        // English never considers a word whose first letter is only an accent away.
        assertNull(Corrector({ WordDictionary.parse(lines) }).correct("angel"))
    }

    // ---------------------------------------------------------------- tap offsets

    @Test
    fun `tap offsets follow the layout's keys and skip letters typed by holding`() {
        val g = spanish.geometry
        val offsets = TouchOffsets(g)
        assertTrue(offsets.record("año", "año".map { g.touchOn(it, 0.1f, 0f) }))
        assertEquals(listOf('a', 'o', 'ñ'), offsets.entries().map { it.letter })
        assertEquals(1, offsets.count('Ñ'))
        // "á" is held on "a": it teaches nothing, and the rest of the word still does.
        assertTrue(offsets.record("está", "está".map { g.touchOn(it, 0.1f, 0f) }))
        assertEquals(0, offsets.count('á'))
        assertEquals(1, offsets.count('t'))
    }

    @Test
    fun `a capital counts as its letter only when the two map to each other both ways`() {
        val offsets = TouchOffsets()
        assertTrue(offsets.record("I", listOf(KeyGeometry.touchOn('i', 0.2f, 0f))))
        assertEquals(1, offsets.count('i'))
        assertEquals(0, offsets.count('İ'))
        val touch = Touch(7.5f, 0.5f)
        assertEquals(touch, offsets.correctedTouch('İ', touch))
    }

    @Test
    fun `english keeps its offsets file and every other layout gets its own`() {
        assertEquals("touch_offsets", TouchOffsetStore.preferencesName(english))
        assertEquals("touch_offsets_es", TouchOffsetStore.preferencesName(spanish))
    }

    // ---------------------------------------------------------------- completions

    @Test
    fun `a bare prefix finds accented words and a typed accent is kept`() {
        val words = WordDictionary.parse(lines, spanish)
        assertEquals("mañana", words.completions("manan", 3).first().first)
        assertEquals(listOf("está", "esta", "estás", "estaba"), words.completions("esta", 5).map { it.first })
        assertEquals(listOf("está", "estás"), words.completions("está", 5).map { it.first })
        val plain = WordDictionary.parse(lines)
        assertTrue(plain.completions("manan", 3).isEmpty())
        assertEquals(listOf("esta", "estaba"), plain.completions("esta", 5).map { it.first })
    }

    @Test
    fun `folding drops accents only where the language has them`() {
        assertEquals("cancion", spanish.fold("canción"))
        assertEquals("nino", spanish.fold("niño"))
        assertEquals("café", english.fold("café"))
    }
}
