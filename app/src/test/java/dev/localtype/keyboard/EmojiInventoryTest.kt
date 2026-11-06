package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiInventoryTest {
    private fun shipped(hasGlyph: (String) -> Boolean = { true }) =
        File("src/main/assets/emoji_inventory.txt").bufferedReader().useLines { EmojiInventory.parse(it, hasGlyph) }

    private val inventory = shipped()

    @Test
    fun `the shipped asset has every Emoji 18 base in its Unicode group`() {
        assertEquals(
            listOf(
                "Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places",
                "Activities", "Objects", "Symbols", "Flags",
            ),
            inventory.groups,
        )
        val perGroup = inventory.emoji.groupingBy { inventory.groups[it.group] }.eachCount()
        assertEquals(
            mapOf(
                "Smileys & Emotion" to 172, "People & Body" to 390, "Animals & Nature" to 161, "Food & Drink" to 132,
                "Travel & Places" to 221, "Activities" to 85, "Objects" to 268, "Symbols" to 224, "Flags" to 270,
            ),
            perGroup,
        )
        assertEquals(1923, inventory.size)
        // Only People & Body has skin tones, and always all five.
        assertEquals(332, inventory.emoji.count { it.tones.isNotEmpty() })
        assertTrue(inventory.emoji.all { it.tones.isEmpty() || it.tones.size == EmojiInventory.TONE_COUNT })
        assertTrue(inventory.emoji.filter { it.tones.isNotEmpty() }.all { inventory.groups[it.group] == "People & Body" })
        // Groups arrive in order, so the panel's sections follow Unicode's.
        assertEquals(inventory.emoji.map { it.group }.sorted(), inventory.emoji.map { it.group })
    }

    @Test
    fun `names, keywords and tones come through`() {
        val thumbsUp = inventory.find("👍")
        assertNotNull(thumbsUp)
        assertEquals("thumbs up", thumbsUp!!.name)
        assertTrue("good" in thumbsUp.keywords)
        assertEquals(listOf("👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"), thumbsUp.tones)
        assertEquals("👍🏽", thumbsUp.variant(3))
        assertEquals("👍", thumbsUp.variant(0))

        val technologist = inventory.find("🧑‍💻")!!
        assertEquals("technologist", technologist.name)
        assertEquals("🧑🏿‍💻", technologist.variant(5))

        // Multi-person sequences keep only same-tone variants; this family has none at all.
        assertEquals(emptyList<String>(), inventory.find("👨‍👩‍👧")!!.tones)
        assertEquals("👨‍👩‍👧", inventory.find("👨‍👩‍👧")!!.variant(2))
    }

    @Test
    fun `flags and variation selectors are kept as Unicode has them`() {
        val us = inventory.find("🇺🇸")
        assertEquals("flag: United States", us?.name)
        assertEquals(8, us?.group)
        assertTrue(inventory.emoji.count { inventory.groups[it.group] == "Flags" && it.name.startsWith("flag:") } > 250)
        // Red heart is stored fully qualified; the bare heart and a toned form both find it.
        assertEquals("❤️", inventory.find("❤")?.value)
        assertEquals("👍", inventory.find("👍🏾")?.value)
        assertNull(inventory.find("abc"))
    }

    @Test
    fun `the glyph check drops what the phone can't draw`() {
        val asked = HashSet<String>()
        // Pretend a phone without flags or the technologist's darkest tone.
        val filtered = shipped { emoji ->
            asked += emoji
            !emoji.startsWith("🇺") && emoji != "🧑🏿‍💻"
        }
        assertNull(filtered.find("🇺🇸"))
        assertNotNull(filtered.find("🇬🇧"))
        // One tone missing drops all five, so the picker offers the full set or none.
        assertEquals(emptyList<String>(), filtered.find("🧑‍💻")!!.tones)
        assertEquals(5, filtered.find("👍")!!.tones.size)
        // Every base and every variant was checked.
        assertTrue("👍🏿" in asked && "👍" in asked && "🇺🇸" in asked)
        assertTrue(filtered.size < inventory.size)
    }

    @Test
    fun `malformed rows are skipped`() {
        val parsed = EmojiInventory.parse(
            sequenceOf(
                "# a comment",
                "#groups\tOne\tTwo",
                "0\t😀\tgrinning face\tface|grin\t",
                "1\t👍\tthumbs up\t\t👍🏻 👍🏼 👍🏽 👍🏾 👍🏿",
                "2\t🙃\tout of range group\t\t",
                "x\t🙃\tnot a number\t\t",
                "0\t🙂\ttoo few fields",
                "0\t\tempty emoji\t\t",
                "1\t👋\tpartial tones\t\t👋🏻 👋🏼",
                "",
            ),
        )
        assertEquals(listOf("😀", "👍", "👋"), parsed.emoji.map { it.value })
        assertEquals(listOf("face", "grin"), parsed.find("😀")!!.keywords)
        assertEquals(emptyList<String>(), parsed.find("👍")!!.keywords)
        assertEquals(5, parsed.find("👍")!!.tones.size)
        assertEquals(emptyList<String>(), parsed.find("👋")!!.tones)
        // Rows before a #groups line have no group to belong to.
        assertEquals(0, EmojiInventory.parse(sequenceOf("0\t😀\tgrinning face\t\t")).size)
    }

    @Test
    fun `keys and tones read from the code points`() {
        assertEquals("👍", EmojiInventory.key("👍🏽"))
        assertEquals("❤", EmojiInventory.key("❤️"))
        assertEquals("🧑‍💻", EmojiInventory.key("🧑🏾‍💻"))
        assertEquals(0, EmojiInventory.toneOf("👍"))
        assertEquals(1, EmojiInventory.toneOf("👍🏻"))
        assertEquals(5, EmojiInventory.toneOf("🧑🏿‍💻"))
        assertEquals(0, EmojiInventory.toneOf(""))
    }
}
