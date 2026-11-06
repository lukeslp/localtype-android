package dev.localtype.keyboard

/**
 * The word-level tokenizer of `model/tokenize.py`, ported for [CifgModel]'s context. Only what
 * encoding a context needs is here: the token rules and the sentence scope, not the vocabulary
 * builder or detokenizing. Words follow the keyboard's own rule (`[\p{L}][\p{L}'’-]*`, normalized
 * by [AdaptiveNgramModel.normalize]); everything else becomes punctuation, an emoji, `<num>` or
 * `<sym>`, and whitespace is never a token.
 *
 * This is a second copy of the Python rules, like the tokenizer in [PredictionLogSession]: change
 * the two together, or the model sees contexts it was never trained on and nothing fails. The
 * `cifg_tokenizer_cases.tsv` fixture, written by `model/export.py --fixtures`, pins this port to
 * the Python output. Character classes come from the JVM's Unicode tables, so a character newer
 * than the JVM's Unicode version can tokenize differently from Python 3.12 (Unicode 15).
 */
object CifgTokenizer {
    const val PAD = "<pad>"
    const val BOS = "<s>"
    const val EOS = "</s>"
    const val UNK = "<unk>"
    const val NUM = "<num>"
    const val SYM = "<sym>"

    /** Ids 0–5 of every vocabulary, in this order. */
    val SPECIALS = listOf(PAD, BOS, EOS, UNK, NUM, SYM)

    /** Punctuation with a token of its own; any other non-word character is [SYM]. */
    val PUNCT: Set<String> = setOf(
        ".", ",", "?", "!", "…", "'", "\"", "-", ":", ";", "(", ")", "/", "&",
        "$", "%", "*", "+", "=", "@", "#", "~", "_", "[", "]", "<", ">", "^",
        "\\", "|", "°",
    )

    /** Tokens that end a sentence; the context starts after the last of them. */
    val SENTENCE_ENDS: Set<String> = setOf(".", "!", "?", "…")

    /** `<s>` plus at most this many minus one tokens of the sentence: the length training cuts lines at. */
    const val CONTEXT_TOKENS = 64

    /**
     * How far the window moves once a sentence outgrows it. It moves in jumps rather than one token
     * per word, so a [CifgModel.Session] that follows the sentence re-reads the window once every
     * [CONTEXT_HOP] tokens instead of on every word.
     */
    const val CONTEXT_HOP = 32

    /** Look-alike characters folded onto [PUNCT] marks (tokenize.CANON). */
    private val CANON: Map<Int, String> = mapOf(
        '’' to "'", '‘' to "'", '‚' to "'", '`' to "'", '´' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"", '«' to "\"", '»' to "\"",
        '–' to "-", '—' to "-", '‐' to "-", '‑' to "-", '−' to "-", '﹣' to "-", '－' to "-",
        '。' to ".", '．' to ".", '，' to ",", '、' to ",", '：' to ":", '；' to ";",
        '？' to "?", '！' to "!", '（' to "(", '）' to ")", '¿' to "?", '¡' to "!",
        '‥' to "…",
    ).mapKeys { it.key.code }

    /**
     * The tokens a model sees for the text before the cursor, and the word being typed.
     * [Context.prefix] is the normalized [AdaptiveNgramModel.activePrefix], left out of the
     * tokens. The tokens are the current sentence: what follows the last newline, then whatever
     * follows the last [SENTENCE_ENDS] token, which is the split the Python evaluation makes in
     * sentence scope. A sentence of up to [limit] tokens is kept whole; a longer one is read
     * from [windowStart], so the context holds its last `limit - hop + 1` to [limit] tokens.
     */
    fun context(beforeCursor: String, limit: Int = CONTEXT_TOKENS - 1, hop: Int = CONTEXT_HOP): Context {
        require(limit > 0 && hop in 1..limit)
        val prefix = AdaptiveNgramModel.activePrefix(beforeCursor)
        val before = beforeCursor.substring(0, beforeCursor.length - prefix.length)
        val tokens = tokenize(before.substring(before.lastIndexOf('\n') + 1))
        val sentence = tokens.subList(tokens.indexOfLast { it in SENTENCE_ENDS } + 1, tokens.size)
        return Context(sentence.subList(windowStart(sentence.size, limit, hop), sentence.size).toList(), AdaptiveNgramModel.normalize(prefix))
    }

    /**
     * Where the context starts in a sentence of [length] tokens: at 0 until the sentence is longer
     * than [limit], then at the smallest multiple of [hop] that leaves at most [limit] tokens. With
     * the defaults a 63-token sentence is read whole, a 64-token one from token 32, and a 96-token
     * one from token 64. A [hop] of 1 is a window that slides one token at a time.
     */
    fun windowStart(length: Int, limit: Int = CONTEXT_TOKENS - 1, hop: Int = CONTEXT_HOP): Int =
        if (length <= limit) 0 else (length - limit + hop - 1) / hop * hop

    data class Context(val tokens: List<String>, val prefix: String)

    /**
     * tokenize.tokenize: words, punctuation, emoji, [NUM] and [SYM], in order. The alternatives are
     * tried in the Python regex's order (word, emoji, number, whitespace, any one character), so a
     * character both rules accept goes where Python sends it.
     */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            if (isWordStart(cp)) {
                var end = i + charCount(cp)
                while (end < text.length) {
                    val next = codePointAt(text, end)
                    if (!isWordStart(next) && !isJoiner(next)) break
                    end += charCount(next)
                }
                addWord(text, i, end, out)
                i = end
                continue
            }
            val emoji = emojiEnd(text, i)
            when {
                emoji > i -> {
                    out.add(text.substring(i, emoji))
                    i = emoji
                }
                isDigitCodePoint(cp) -> {
                    out.add(NUM)
                    i = numberEnd(text, i)
                }
                isSpace(cp) -> i += charCount(cp)
                else -> {
                    emit(out, punctOrSym(cp))
                    i += charCount(cp)
                }
            }
        }
        return out
    }

    /** True for a whole emoji as the tokenizer reads one: a base, modifiers, ZWJ-joined bases. */
    fun isEmoji(token: String): Boolean = token.isNotEmpty() && emojiEnd(token, 0) == token.length

    /** The key that matches an emoji with or without its variation selector (☀ and ☀️ are one). */
    fun stripPresentation(emoji: String): String = emoji.replace("️", "").replace("︎", "")

    /**
     * A word run as Python's regex finds it may hold number letters (Ⅻ, ², ½) that `\p{L}` does
     * not accept. tokenize._exact_word_pieces splits those out: each becomes its own token, and an
     * apostrophe or hyphen right after one is punctuation, not part of a word.
     */
    private fun addWord(text: String, start: Int, end: Int, out: MutableList<String>) {
        var exact = isLetterCodePoint(codePointAt(text, start))
        var k = start
        while (exact && k < end) {
            val cp = codePointAt(text, k)
            exact = isLetterCodePoint(cp) || isJoiner(cp)
            k += charCount(cp)
        }
        if (exact) {
            out.add(AdaptiveNgramModel.normalize(text.substring(start, end)))
            return
        }
        val run = StringBuilder()
        k = start
        while (k < end) {
            val cp = codePointAt(text, k)
            if (isLetterCodePoint(cp) || (run.isNotEmpty() && isJoiner(cp))) {
                run.append(codePointString(cp))
            } else {
                if (run.isNotEmpty()) out.add(AdaptiveNgramModel.normalize(run.toString()))
                run.setLength(0)
                emit(out, punctOrSym(cp))
            }
            k += charCount(cp)
        }
        if (run.isNotEmpty()) out.add(AdaptiveNgramModel.normalize(run.toString()))
    }

    /** tokenize._emit: repeated marks collapse to one, and two or more dots become …. */
    private fun emit(out: MutableList<String>, token: String) {
        val previous = out.lastOrNull()
        if (token == ".") {
            if (previous == ".") {
                out[out.lastIndex] = "…"
                return
            }
            if (previous == "…") return
        } else if (token == previous && token in PUNCT) {
            return
        }
        out.add(token)
    }

    private fun punctOrSym(cp: Int): String {
        CANON[cp]?.let { return it }
        val text = codePointString(cp)
        return if (text in PUNCT) text else SYM
    }

    /** Python's `[^\W\d_]`: letters plus the letter-like and other numbers (Nl, No) that `\w` accepts. */
    private fun isWordStart(cp: Int): Boolean = when (codePointType(cp)) {
        UnicodeType.UPPERCASE_LETTER, UnicodeType.LOWERCASE_LETTER, UnicodeType.TITLECASE_LETTER,
        UnicodeType.MODIFIER_LETTER, UnicodeType.OTHER_LETTER,
        UnicodeType.LETTER_NUMBER, UnicodeType.OTHER_NUMBER -> true
        else -> false
    }

    private fun isJoiner(cp: Int): Boolean = cp == '\''.code || cp == '’'.code || cp == '-'.code

    /** Python's `\s` (str.isspace), which also counts U+001C–U+001F, U+0085 and the no-break spaces. */
    private fun isSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp in 0x1C..0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
            cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

    /** `\d+(?:[.,:/]\d+)*`, where `\d` is any decimal digit: 12:30, 1,000 and 3.5 are one token. */
    private fun numberEnd(text: String, start: Int): Int {
        var end = digitsEnd(text, start)
        while (end < text.length && text[end] in ".,:/") {
            val more = digitsEnd(text, end + 1)
            if (more == end + 1) break
            end = more
        }
        return end
    }

    private fun digitsEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length) {
            val cp = codePointAt(text, end)
            if (!isDigitCodePoint(cp)) break
            end += charCount(cp)
        }
        return end
    }

    /** tokenize.EMOJI_RE matched at [start]: the end of the emoji, or [start] when there is none. */
    private fun emojiEnd(text: String, start: Int): Int {
        var end = emojiBaseEnd(text, start)
        if (end < 0) return start
        end = modifiersEnd(text, end)
        while (end < text.length && text[end] == '‍') {
            val joined = emojiBaseEnd(text, end + 1)
            if (joined < 0) break
            end = modifiersEnd(text, joined)
        }
        return end
    }

    /** One base: a flag (two regional indicators), a keycap, or one emoji character; -1 for none. */
    private fun emojiBaseEnd(text: String, start: Int): Int {
        if (start >= text.length) return -1
        val cp = codePointAt(text, start)
        val next = start + charCount(cp)
        if (cp in REGIONAL_INDICATORS && next < text.length && codePointAt(text, next) in REGIONAL_INDICATORS) return next + 2
        if (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code) {
            var end = next
            if (end < text.length && text[end] == '️') end++
            return if (end < text.length && text[end] == '⃣') end + 1 else -1
        }
        return if (isEmojiCharacter(cp)) next else -1
    }

    private fun modifiersEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length) {
            val cp = codePointAt(text, end)
            if (cp != 0xFE0E && cp != 0xFE0F && cp !in 0x1F3FB..0x1F3FF && cp !in 0xE0020..0xE007F) break
            end += charCount(cp)
        }
        return end
    }

    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

    private fun isEmojiCharacter(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 || cp == 0xA9 || cp == 0xAE || cp == 0x2122 ||
            cp in 0x2194..0x2199 || cp == 0x21A9 || cp == 0x21AA || cp == 0x24C2 || cp in 0x25AA..0x25FE ||
            cp == 0x2934 || cp == 0x2935
}
