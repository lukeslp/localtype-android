package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiPanelLayoutTest {
    private val inventory = File("src/main/assets/emoji_inventory.txt").bufferedReader().useLines { EmojiInventory.parse(it) }

    /** Groups of 3, 9 and 2 emoji named after the shipped ones, so rows are easy to count. */
    private val small = EmojiInventory.parse(
        sequenceOf(
            "#groups\tSmileys & Emotion\tPeople & Body\tFlags",
            "0\t😀\tgrinning face\t\t",
            "0\t😃\tgrinning face with big eyes\t\t",
            "0\t😄\tgrinning face with smiling eyes\t\t",
        ) + (1..9).map { "1\t${"👋👍👎👏🙌👐🤲🤝🙏".substring((it - 1) * 2, it * 2)}\tperson $it\t\t" } + sequenceOf(
            "2\t🏁\tchequered flag\t\t",
            "2\t🚩\ttriangular flag\t\t",
        ),
    )

    private fun build(recent: List<String> = emptyList(), source: EmojiInventory = small) =
        EmojiPanelLayout.build(source, recent) { it.value }

    @Test
    fun `sections are a heading then their emoji, Recent hidden while empty`() {
        val layout = build()
        assertEquals(3, layout.sections.size)
        assertEquals(listOf(EmojiTab.SMILEYS, EmojiTab.FLAGS), layout.tabs)
        // 3 + 9 + 2 emoji and three headings.
        assertEquals(17, layout.size)
        assertTrue(layout.isHeader(0))
        assertEquals("😀", layout.cellAt(1)?.text)
        assertEquals("😄", layout.cellAt(3)?.text)
        assertTrue(layout.isHeader(4))
        assertEquals("👋", layout.cellAt(5)?.text)
        assertEquals(1, layout.sectionAt(13))
        assertTrue(layout.isHeader(14))
        assertEquals("🚩", layout.cellAt(16)?.text)
        assertNull(layout.cellAt(0))
        assertNull(layout.cellAt(17))
    }

    @Test
    fun `Smileys and People are two sections under one tab`() {
        val layout = build()
        assertEquals("Smileys & Emotion", layout.sections[0].group)
        assertEquals("People & Body", layout.sections[1].group)
        assertEquals(EmojiTab.SMILEYS, layout.sections[1].tab)
        assertEquals(EmojiTab.SMILEYS, layout.tabAt(10))
        assertEquals(EmojiTab.FLAGS, layout.tabAt(14))
        // A tab jumps to its first section's heading.
        assertEquals(0, layout.tabPosition(EmojiTab.SMILEYS))
        assertEquals(14, layout.tabPosition(EmojiTab.FLAGS))
        assertEquals(-1, layout.tabPosition(EmojiTab.FOOD))
        assertEquals(-1, layout.tabPosition(EmojiTab.RECENT))
    }

    @Test
    fun `Recent comes first, as inserted, skipping what the panel doesn't offer`() {
        val layout = build(listOf("👍🏽", "🏁", "🦖", "❤"))
        assertEquals(EmojiTab.RECENT, layout.tabs.first())
        assertNull(layout.sections[0].group)
        // A tone variant shows as inserted only when the inventory offers that tone; here 👍 has none.
        assertEquals(listOf("👍", "🏁"), layout.sections[0].cells.map { it.text })
        assertEquals(0, layout.tabPosition(EmojiTab.RECENT))
        assertEquals(3, layout.tabPosition(EmojiTab.SMILEYS))

        val shipped = build(listOf("👍🏽", "❤"), inventory)
        assertEquals(listOf("👍🏽", "❤️"), shipped.sections[0].cells.map { it.text })
        assertEquals("thumbs up", shipped.sections[0].cells[0].emoji.name)
    }

    @Test
    fun `rows and columns count heading rows and wrap cells`() {
        val layout = build()
        // Columns 4: heading, 3 cells (1 row), heading, 9 cells (3 rows), heading, 2 cells (1 row).
        assertEquals(8, layout.rowCount(4))
        assertEquals(0, layout.rowOf(0, 4))
        assertEquals(1, layout.rowOf(3, 4))
        assertEquals(2, layout.columnOf(3, 4))
        assertEquals(2, layout.rowOf(4, 4))
        assertEquals(0, layout.columnOf(4, 4))
        assertEquals(3, layout.rowOf(5, 4))
        assertEquals(3, layout.rowOf(8, 4))
        assertEquals(3, layout.columnOf(8, 4))
        assertEquals(4, layout.rowOf(9, 4))
        assertEquals(0, layout.columnOf(9, 4))
        assertEquals(5, layout.rowOf(13, 4))
        assertEquals(6, layout.rowOf(14, 4))
        assertEquals(7, layout.rowOf(16, 4))
        assertEquals(1, layout.columnOf(16, 4))
    }

    @Test
    fun `every shipped position maps to one section and one tab`() {
        val layout = build(listOf("😀"), inventory)
        assertEquals(EmojiTab.entries, layout.tabs)
        assertEquals(1 + 1 + 1923 + 9, layout.size)
        var cells = 0
        for (position in 0 until layout.size) {
            val section = layout.sectionAt(position)
            assertEquals(layout.isHeader(position), position == layout.headerPosition(section))
            if (!layout.isHeader(position)) cells++
            assertEquals(layout.sections[section].tab, layout.tabAt(position))
        }
        assertEquals(1924, cells)
        // Tabs run in the order the list scrolls: Unicode's, so Travel comes before Activities.
        val firstPositions = layout.tabs.map { layout.tabPosition(it) }
        assertEquals(firstPositions.sorted(), firstPositions)
        assertEquals(layout.sections.sumOf { 1 + (it.cells.size + 7) / 8 }, layout.rowCount(8))
    }

    @Test
    fun `an unknown group joins the tab before it`() {
        val layout = EmojiPanelLayout.build(
            EmojiInventory.parse(sequenceOf("#groups\tFood & Drink\tSnacks", "0\t🍔\thamburger\t\t", "1\t🍿\tpopcorn\t\t")),
            emptyList(),
        ) { it.value }
        assertEquals(listOf(EmojiTab.FOOD), layout.tabs)
        assertEquals("Snacks", layout.sections[1].group)
    }

    @Test
    fun `display picks what each cell shows`() {
        val layout = EmojiPanelLayout.build(inventory, emptyList()) { it.variant(2) }
        val thumbs = layout.sections.flatMap { it.cells }.first { it.emoji.value == "👍" }
        assertEquals("👍🏼", thumbs.text)
        assertFalse(layout.sections.any { it.tab == EmojiTab.RECENT })
    }

    @Test
    fun `narrow grids retain 48 dp targets rather than forcing seven columns`() {
        assertEquals(8, EmojiPanelLayout.columns(1080, 2.625f))
        assertEquals(5, EmojiPanelLayout.columns(720, 2.625f))
        assertEquals(6, EmojiPanelLayout.columns(840, 2.625f))
        assertEquals(10, EmojiPanelLayout.columns(1440, 3f))
        assertEquals(7, EmojiPanelLayout.columns(0, 2f))
        for (width in listOf(48, 240, 274, 320, 360, 411)) {
            val columns = EmojiPanelLayout.columns(width * 2, 2f)
            assertTrue("$width dp grid", width.toFloat() / columns >= 48)
        }
    }
}
