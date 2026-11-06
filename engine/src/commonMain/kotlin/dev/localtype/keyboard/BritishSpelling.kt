package dev.localtype.keyboard

/**
 * American to British spellings for English's Spelling: British choice ("color" to "colour", "organize" to
 * "organise"), read from `spellings_en_gb.txt` (`model/british_spellings.py`, from SCOWL's VarCon).
 *
 * Only what the keyboard offers changes: strip words and correction targets. The ranker, the prior, Chime and the
 * learned tables keep the words they have, and a word the person typed, kept, added or gave capitals to is never
 * swapped. Words whose British form depends on the sense ("program", "check", "tire", "meter") aren't in the file.
 */
class BritishSpelling private constructor(private val british: Map<String, String>) {

    val size: Int get() = british.size

    /** The British form of [word], with its capitals and any possessive kept, or [word] itself. */
    fun of(word: String): String {
        if (word.isEmpty()) return word
        val lower = word.lowercase()
        val (stem, tail) = when {
            lower in british -> lower to ""
            lower.endsWith("'s") -> lower.dropLast(2) to word.takeLast(2)
            lower.endsWith("’s") -> lower.dropLast(2) to word.takeLast(2)
            else -> return word
        }
        val gb = british[stem] ?: return word
        val cased = when {
            word == lower -> gb
            word.length > 1 && word.uppercase() == word -> gb.uppercase()
            word[0].isUpperCase() -> gb.replaceFirstChar { it.uppercaseChar() }
            else -> gb
        }
        return cased + tail
    }

    companion object {
        const val ASSET = "spellings_en_gb.txt"
        val EMPTY = BritishSpelling(emptyMap())

        /** [words] without the later of any two that read the same once spelled British ("colour", "colour"). */
        fun distinct(words: List<String>): List<String> {
            val seen = HashSet<String>()
            return words.filter { it.isEmpty() || seen.add(it.lowercase()) }
        }

        /** `american<TAB>british` lines; `#` lines and anything malformed are skipped. */
        fun parse(lines: Sequence<String>): BritishSpelling {
            val map = HashMap<String, String>()
            lines.forEach { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab == line.lastIndex) return@forEach
                val us = line.substring(0, tab)
                val gb = line.substring(tab + 1)
                if (us != gb) map[us] = gb
            }
            return BritishSpelling(map)
        }
    }
}
