package dev.localtype.keyboard

/**
 * The text rules typing applies whatever the platform: sentence ends, punctuation that sits against a
 * word, case matching and grapheme lengths. The Android keyboard reaches them through TypingRules, which
 * adds the rules that depend on Android's field types.
 */
object TextRules {
    private val SENTENCE_END = setOf('.', '!', '?')
    private val ATTACHING_PUNCTUATION = setOf('.', ',', '!', '?', ':', ';')
    private const val ZWJ = 0x200D

    /** Two quick spaces after a word become ". ", like most phone keyboards. */
    fun shouldInsertPeriodOnSpace(beforeCursor: String): Boolean {
        if (beforeCursor.length < 2 || !beforeCursor.endsWith(" ")) return false
        val previous = beforeCursor[beforeCursor.length - 2]
        return previous.isLetterOrDigit() || previous == ')' || previous == '"' || previous == '\''
    }

    /** A standalone lowercase "i" just before the cursor, which space should turn into "I". */
    fun isLoneLowercaseI(beforeCursor: String): Boolean =
        beforeCursor.endsWith("i") && (beforeCursor.length == 1 || !beforeCursor[beforeCursor.length - 2].isLetterOrDigit() &&
            beforeCursor[beforeCursor.length - 2] != '\'' && beforeCursor[beforeCursor.length - 2] != '-')

    /** Punctuation typed right after an automatic space should sit against the word instead. */
    fun attachesToPreviousWord(character: Char): Boolean = character in ATTACHING_PUNCTUATION

    fun endsSentence(character: Char): Boolean = character in SENTENCE_END

    /**
     * Matches a lowercase dictionary word to what the person has typed: "Wor" → "World",
     * "WOR" → "WORLD", sentence start → "The". The pronoun "i" and its contractions are always capitalized.
     * All caps needs two capital letters, so "I'" gives "I'll" and "X-" gives "X-ray", unless caps lock is on,
     * which makes every candidate all caps.
     */
    fun applyCase(word: String, typedPrefix: String, sentenceStart: Boolean, capsLock: Boolean = false): String {
        val base = if (word == "i" || word.startsWith("i'")) "I" + word.drop(1) else word
        return when {
            capsLock || (typedPrefix.count { it.isLetter() } > 1 && typedPrefix.all { !it.isLetter() || it.isUpperCase() }) -> base.uppercase()
            typedPrefix.firstOrNull()?.isUpperCase() == true -> base.replaceFirstChar { it.uppercaseChar() }
            typedPrefix.isEmpty() && sentenceStart -> base.replaceFirstChar { it.uppercaseChar() }
            else -> base
        }
    }

    /**
     * What [value] types with shift on. Only a single character up to Latin Extended-B is capitalized,
     * as in the long-press popup, so IPA and Greek alternates are typed exactly as shown ("ə" stays "ə"),
     * and so are the domain keys, whose faces stay lowercase (".com", never ".COM").
     */
    fun shifted(value: String): String = if (value.length == 1 && value[0].code <= 0x024F) value.uppercase() else value

    /**
     * UTF-16 length of the last character a person sees in [text], which delete removes whole: an
     * emoji with its variation selector, skin tone, keycap or ZWJ parts, a flag pair, or a letter with
     * its combining marks. Written by hand rather than with BreakIterator, whose emoji rules differ
     * across Android versions and JDKs. 0 for empty text.
     */
    fun lastGraphemeLength(text: String): Int {
        if (text.isEmpty()) return 0
        var i = text.length
        fun before() = codePointBefore(text, i)
        fun step() { i -= charCount(before()) }
        fun skipExtends() { while (i > 0 && extendsGrapheme(before())) step() }
        skipExtends()
        if (i == 0) return text.length
        val base = before()
        step()
        when {
            base == '\n'.code -> if (i > 0 && before() == '\r'.code) step()
            isRegionalIndicator(base) -> {
                // Flags pair up regional indicators from the start of the run; an odd one out stands alone.
                var run = 0
                var j = i
                while (j > 0 && isRegionalIndicator(codePointBefore(text, j))) { run++; j -= 2 }
                if (run % 2 == 1) step()
            }
            isPictographic(base) -> while (i > 0 && before() == ZWJ) {
                // A ZWJ joins two emoji ("🧑‍💻"); it doesn't join a letter.
                val joint = i
                i -= 1
                skipExtends()
                if (i == 0 || !isPictographic(before())) { i = joint; break }
                step()
            }
        }
        return text.length - i
    }

    /** [text] without its last visible character (see [lastGraphemeLength]). */
    fun dropLastGrapheme(text: String): String = text.dropLast(lastGraphemeLength(text))

    /** Code points that attach to the character before them. */
    private fun extendsGrapheme(c: Int): Boolean = c == ZWJ || c == 0x200C || c in 0x1F3FB..0x1F3FF || c in 0xE0020..0xE007F ||
        when (codePointType(c)) {
            UnicodeType.NON_SPACING_MARK, UnicodeType.ENCLOSING_MARK, UnicodeType.COMBINING_SPACING_MARK -> true
            else -> false
        }

    private fun isRegionalIndicator(c: Int): Boolean = c in 0x1F1E6..0x1F1FF

    /** Close to Unicode's Extended_Pictographic: symbols, plus the emoji planes even where this runtime's tables predate them. */
    private fun isPictographic(c: Int): Boolean = c in 0x1F000..0x1FAFF || c == 0x203C || c == 0x2049 || c == 0x3030 || c == 0x303D ||
        codePointType(c) == UnicodeType.OTHER_SYMBOL
}
