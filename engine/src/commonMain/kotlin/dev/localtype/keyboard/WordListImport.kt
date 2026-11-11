package dev.localtype.keyboard

/**
 * Reads the words out of a text file a person picked for Added words: a plain list with one word on each line, or
 * a keyboard's personal-dictionary export. Pure text in, candidate spellings out; [AddedWords.addAll] decides which
 * of them are words.
 */
object WordListImport {
    /** A file past this is not a personal word list; the reader stops here and says the file is too large. */
    const val MAX_BYTES = 1_048_576

    /** Lines looked at, so a large file of something else costs a bounded pass. */
    const val MAX_LINES = 20_000

    /** Gboard's export, dictionary.txt, opens with this and writes "shortcut<TAB>word<TAB>locale" rows. */
    private const val GBOARD_HEADER = "# Gboard Dictionary"

    /**
     * One candidate per line, in the file's order and without repeats. A line that starts with # is a comment. On a
     * line with tabs or commas the word is the first field, or the second in Gboard's export, whose first field is
     * the shortcut. Surrounding quotes go. Nothing here checks that a candidate is a word.
     */
    fun parse(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        var gboard = false
        var lines = 0
        for (raw in text.removePrefix("﻿").lineSequence()) {
            if (++lines > MAX_LINES) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith(GBOARD_HEADER)) gboard = true
                continue
            }
            // The untrimmed line: Gboard writes an empty shortcut as a leading tab.
            val fields = raw.trimEnd('\r').split('\t').let { if (it.size == 1) raw.split(',') else it }
            val field = if (gboard && fields.size >= 2) fields[1] else fields[0]
            val word = field.trim().trim('"').trim()
            if (word.isNotEmpty()) seen += word
        }
        return seen.toList()
    }
}
