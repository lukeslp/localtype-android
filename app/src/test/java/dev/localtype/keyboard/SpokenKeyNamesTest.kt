package dev.localtype.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TalkBack's names for the keys: every symbol a page or a hold types has one, and every name the keyboard owns has
 * a string resource whose English is that name, so Spanish and Portuguese phones hear their own words.
 */
class SpokenKeyNamesTest {
    private val inputTypes = listOf(
        InputType.TYPE_CLASS_TEXT,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_NUMBER,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME,
        InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
        InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
    )

    /** Every layout a person can reach: each language, page, field, and the options that change keys or holds. */
    private fun layouts(languages: List<LanguageProfile> = LanguageProfile.ALL): Sequence<Pair<LanguageProfile, KeyboardLayout>> = sequence {
        for (language in languages) for (mode in KeyboardLayout.Mode.entries) for (type in inputTypes)
            for (numberRow in listOf(false, true)) for (common in listOf(false, true)) for (pound in listOf(false, true))
                yield(language to KeyboardLayout.build(mode, true, numberRow, type, language, pound = pound, commonSymbolHolds = common, voiceKey = true))
    }

    private fun typed(key: Key) = listOfNotNull((key.action as? KeyAction.Text)?.value?.takeIf { it.isNotEmpty() }) + key.alternates

    /** A letter, possibly with its marks (Navajo's ą́), or a digit: the voice reads these, and they are not named. */
    private fun letterOrDigit(value: String) = value.all { it.isLetterOrDigit() || Character.getType(it) == Character.NON_SPACING_MARK.toInt() } &&
        value.first().isLetterOrDigit()

    @Test
    fun `every symbol a page or a hold types has a spoken name`() {
        val unnamed = sortedSetOf<String>()
        for ((_, layout) in layouts()) for (key in layout.rows.flatten()) for (value in typed(key)) {
            if (!letterOrDigit(value) && KeyboardLayout.spokenName(value) == value) unnamed += value
        }
        assertTrue("no spoken name: $unnamed", unnamed.isEmpty())
    }

    @Test
    fun `the symbols that were bare glyphs are named`() {
        val expected = mapOf("½" to "One half", "²" to "Superscript two", "€" to "Euro sign", "~" to "Tilde", "⅞" to "Seven eighths",
            "…" to "Ellipsis", "—" to "Em dash", "√" to "Square root", ".edu" to "Dot edu", "[" to "Left bracket")
        expected.forEach { (symbol, name) -> assertEquals(symbol, name, KeyboardLayout.spokenName(symbol)) }
    }

    /**
     * Names the keyboard owns but leaves in English: the Arabic-script marks and joiners, ʻokina, and Navajo's
     * glottal stop are names of a language's own letters and marks; IPA symbols keep the IPA chart's names.
     */
    private fun untranslated(language: LanguageProfile, name: String) =
        !language.words || language.code in setOf("ar", "fa", "he") || name in setOf("ʻOkina", "Glottal stop")

    @Test
    fun `every name spoken on a key or a hold has a resource`() {
        val missing = sortedSetOf<String>()
        for ((language, layout) in layouts(LanguageProfile.WITH_WORDS)) for (key in layout.rows.flatten()) {
            if (key.isSpacer) continue
            // Letters keep their own names (IPA's "ash" for æ on a hold is the IPA keyboard's, left as it is).
            val text = (key.action as? KeyAction.Text)?.value
            val names = listOfNotNull(key.spokenLabel.takeIf { text == null || !letterOrDigit(text) }) +
                typed(key).filterNot(::letterOrDigit).map(KeyboardLayout::spokenName)
            for (name in names) {
                if (untranslated(language, name)) continue
                if (SpokenKeyNames.resolve(name, { "R$it" }, { id, arg -> "R$id($arg)" }) == name) missing += name
            }
        }
        assertTrue("no resource: $missing", missing.isEmpty())
    }

    @Test
    fun `Return's actions and Shift's states have resources`() {
        val enter = listOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS, null).map(TypingRules::enterLabel)
        val shift = listOf("Shift", "Shift, on", "Shift, off", "Caps lock, on", "Caps lock, off")
        (enter + shift).forEach { assertTrue(it, it in SpokenKeyNames.ids) }
    }

    @Test
    fun `the English resources say exactly the names they translate`() {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val english = strings("values")
        SpokenKeyNames.ids.forEach { (name, id) ->
            val resource = names.getValue(id)
            assertEquals(resource, name, english[resource])
        }
        assertEquals("Space, %1\$s", english["key_name_space_with"])
        assertEquals("Capital %1\$s", english["key_name_capital"])
    }

    @Test
    fun `spaces with a language and capitals take their formats, other names pass through`() {
        val text = { id: Int -> "text:$id" }
        val format = { id: Int, arg: String -> "format:$id:$arg" }
        assertEquals("text:${R.string.key_name_comma}", SpokenKeyNames.resolve("Comma", text, format))
        assertEquals("format:${R.string.key_name_space_with}:Español", SpokenKeyNames.resolve("Space, Español", text, format))
        assertEquals("format:${R.string.key_name_capital}:É", SpokenKeyNames.resolve("Capital É", text, format))
        assertEquals("schwa", SpokenKeyNames.resolve("schwa", text, format))
        assertEquals("Fatha", SpokenKeyNames.resolve("Fatha", text, format))
        assertEquals("é", SpokenKeyNames.resolve("é", text, format))
    }

    private fun strings(folder: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml")).documentElement
        val nodes = root.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.replace("\\'", "'") }
    }
}
