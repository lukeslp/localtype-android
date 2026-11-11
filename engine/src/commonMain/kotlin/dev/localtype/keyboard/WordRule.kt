package dev.localtype.keyboard

/**
 * What one language counts as a word while it is being typed. Every language starts a word with a letter and lets
 * letters, an apostrophe or a hyphen continue it: [DEFAULT], the rule English, its tokenizer copies and every language
 * before November 2026 share ([AdaptiveNgramModel.WORD_REGEX]). A language may add [marks]: characters Unicode does
 * not call letters that still sit inside its words, such as the zero-width non-joiner in Persian "می‌رود", the
 * combining acute on Navajo's high nasal vowels ("ą́") and Arabic's vowel marks. Without them the word would end at
 * the mark, and suggestions and correction would work on the half after it.
 *
 * A mark continues a word and may end one; it never starts one.
 */
class WordRule private constructor(val marks: String) {
    val word: Regex = Regex("[\\p{L}][\\p{L}$marks'’-]*")
    val prefix: Regex = Regex("[\\p{L}][\\p{L}$marks'’-]*\\z")

    /** The rest of a word that began before this text: what follows the caret inside a word. */
    val continuation: Regex = Regex("^[\\p{L}$marks'’-]+")

    /** A character a finished word can end on. */
    fun ends(c: Char): Boolean = c.isLetter() || c in marks

    /** A character that extends the word being typed. */
    fun continues(c: Char): Boolean = c.isLetter() || c in marks || c in "'’-"

    fun activePrefix(beforeCursor: String): String = prefix.find(beforeCursor)?.value.orEmpty()

    override fun toString(): String = "WordRule(${marks.map { it.code.toString(16) }})"

    companion object {
        val DEFAULT = WordRule("")

        /**
         * A word some language's rule has already cut out, checked before it is stored or compared: any combining mark
         * or the non-joiner may sit inside it. This validates a token; it never finds one in running text, so it cannot
         * change where English words begin and end.
         */
        val STORED: Regex = Regex("[\\p{L}][\\p{L}\\p{Mn}‌'’-]*")

        fun of(marks: String): WordRule {
            if (marks.isEmpty()) return DEFAULT
            // Only combining marks and invisible joiners: nothing that could mean something inside a character class.
            require(marks.all { it.category == CharCategory.NON_SPACING_MARK || it.category == CharCategory.FORMAT }) { "not a mark" }
            return WordRule(marks)
        }
    }
}
