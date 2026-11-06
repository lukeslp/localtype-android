package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Spanish and Portuguese resources are machine-translated. They must still say the same things in the same
 * shape: every string translated, the same placeholders, the same number of items, and tips still in
 * "gesture|effect" form.
 */
class TranslationResourcesTest {
    private class Entry(val kind: String, val values: List<String>)

    private fun read(folder: String): Map<String, Entry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml")).documentElement
        val entries = LinkedHashMap<String, Entry>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.getAttribute("translatable") == "false") continue
            val items = node.getElementsByTagName("item")
            val values = if (items.length == 0) listOf(node.textContent) else (0 until items.length).map { items.item(it).textContent }
            entries[node.getAttribute("name")] = Entry(node.tagName, values)
        }
        return entries
    }

    private fun placeholders(value: String) = Regex("""%(\d+\$)?[sd]""").findAll(value).map { it.value }.sorted().toList()

    private fun check(folder: String) {
        val english = read("values")
        val translated = read(folder)
        assertEquals("$folder: names", english.keys, translated.keys)
        english.forEach { (name, source) ->
            val target = translated.getValue(name)
            assertEquals("$folder/$name: kind", source.kind, target.kind)
            if (source.kind == "plurals") {
                val englishPlaceholders = source.values.flatMap(::placeholders).toSet()
                target.values.forEach { assertTrue("$folder/$name: $it", placeholders(it).toSet().all { p -> p in englishPlaceholders }) }
                return@forEach
            }
            assertEquals("$folder/$name: items", source.values.size, target.values.size)
            source.values.zip(target.values).forEach { (en, other) ->
                assertEquals("$folder/$name: placeholders in \"$other\"", placeholders(en), placeholders(other))
                if (name.startsWith("tips_") && source.kind == "string-array") {
                    val parts = other.split('|')
                    assertTrue("$folder/$name: \"$other\"", parts.size == 2 && parts.all { it.isNotBlank() })
                }
                assertTrue("$folder/$name is empty", other.isNotBlank())
            }
        }
    }

    @Test
    fun `Spanish says the same things in the same shape`() = check("values-es")

    @Test
    fun `Portuguese says the same things in the same shape`() = check("values-pt")

    @Test
    fun `row summaries stay short enough for one line`() {
        val english = read("values")
        val long = english.filterKeys { it.endsWith("_summary") && !it.startsWith("section") }
            .filterValues { it.values.single().length > 46 }.keys
        assertTrue("too long: $long", long.isEmpty())
    }
}
