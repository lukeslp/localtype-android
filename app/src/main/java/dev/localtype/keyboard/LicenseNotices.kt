package dev.localtype.keyboard

/**
 * What Settings → About → Licenses and credits lists: every notice in the assets' `licenses` folder, which the
 * bundled data's licenses ask the app to show, and the software notices in `licenses/android`. The iPhone app
 * bundles the same `licenses` folder and lists only its top level, so Android's own notices sit one level down.
 * Pure, so the JVM tests run it over the files that ship.
 */
object LicenseNotices {
    const val FOLDER = "licenses"
    const val SOFTWARE_FOLDER = "licenses/android"

    /** The order the screen shows them in: what is credited first, the long license texts last. */
    enum class Section { DATA, SOFTWARE, TEXTS }

    data class Notice(val path: String, val title: String, val section: Section)

    /** A license's whole text, which the notices point to, as against a credit for something bundled. */
    private val LICENSE_TEXT = Regex("""(Apache|CC|MPL)-[A-Za-z0-9.-]+\.txt""")

    /** Kept lines in one block of a shown notice: a screen reader stops once per block, and a long list stays quick to draw. */
    const val BLOCK_LINES = 40

    /**
     * The notices for the file names in [FOLDER] ([top]) and in [SOFTWARE_FOLDER] ([software]), by section and then
     * by name. Anything that is not a .txt file, such as the software folder's own entry, is left out.
     */
    fun notices(top: List<String>, software: List<String>): List<Notice> {
        val data = top.filter(::shown).map { name ->
            Notice("$FOLDER/$name", title(name), if (LICENSE_TEXT.matches(name)) Section.TEXTS else Section.DATA)
        }
        val added = software.filter(::shown).map { name -> Notice("$SOFTWARE_FOLDER/$name", title(name), Section.SOFTWARE) }
        return (data + added).sortedWith(compareBy<Notice> { it.section }.thenBy { it.title.lowercase() })
    }

    private fun shown(name: String) = name.endsWith(".txt") && '/' !in name

    fun title(name: String): String = name.removeSuffix(".txt")

    /** The notice's first line with words in it, for the row under its title, without a comment mark. */
    fun summary(text: String): String =
        text.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { line -> line.any { it.isLetter() } }.orEmpty()

    /**
     * One drawn piece of a notice. [prose] is a paragraph whose line breaks were only for a wide page, joined so
     * it wraps to the phone's width; anything else (a table, a list, a centered heading) is kept line for line.
     * [indent] is how many spaces the piece was set in by, which the screen draws as a margin.
     */
    data class Block(val text: String, val prose: Boolean, val indent: Int = 0)

    /**
     * [text] cut into the blocks the screen draws: at each blank line, and a longer run of kept lines every
     * [maxLines]. Only blank lines, line ends, a remark's # mark and indentation are dropped, and a tab becomes
     * a gap; every word stays, in order.
     */
    fun blocks(text: String, maxLines: Int = BLOCK_LINES): List<Block> {
        require(maxLines > 0)
        val blocks = ArrayList<Block>()
        val run = ArrayList<String>()
        fun close() {
            if (run.isEmpty()) return
            val indent = run.minOf(::indentOf)
            if (wrapped(run)) blocks += Block(joined(run.map { it.trimStart() }), prose = true, indent = indentOf(run.first()))
            else run.chunked(maxLines).forEach { lines ->
                // A tab between columns draws as almost nothing in a text view, so a name would run into its number.
                blocks += Block(lines.joinToString("\n") { it.drop(indent).replace("\t", COLUMN_GAP) }, prose = false, indent = indent)
            }
            run.clear()
        }
        // The block a remark opened, while remarks still follow one another.
        var remark = -1
        text.lineSequence().forEach { line ->
            when {
                line.isBlank() -> { close(); remark = -1 }
                // A file's own remark is a paragraph without its mark, and one that stops mid-sentence runs on into
                // the next remark line. A remark with a tab in it is a table's heading and stays with the table.
                line.startsWith("#") && '\t' !in line && line.any { it.isLetter() } -> {
                    close()
                    val said = line.trimStart('#').trim()
                    if (remark >= 0 && blocks[remark].text.last() !in SENTENCE_END) blocks[remark] = Block(blocks[remark].text + " " + said, prose = true)
                    else { blocks += Block(said, prose = true); remark = blocks.lastIndex }
                }
                else -> { run += line.trimEnd(); remark = -1 }
            }
        }
        close()
        return blocks
    }

    private const val SENTENCE_END = ".:;!?"

    private const val COLUMN_GAP = "   "

    /** A line no shorter than this was filled to a page's width; a shorter one inside a run was ended on purpose. */
    private const val FILLED = 40

    /** A bullet, a number or section number ("2.", "10.3."), or a letter with its point, opening a line. */
    private val MARKED = Regex("""^([#*•>|-]|\d+(\.\d+)*[.)]\s|[A-Za-z][.)]\s)""")

    /** "(4)", "(a)" or "a)" opening a line: a list item, or a reference a sentence happened to wrap at. */
    private val BRACKETED = Regex("""^\(?[A-Za-z0-9]{1,3}\)""")

    private val BARE_LINK = Regex("""^https?://\S+$""")

    private fun indentOf(line: String) = line.length - line.trimStart(' ').length

    /**
     * Sentences filled to a page's width: no tabs, every line but the last full, and the lines after the first
     * set in alike with no list mark of their own. A first line may open a numbered or lettered clause whose
     * other lines hang under it; a plain line with indented lines below it is a heading over a list, and is kept.
     * So is a numbered entry that gives a link a line to itself: it is one fact to a line, not a sentence.
     */
    private fun wrapped(lines: List<String>): Boolean {
        if (lines.any { '\t' in it }) return false
        val first = lines.first().trimStart()
        if (first.startsWith("#")) return false
        val rest = lines.drop(1)
        if (rest.isEmpty()) return true
        val hang = indentOf(rest.first())
        val opens = MARKED.containsMatchIn(first) || BRACKETED.containsMatchIn(first)
        rest.forEachIndexed { i, line ->
            val words = line.trimStart()
            if (indentOf(line) != hang || MARKED.containsMatchIn(words)) return false
            // A bracketed mark starts an item only after a line that ended a clause; mid-sentence it is a reference.
            if (BRACKETED.containsMatchIn(words) && lines[i].last() in ".;:,") return false
            if (opens && BARE_LINK.matches(words)) return false
        }
        if (hang > indentOf(lines.first()) && !opens) return false
        return lines.dropLast(1).all { it.trimStart().length >= FILLED }
    }

    /** The lines as one paragraph. A word the page broke at its hyphen ("Attribution-" / "ShareAlike") is closed up. */
    private fun joined(lines: List<String>): String = buildString {
        lines.forEachIndexed { i, line ->
            if (i > 0) {
                val broken = endsWith('-') && length >= 2 && this[length - 2].isLetter() && line.first().isLetter()
                if (!broken) append(' ')
            }
            append(line)
        }
    }
}
