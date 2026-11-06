package dev.localtype.keyboard

/**
 * The emoji panel's category tabs, in scroll order. Each shows [glyph] and covers the Unicode
 * [groups]; Smileys and People are two sections under one tab, so nine tabs fit a phone's strip.
 */
enum class EmojiTab(val glyph: String, val groups: List<String>) {
    RECENT("🕒", emptyList()),
    SMILEYS("😀", listOf("Smileys & Emotion", "People & Body")),
    ANIMALS("🐻", listOf("Animals & Nature")),
    FOOD("🍔", listOf("Food & Drink")),
    TRAVEL("🚗", listOf("Travel & Places")),
    ACTIVITIES("⚽", listOf("Activities")),
    OBJECTS("💡", listOf("Objects")),
    SYMBOLS("🔣", listOf("Symbols")),
    FLAGS("🏁", listOf("Flags")),
}

/**
 * The emoji panel as one continuous list: Recent first when it holds anything, then every Unicode
 * group in order, each section a heading row followed by its emoji. Maps list positions to
 * sections, tabs and grid rows, so tab jumps, the tab that follows the scroll, and the row and
 * column TalkBack reads all come from one place. Pure, so the mapping is tested without views.
 */
class EmojiPanelLayout(val sections: List<Section>) {
    /** [group] is the Unicode group name, or null for Recent. */
    data class Section(val group: String?, val tab: EmojiTab, val cells: List<Cell>)

    /** [text] is what the cell shows and inserts (the base, or its chosen tone); [emoji] names it and holds its tones. */
    data class Cell(val text: String, val emoji: EmojiInventory.Emoji)

    /** The list position of each section's heading. */
    private val starts = IntArray(sections.size)

    /** Heading plus cells, over every section. */
    val size: Int

    init {
        var position = 0
        sections.forEachIndexed { index, section ->
            starts[index] = position
            position += 1 + section.cells.size
        }
        size = position
    }

    /** The tabs to show, in order: one per tab that has a section. */
    val tabs: List<EmojiTab> = sections.map { it.tab }.distinct()

    /** The section [position] falls in. */
    fun sectionAt(position: Int): Int {
        val found = starts.binarySearch(position)
        return if (found >= 0) found else -found - 2
    }

    fun isHeader(position: Int): Boolean = starts.binarySearch(position) >= 0

    /** The emoji at [position], or null for a heading. */
    fun cellAt(position: Int): Cell? {
        val section = sectionAt(position)
        return sections.getOrNull(section)?.cells?.getOrNull(position - starts[section] - 1)
    }

    fun headerPosition(section: Int): Int = starts[section]

    fun tabAt(position: Int): EmojiTab? = sections.getOrNull(sectionAt(position))?.tab

    /** Where [tab]'s first section starts, or -1 when no section shows it. */
    fun tabPosition(tab: EmojiTab): Int = sections.indexOfFirst { it.tab == tab }.let { if (it < 0) -1 else starts[it] }

    /** The grid column of [position]; a heading spans the row from column 0. */
    fun columnOf(position: Int, columns: Int): Int {
        val section = sectionAt(position)
        return if (position == starts[section]) 0 else (position - starts[section] - 1) % columns
    }

    /** The grid row of [position], counting heading rows. */
    fun rowOf(position: Int, columns: Int): Int {
        val section = sectionAt(position)
        val above = (0 until section).sumOf { rowsIn(it, columns) }
        return if (position == starts[section]) above else above + 1 + (position - starts[section] - 1) / columns
    }

    fun rowCount(columns: Int): Int = sections.indices.sumOf { rowsIn(it, columns) }

    private fun rowsIn(section: Int, columns: Int): Int = 1 + (sections[section].cells.size + columns - 1) / columns

    companion object {
        /** Initial span count before the view has a measured width. */
        const val INITIAL_COLUMNS = 7
        /** The width a cell aims for, so it stays a comfortable touch target. */
        const val CELL_DP = 48

        /** Narrow screens use fewer columns rather than shrinking touch targets below 48 dp. */
        fun columns(widthPx: Int, density: Float): Int =
            if (widthPx <= 0 || !density.isFinite() || density <= 0f) INITIAL_COLUMNS
            else maxOf(1, (widthPx / (CELL_DP * density)).toInt())

        /**
         * Recent first (each entry as it was inserted, skipping any the panel no longer offers), then
         * each group of [inventory] in order, showing each emoji as [display] picks (its chosen tone).
         * A group whose name no tab claims joins the tab before it.
         */
        fun build(inventory: EmojiInventory, recent: List<String>, display: (EmojiInventory.Emoji) -> String): EmojiPanelLayout {
            val sections = ArrayList<Section>()
            val recentCells = recent.mapNotNull { text ->
                inventory.find(text)?.let { Cell(it.variant(EmojiInventory.toneOf(text)), it) }
            }
            if (recentCells.isNotEmpty()) sections += Section(null, EmojiTab.RECENT, recentCells)
            var tab = EmojiTab.SMILEYS
            inventory.emoji.groupBy { it.group }.forEach { (group, emoji) ->
                val name = inventory.groups[group]
                tab = EmojiTab.entries.firstOrNull { name in it.groups } ?: tab
                sections += Section(name, tab, emoji.map { Cell(display(it), it) })
            }
            return EmojiPanelLayout(sections)
        }
    }
}
