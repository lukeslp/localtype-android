package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommonSymbolHoldsTest {
    private val symbols = mapOf(
        "a" to "@", "s" to "#", "d" to "$", "f" to "_", "g" to "&", "h" to "-", "j" to "+", "k" to "(", "l" to ")",
        "z" to "*", "x" to "\"", "c" to "'", "v" to ":", "b" to ";", "n" to "!", "m" to "?",
    )

    @Test fun `symbol holds are optional and preserve every old alternate in order`() {
        for (language in LanguageProfile.ALL) for (numberRow in listOf(false, true)) {
            val old = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false, numberRow, language = language)
            assertEquals(old, KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false, numberRow, language = language, commonSymbolHolds = false))
            val enabled = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false, numberRow, language = language, commonSymbolHolds = true)
            assertEquals(old.rowHeights, enabled.rowHeights)
            assertEquals(LetterGeometry.from(old), LetterGeometry.from(enabled))
            old.rows.flatten().zip(enabled.rows.flatten()).forEach { (before, after) ->
                assertEquals(before.copy(alternates = after.alternates), after)
                val topIndex = "qwertyuiop".indexOf(before.label.singleOrNull() ?: ' ')
                val symbol = (if (topIndex >= 0) {
                    if (numberRow) listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}")[topIndex]
                    else ((topIndex + 1) % 10).toString()
                } else symbols[before.label]).takeIf { language.words }
                assertEquals(if (symbol == null) before.alternates else (listOf(symbol) + before.alternates).distinct(), after.alternates)
                assertEquals(after.alternates.distinct(), after.alternates)
            }
        }
    }

    @Test fun `the full symbols and both field pads remain available without changed holds`() {
        for (language in LanguageProfile.ALL) for (mode in KeyboardLayout.Mode.entries.filter { it != KeyboardLayout.Mode.LETTERS }) {
            assertEquals(KeyboardLayout.build(mode, false, language = language),
                KeyboardLayout.build(mode, false, language = language, commonSymbolHolds = true))
        }
    }

    @Test fun `number row removes redundant letter digits without moving the common symbols`() {
        val without = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false, commonSymbolHolds = true)
        val with = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false, numberRow = true, commonSymbolHolds = true)
        fun key(layout: KeyboardLayout, label: String) = layout.rows.flatten().single { it.label == label }
        for ((index, letter) in "qwertyuiop".withIndex()) {
            assertEquals(((index + 1) % 10).toString(), key(without, letter.toString()).longPress)
            assertTrue(key(with, letter.toString()).alternates.none { it.first().isDigit() })
            assertEquals(listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}")[index], key(with, letter.toString()).longPress)
        }
        for ((letter, symbol) in symbols) {
            assertEquals(symbol, key(with, letter).longPress)
            assertEquals(key(without, letter).alternates, key(with, letter).alternates)
        }
    }

    @Test fun `currency overrides spelling consistently on pages and holds`() {
        assertEquals(KeyboardCurrency.FOLLOW_SPELLING, KeyboardCurrency.of(null))
        assertEquals(KeyboardCurrency.FOLLOW_SPELLING, KeyboardCurrency.of("obsolete"))
        for (british in listOf(false, true)) for (currency in KeyboardCurrency.entries) {
            val pound = currency.poundFirst(british)
            assertEquals(if (currency == KeyboardCurrency.FOLLOW_SPELLING) british else currency == KeyboardCurrency.POUND, pound)
            val primary = if (pound) "£" else "$"
            val other = if (pound) "$" else "£"
            fun layout(mode: KeyboardLayout.Mode) = KeyboardLayout.build(mode, false, numberRow = true, pound = pound, commonSymbolHolds = true)
            fun keys(mode: KeyboardLayout.Mode) = layout(mode).rows.flatten()
            assertTrue(keys(KeyboardLayout.Mode.SYMBOLS).any { it.action == KeyAction.Text(primary) })
            assertTrue(keys(KeyboardLayout.Mode.MORE_SYMBOLS).any { it.action == KeyAction.Text(other) })
            assertEquals(primary, keys(KeyboardLayout.Mode.LETTERS).single { it.label == "d" }.longPress)
            assertEquals(primary, keys(KeyboardLayout.Mode.LETTERS).single { it.label == "4" }.longPress)
        }
    }

    @Test fun `symbol shortcuts are visible while accent and number-row faces stay clean`() {
        fun key(enabled: Boolean, numberRow: Boolean, label: String) = KeyboardLayout.build(
            KeyboardLayout.Mode.LETTERS, false, numberRow, commonSymbolHolds = enabled).rows.flatten().single { it.label == label }
        assertNull(key(false, false, "a").holdHint)
        assertEquals("@", key(true, false, "a").holdHint)
        assertEquals("(", key(true, true, "k").holdHint)
        assertEquals("1", key(true, false, "q").holdHint)
        assertEquals("%", key(true, true, "q").holdHint)
        assertNull(key(true, true, "4").holdHint)
    }

    @Test fun `every alternate fits the declared accessibility actions and touch popup coverage`() {
        val ids = Regex("name=\"key_alternate_\\d+\"").findAll(File("src/main/res/values/ids.xml").readText()).count()
        assertTrue(ids > 0)
        for (language in LanguageProfile.ALL) for (mode in KeyboardLayout.Mode.entries) for (numberRow in listOf(false, true)) {
            for (key in KeyboardLayout.build(mode, false, numberRow, language = language, commonSymbolHolds = true).rows.flatten()) {
                assertTrue("${language.code} ${key.label}: ${key.alternates}", key.alternates.size <= ids + 1)
                assertTrue("touch geometry covers ${key.label}", key.alternates.size <= 17)
            }
        }
    }
}
