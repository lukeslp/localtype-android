package dev.localtype.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutTest {
    private fun typed(layout: KeyboardLayout) = layout.rows.flatten()
        .mapNotNull { (it.action as? KeyAction.Text)?.value }
        .filter { it.isNotEmpty() }

    @Test
    fun `letters layout contains the whole alphabet`() {
        for (switchKey in listOf(true, false)) {
            val letters = typed(KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, switchKey)).filter { it[0].isLetter() }
            assertEquals(('a'..'z').map(Char::toString).toSet(), letters.toSet())
        }
    }

    @Test
    fun `the comma sits beside the mode key and the switch key beside the space bar`() {
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val url = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        for (mode in listOf(KeyboardLayout.Mode.LETTERS, KeyboardLayout.Mode.SYMBOLS, KeyboardLayout.Mode.MORE_SYMBOLS)) {
            for ((inputType, mark) in listOf(InputType.TYPE_CLASS_TEXT to ",", email to "@", url to "/")) {
                val actions = KeyboardLayout.build(mode, showSwitchKey = true, inputType = inputType).rows.last().map { it.action }
                assertEquals("$mode $mark", KeyAction.Text(mark), actions[1])
                assertEquals("$mode $mark", KeyAction.SwitchKeyboard, actions[2])
                assertEquals("$mode $mark", KeyAction.Space, actions[3])
                val without = KeyboardLayout.build(mode, showSwitchKey = false, inputType = inputType).rows.last().map { it.action }
                assertEquals(listOf(KeyAction.Text(mark), KeyAction.Space), without.subList(1, 3))
            }
        }
    }

    @Test
    fun `every row fills the same width`() {
        for (mode in KeyboardLayout.Mode.entries) for (switchKey in listOf(true, false)) {
            KeyboardLayout.build(mode, switchKey).rows.forEach { row ->
                assertEquals("$mode $row", KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
            }
        }
    }

    @Test
    fun `symbol layouts include digits and common punctuation`() {
        val symbols = typed(KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, false)).toSet()
        assertTrue(symbols.containsAll(('0'..'9').map(Char::toString)))
        assertTrue(symbols.containsAll(listOf("@", "#", "?", "!", "'", "\"", ",", ".", "(", ")")))
    }

    @Test
    fun `british spelling puts the pound where the dollar was`() {
        val symbols = typed(KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, false, pound = true))
        assertTrue("£" in symbols)
        assertFalse("$" in symbols)
        val more = typed(KeyboardLayout.build(KeyboardLayout.Mode.MORE_SYMBOLS, false, pound = true))
        assertTrue("$" in more)
        assertFalse("£" in more)
        val plain = typed(KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, false))
        assertTrue("$" in plain)
        val four = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false, numberRow = true, pound = true).rows.first()
            .first { it.action == KeyAction.Text("4") }
        assertEquals(listOf("£", "$", "⁴"), four.alternates)
    }

    @Test
    fun `less than and greater than are keys on the page the more symbols key opens`() {
        val fields = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
        )
        for (inputType in fields) for (switchKey in listOf(false, true)) {
            val symbols = KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, switchKey, inputType = inputType)
            assertEquals("=\\<", symbols.rows.flatten().single { it.action == KeyAction.ToMoreSymbols }.label)
            val more = KeyboardLayout.build(KeyboardLayout.Mode.MORE_SYMBOLS, switchKey, inputType = inputType)
            val keys = more.rows.flatten()
            assertEquals("Less than", keys.single { it.action == KeyAction.Text("<") }.spokenLabel)
            assertEquals("Greater than", keys.single { it.action == KeyAction.Text(">") }.spokenLabel)
            // ¶ and ∆ gave up their keys and stay a hold away, where TalkBack's hold action reaches them.
            assertEquals("¶", keys.single { it.label == "•" }.longPress)
            assertEquals("∆", keys.single { it.label == "π" }.longPress)
            // A field changes only the bottom row.
            assertEquals(KeyboardLayout.build(KeyboardLayout.Mode.MORE_SYMBOLS, switchKey).rows.dropLast(1), more.rows.dropLast(1))
        }
    }

    @Test
    fun `letters offer accents and top row keeps its digit first`() {
        val keys = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false).rows.flatten().associateBy { it.label }
        assertEquals("1", keys.getValue("q").alternates.first())
        assertTrue("é" in keys.getValue("e").alternates && "ə" in keys.getValue("e").alternates)
        assertTrue("ñ" in keys.getValue("n").alternates && "ŋ" in keys.getValue("n").alternates)
        assertTrue(keys.getValue("a").alternates.none { it.length != 1 })
    }

    @Test
    fun `every key has a spoken label`() {
        for (mode in KeyboardLayout.Mode.entries) {
            KeyboardLayout.build(mode, true).rows.flatten().filterNot { it.isSpacer }.forEach {
                assertTrue("$it", it.spokenLabel.isNotBlank())
            }
        }
    }

    @Test
    fun `number row sits above the letters, shorter, with symbols on hold`() {
        val layout = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, showSwitchKey = false, numberRow = true)
        assertEquals("1234567890", layout.rows.first().joinToString("") { it.label })
        assertEquals(listOf(KeyboardLayout.NUMBER_ROW_HEIGHT, 1f, 1f, 1f, 1f), layout.rowHeights)
        assertEquals(KeyboardLayout.ROW_UNITS, layout.rows.first().sumOf { it.width.toDouble() }.toFloat(), 0.001f)
        val keys = layout.rows.flatten().associateBy { it.label }
        assertEquals("!", keys.getValue("1").longPress)
        assertEquals("@", keys.getValue("2").longPress)
        // With digits on their own row, holding a top-row letter goes straight to accents.
        assertEquals("é", keys.getValue("e").longPress)
    }

    @Test
    fun `number row only changes the letters layout`() {
        for (mode in KeyboardLayout.Mode.entries.filter { it != KeyboardLayout.Mode.LETTERS }) {
            assertEquals(KeyboardLayout.build(mode, false), KeyboardLayout.build(mode, false, numberRow = true))
        }
        assertEquals("1", KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false).rows.first().first().longPress)
    }

    @Test
    fun `number pad keeps a large three by four grid for every numeric flag combination`() {
        for (signed in listOf(false, true)) for (decimal in listOf(false, true)) {
            val flags = (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0) or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            for (password in listOf(false, true)) for (switchKey in listOf(false, true)) {
                val inputType = InputType.TYPE_CLASS_NUMBER or flags or
                    (if (password) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0)
                val layout = KeyboardLayout.build(KeyboardLayout.Mode.NUMBER, switchKey, inputType = inputType)
                assertPadGrid(layout)
                assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"),
                    layout.rows.take(3).flatten().map { it.label })
                assertEquals(listOf(if (signed) "-" else "", "0", if (decimal) "." else ""),
                    layout.rows[3].map { it.label })
                val expected = ('0'..'9').map(Char::toString).toSet() +
                    (if (signed) setOf("-") else emptySet()) + (if (decimal) setOf(".") else emptySet())
                assertEquals(expected, typed(layout).toSet())
                // A decimal field holds a comma on its period, and one with no minus key of its own holds a minus
                // on 0 (a web number box is decimal and never signed). Digits-only boxes and number passwords hold nothing.
                val holds = layout.rows.flatten().filter { it.alternates.isNotEmpty() }.associate { it.label to it.alternates }
                val minusOnZero = decimal && !signed && !password
                assertEquals((if (decimal) mapOf("." to listOf(",")) else emptyMap()) +
                    (if (minusOnZero) mapOf("0" to listOf("-")) else emptyMap()), holds)
                assertPadActions(layout, switchKey)
                assertSpokenLabels(layout)
            }
        }
    }

    @Test
    fun `number pad does not mistake text capitalization flags for numeric flags`() {
        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val layout = KeyboardLayout.build(KeyboardLayout.Mode.NUMBER, false, inputType = text)
        assertEquals(('0'..'9').map(Char::toString).toSet(), typed(layout).toSet())
    }

    @Test
    fun `phone pad offers each phone symbol through its own accessible hold`() {
        val holds = mapOf("0" to "+", "1" to "*", "3" to "#", "7" to "(", "9" to ")")
        for (switchKey in listOf(false, true)) for (numericFlags in listOf(0, InputType.TYPE_NUMBER_FLAG_SIGNED,
            InputType.TYPE_NUMBER_FLAG_DECIMAL, InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)) {
            val layout = KeyboardLayout.build(KeyboardLayout.Mode.PHONE, switchKey,
                inputType = InputType.TYPE_CLASS_PHONE or numericFlags)
            assertPadGrid(layout)
            assertEquals(('0'..'9').map(Char::toString).toSet(), typed(layout).toSet())
            assertEquals(listOf("", "0", ""), layout.rows[3].map { it.label })
            layout.rows.take(4).flatten().filterNot { it.isSpacer }.forEach { key ->
                // TalkBack's hold action types the first alternate, so every phone symbol gets its own key.
                assertEquals(holds[key.label]?.let { listOf(it) }.orEmpty(), key.alternates)
            }
            val spoken = holds.values.map(KeyboardLayout::spokenName)
            assertEquals(listOf("Plus", "Asterisk", "Hash", "Left parenthesis", "Right parenthesis"), spoken)
            assertPadActions(layout, switchKey)
            assertSpokenLabels(layout)
        }
    }

    @Test
    fun `email fields replace comma with at and offer domain suffixes on period hold`() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            for (switchKey in listOf(false, true)) for (numberRow in listOf(false, true)) {
                val layout = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, switchKey, numberRow,
                    inputType = InputType.TYPE_CLASS_TEXT or variation)
                val bottom = layout.rows.last()
                assertTrue(bottom.any { it.action == KeyAction.Text("@") })
                assertFalse(bottom.any { it.action == KeyAction.Text(",") })
                val period = bottom.single { it.action == KeyAction.Text(".") }
                assertEquals(listOf(".com", ".org", ".net"), period.alternates.take(3))
                assertTrue(period.alternates.containsAll(listOf("?", "!")))
                assertEquals("At", bottom.single { it.action == KeyAction.Text("@") }.spokenLabel)
                val dotCom = bottom.single { it.action == KeyAction.Text(".com") }
                assertEquals(listOf(".org", ".net", ".edu", ".me"), dotCom.alternates)
                assertEquals(KeyboardLayout.ROW_UNITS, bottom.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
                assertSpokenLabels(layout)
            }
        }
    }

    @Test
    fun `url fields offer slash and dot com directly without changing letter geometry`() {
        for (switchKey in listOf(false, true)) for (numberRow in listOf(false, true)) {
            val plain = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, switchKey, numberRow)
            val url = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, switchKey, numberRow,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            assertEquals(plain.rows.dropLast(1), url.rows.dropLast(1))
            assertEquals(plain.rowHeights, url.rowHeights)
            assertTrue(url.rows.last().any { it.action == KeyAction.Text("/") })
            assertTrue(url.rows.last().any { it.action == KeyAction.Text(".com") })
            assertFalse(url.rows.last().any { it.action == KeyAction.Text(",") })
            assertEquals("Slash", url.rows.last().single { it.action == KeyAction.Text("/") }.spokenLabel)
            assertEquals(KeyboardLayout.ROW_UNITS, url.rows.last().sumOf { it.width.toDouble() }.toFloat(), 0.001f)
            assertSpokenLabels(url)
        }
    }

    @Test
    fun `field punctuation leaves the letter rows and corrector touch geometry unchanged`() {
        val plain = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false)
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_PERSON_NAME, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)) {
            val field = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false, inputType = InputType.TYPE_CLASS_TEXT or variation)
            assertEquals(plain.rows.take(3), field.rows.take(3))
        }
        for (mode in listOf(KeyboardLayout.Mode.NUMBER, KeyboardLayout.Mode.PHONE)) {
            val layout = KeyboardLayout.build(mode, false, inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            layout.rows.flatten().filter { it.action is KeyAction.Text }.forEach { key ->
                key.label.singleOrNull()?.let { assertNull(KeyGeometry.touchOn(it, 0f, 0f)) }
            }
        }
    }

    @Test
    fun `email and url conveniences stay available after escaping to symbols`() {
        for (mode in listOf(KeyboardLayout.Mode.SYMBOLS, KeyboardLayout.Mode.MORE_SYMBOLS)) {
            val email = KeyboardLayout.build(mode, false,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            assertTrue(email.rows.last().any { it.action == KeyAction.Text("@") })
            assertEquals(listOf(".com", ".org", ".net"),
                email.rows.last().single { it.action == KeyAction.Text(".") }.alternates.take(3))
            val url = KeyboardLayout.build(mode, false, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            assertTrue(url.rows.last().any { it.action == KeyAction.Text("/") })
            assertTrue(url.rows.last().any { it.action == KeyAction.Text(".com") })
            for (layout in listOf(email, url)) {
                assertEquals(KeyboardLayout.ROW_UNITS, layout.rows.last().sumOf { it.width.toDouble() }.toFloat(), 0.001f)
                assertSpokenLabels(layout)
            }
        }
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI)) {
            val layout = KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false,
                inputType = InputType.TYPE_CLASS_NUMBER or variation)
            assertFalse(layout.rows.last().any { it.action == KeyAction.Text("@") || it.action == KeyAction.Text("/") ||
                it.action == KeyAction.Text(".com") })
            assertFalse(layout.rows.last().single { it.action == KeyAction.Text(".") }.alternates.contains(".com"))
        }
    }

    @Test
    fun `escaped number and phone fields can return to their pad from every regular layer`() {
        for ((inputClass, spoken) in listOf(InputType.TYPE_CLASS_NUMBER to "Number pad", InputType.TYPE_CLASS_PHONE to "Phone pad")) {
            for (mode in listOf(KeyboardLayout.Mode.LETTERS, KeyboardLayout.Mode.SYMBOLS, KeyboardLayout.Mode.MORE_SYMBOLS)) {
                for (switchKey in listOf(false, true)) {
                    val layout = KeyboardLayout.build(mode, switchKey, inputType = inputClass)
                    val returnKey = layout.rows.last().single { it.action == KeyAction.ToFieldLayout }
                    assertEquals("123", returnKey.label)
                    assertEquals(spoken, returnKey.spokenLabel)
                    assertFalse(layout.rows.last().any { it.action == KeyAction.Text(",") })
                    val layerAction = if (mode == KeyboardLayout.Mode.LETTERS) KeyAction.ToSymbols else KeyAction.ToLetters
                    assertTrue(layout.rows.last().any { it.action == layerAction })
                    assertEquals(KeyboardLayout.ROW_UNITS, layout.rows.last().sumOf { it.width.toDouble() }.toFloat(), 0.001f)
                }
            }
        }
    }

    private fun assertPadGrid(layout: KeyboardLayout) {
        assertEquals(5, layout.rows.size)
        layout.rows.take(4).forEach { row ->
            assertEquals(3, row.size)
            row.forEach { assertEquals(KeyboardLayout.ROW_UNITS / 3f, it.width, 0.001f) }
        }
        layout.rows.forEach { row ->
            assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
        }
    }

    private fun assertPadActions(layout: KeyboardLayout, switchKey: Boolean) {
        val actions = layout.rows.last().map { it.action }
        assertTrue(KeyAction.ToSymbols in actions)
        assertTrue(KeyAction.Backspace in actions)
        assertTrue(KeyAction.Enter in actions)
        assertEquals(switchKey, KeyAction.SwitchKeyboard in actions)
        assertFalse(KeyAction.Space in actions)
    }

    private fun assertSpokenLabels(layout: KeyboardLayout) {
        layout.rows.flatten().filterNot { it.isSpacer }.forEach { assertTrue("$it", it.spokenLabel.isNotBlank()) }
    }

    @Test
    fun `a wider space bar takes width from the side keys and the widest drops only the plain comma`() {
        fun bottom(mode: KeyboardLayout.Mode, bar: SpaceBar, inputType: Int = InputType.TYPE_CLASS_TEXT, switchKey: Boolean = false) =
            KeyboardLayout.build(mode, switchKey, inputType = inputType, spaceBar = bar).rows.last()
        fun space(row: List<Key>) = row.single { it.action == KeyAction.Space }.width
        for (mode in listOf(KeyboardLayout.Mode.LETTERS, KeyboardLayout.Mode.SYMBOLS, KeyboardLayout.Mode.MORE_SYMBOLS)) {
            for (switchKey in listOf(true, false)) {
                val standard = bottom(mode, SpaceBar.STANDARD, switchKey = switchKey)
                val wider = bottom(mode, SpaceBar.WIDER, switchKey = switchKey)
                val widest = bottom(mode, SpaceBar.WIDEST, switchKey = switchKey)
                for (row in listOf(standard, wider, widest)) assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
                assertEquals(space(standard) + 0.5f, space(wider), 0.001f)
                assertEquals(space(standard) + 1.5f, space(widest), 0.001f)
                assertTrue(wider.any { it.action == KeyAction.Text(",") })
                assertFalse(widest.any { it.action == KeyAction.Text(",") })
                assertEquals(KeyboardLayout.NARROW_SIDE_KEY, wider.first().width, 0.001f)
                assertEquals(KeyboardLayout.NARROW_SIDE_KEY, wider.last().width, 0.001f)
                // The comma is still a hold on the period.
                assertTrue("," in widest.single { it.action == KeyAction.Text(".") }.alternates)
            }
        }
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val url = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        assertTrue(bottom(KeyboardLayout.Mode.LETTERS, SpaceBar.WIDEST, email).any { it.action == KeyAction.Text("@") })
        assertTrue(bottom(KeyboardLayout.Mode.LETTERS, SpaceBar.WIDEST, url).any { it.action == KeyAction.Text("/") })
        // The pads have no space bar and stay as they are.
        assertEquals(KeyboardLayout.build(KeyboardLayout.Mode.NUMBER, false), KeyboardLayout.build(KeyboardLayout.Mode.NUMBER, false, spaceBar = SpaceBar.WIDEST))
        // The letter rows, which the corrector's geometry reads, never change.
        assertEquals(KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false).rows.dropLast(1),
            KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, false, spaceBar = SpaceBar.WIDEST).rows.dropLast(1))
    }

    @Test
    fun `strip heights grow and an unknown name is standard`() {
        assertEquals(StripHeight.STANDARD, StripHeight.of(null))
        assertEquals(StripHeight.STANDARD, StripHeight.of("TALL"))
        assertEquals(48, StripHeight.STANDARD.dp)
        assertTrue(StripHeight.entries.zipWithNext().all { (a, b) -> b.dp > a.dp && b.wordSp > a.wordSp })
        assertTrue(StripHeight.entries.all { it.wordSp > StripHeight.MIN_WORD_SP })
    }

    /** The audit of November 7, 2026: a hold on the ?123 page no longer repeats a key on that page. */
    @org.junit.Test fun theSymbolsPageHoldsOnlyWhatItLacks() {
        val page = KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, showSwitchKey = false)
        val keys = page.rows.flatten().mapNotNull { (it.action as? KeyAction.Text)?.value }.toSet()
        for (key in page.rows.flatten()) for (held in key.alternates) {
            if ((key.label == "." && held == ",") || held.length > 1) continue
            org.junit.Assert.assertFalse("${key.label} holds $held, already a key", held in keys)
        }
        val comma = page.rows.last().first { it.label == "," }
        org.junit.Assert.assertTrue(comma.alternates.containsAll(listOf("–", "—")))
        // At the widest space bar the comma key goes, and the period still holds it.
        val widest = KeyboardLayout.build(KeyboardLayout.Mode.SYMBOLS, showSwitchKey = false, spaceBar = SpaceBar.WIDEST)
        org.junit.Assert.assertTrue(widest.rows.last().first { it.label == "." }.alternates.contains(","))
    }

    @Test
    fun `a date or time field gets digits with its own separators beside 0`() {
        val cases = mapOf(
            InputType.TYPE_DATETIME_VARIATION_DATE to (listOf("/", "0", "-") to mapOf("/" to listOf(".", "-"), "-" to listOf("."))),
            InputType.TYPE_DATETIME_VARIATION_TIME to (listOf(":", "0", "") to emptyMap()),
            InputType.TYPE_DATETIME_VARIATION_NORMAL to (listOf("/", "0", ":") to mapOf("/" to listOf("-", "."))),
        )
        for ((variation, expected) in cases) for (switchKey in listOf(false, true)) {
            val inputType = InputType.TYPE_CLASS_DATETIME or variation
            val layout = KeyboardLayout.build(TypingRules.layoutFor(inputType), switchKey, inputType = inputType)
            assertPadGrid(layout)
            assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), layout.rows.take(3).flatten().map { it.label })
            assertEquals(expected.first, layout.rows[3].map { it.label })
            assertEquals(expected.second, layout.rows.flatten().filter { it.alternates.isNotEmpty() }.associate { it.label to it.alternates })
            assertPadActions(layout, switchKey)
            assertSpokenLabels(layout)
            // Its symbols and letters pages carry a key back to this pad, as a number field's do.
            for (mode in listOf(KeyboardLayout.Mode.SYMBOLS, KeyboardLayout.Mode.MORE_SYMBOLS, KeyboardLayout.Mode.LETTERS)) {
                val back = KeyboardLayout.build(mode, switchKey, inputType = inputType).rows.last().first { it.action == KeyAction.ToFieldLayout }
                assertEquals("Date and time pad", back.spokenLabel)
            }
        }
    }
}
