package dev.localtype.keyboard

/**
 * What the strip's last slot can offer that is not a word: a saved text for its shortcut, the rest of a
 * remembered host, or the link before the caret without its tracking ([LinkCleaner.atCaret]). Pure. An expansion's text never enters the list of suggested words, so no offer or tap
 * row of the typing log can hold it and it is never learned as a word. Once taken it is text in the field,
 * like a paste; [SavedWords] keeps a saved text's words from becoming a learned previous word.
 */
object Expansions {
    enum class Kind { SHORTCUT, DOMAIN, LINK, SYMBOL }

    /**
     * [label] is what the slot shows. Taking it removes [replace] characters before the caret and inserts
     * [text]: a shortcut replaces its word with the saved text, a host only appends what is missing, and a link
     * is replaced by its cleaned form. [host] names the remembered host for [Kind.DOMAIN], and the link's site for
     * [Kind.LINK], where it is only said aloud and never remembered.
     */
    data class Offer(val kind: Kind, val label: String, val replace: Int, val text: String, val host: String? = null)

    /** The saved text whose shortcut is exactly the word being typed. */
    fun shortcut(prefix: String, saved: SavedTexts?): Offer? {
        val text = saved?.expansion(prefix) ?: return null
        return Offer(Kind.SHORTCUT, SavedTexts.preview(text, LABEL_CHARS), prefix.length, text)
    }

    /** A symbol for the cue or name typed just before the caret ([SymbolCues]): `(c)` or "copyright" offers ©. */
    fun symbol(run: String, prefix: String): Offer? =
        SymbolCues.find(run, prefix)?.let { Offer(Kind.SYMBOL, it.symbol, it.replace, it.symbol) }

    /** The link standing right before the caret, offered without its tracking under [label]. */
    fun link(found: LinkCleaner.AtCaret, label: String): Offer = Offer(Kind.LINK, label, found.replace, found.text, found.host)

    /** Where the run is being typed, which decides how soon a host is offered. */
    enum class Field { ADDRESS, MAIL, TEXT }

    /**
     * The remembered host that the run before the caret is the start of, or null. [run] is the non-whitespace
     * text before the caret; [offered] the hosts typed often enough, best first.
     *
     * After the `@` of a mail address one character is enough, and in a mail field nothing is offered before
     * it, where the name goes. In an address bar, or after a scheme, two are enough. In ordinary text it takes
     * three, and the run must already hold a dot or be a word nothing in the dictionary completes
     * ([hasCompletion] false), so a host never takes the place of a prediction for an ordinary word.
     *
     * After an `@`, when no remembered host fits, one of [common] (best first, such as [MailDomains.COMMON]) is
     * offered instead, with no [Offer.host], so taking it never counts as the person's own host.
     */
    fun domain(
        run: String, offered: List<RememberedDomains.Entry>, field: Field, common: List<String> = emptyList(),
        hasCompletion: () -> Boolean,
    ): Offer? {
        if (offered.isEmpty() && common.isEmpty()) return null
        val typed = DomainHosts.typing(run) ?: return null
        val needed = when {
            // "@lu" opens a handle, which [DomainHosts] reads no host from; "name@" opens a mail address.
            '@' in run -> MAIL_CHARS
            field == Field.MAIL -> return null
            field == Field.ADDRESS || "://" in run -> ADDRESS_CHARS
            else -> TEXT_CHARS
        }
        if (typed.length < needed) return null
        val match = offered.firstOrNull { it.host.length > typed.length && it.host.startsWith(typed) }
        if (match == null) {
            if ('@' !in run) return null
            val usual = common.firstOrNull { it.length > typed.length && it.startsWith(typed) } ?: return null
            // It replaces what was typed of it, so "Luke@G" (Shift still on) becomes "Luke@gmail.com".
            return Offer(Kind.DOMAIN, usual, typed.length, usual)
        }
        if (needed == TEXT_CHARS && '.' !in typed && hasCompletion()) return null
        return Offer(Kind.DOMAIN, match.host, 0, match.host.substring(typed.length), match.host)
    }

    /**
     * Whether a host may be offered with [after] standing right after the caret (empty at the end of the
     * text). Anything that would continue the address rules it out: "example|.com" must not become
     * "example.com.com". White space and closing punctuation do not continue it.
     */
    fun hostEndsAt(after: CharSequence): Boolean = after.isEmpty() || after[0].isWhitespace() || after[0] in CLOSERS

    /**
     * Whether a shortcut's word ends at the caret, given the two characters [after] it: a letter or digit is
     * more of the same word, and so is an apostrophe or hyphen with a letter behind it ("can|'t").
     */
    fun wordEndsAt(after: CharSequence): Boolean = when {
        after.isEmpty() -> true
        after[0].isLetterOrDigit() -> false
        after[0] in "'’-" -> after.length < 2 || !after[1].isLetterOrDigit()
        else -> true
    }

    private const val CLOSERS = ")]}>\"'”’,;!?"

    /**
     * Whether [run], the non-whitespace text before the caret in an address bar, is still a word: letters with
     * an apostrophe or hyphen among them, or nothing yet. Anything else (a dot, a slash, a colon, a digit) makes
     * it an address or a term, and words are not predicted into one.
     */
    fun proseRun(run: String): Boolean = run.all { it.isLetter() || it in "'’-" }

    /** The run of non-whitespace text that ends [before]. */
    fun run(before: String): String = before.takeLastWhile { !it.isWhitespace() }

    private const val MAIL_CHARS = 1
    private const val ADDRESS_CHARS = 2
    private const val TEXT_CHARS = 3
    /** A slot is a third of the strip: enough of a saved text to tell it from another, at the smallest size the slot draws. */
    const val LABEL_CHARS = 16
}

/**
 * Keeps a saved text's words out of the learned pairs. Pure.
 *
 * A saved text can be an address or an account name. Once one is in a field, by its shortcut or from the
 * panel, the word typed next would be learned with the saved text's last word as its previous word, and a
 * learned pair is something settings lists and the typing log's snapshot copies. Where the text went is not
 * something a keyboard can keep track of: editors restart, carets move, people edit around it and come back
 * to drafts. So the rule does not try. It asks two things the keyboard does know: is the previous word one
 * that stands in a saved text, and did the person type that word here? A pair is learned only when the
 * first answer is no or the second is yes.
 *
 * The second answer is the keyboard's own record of the words just completed on it at this spot
 * ([PersonalLearningCoordinator.context]), which is never read from the field and is given up on any paste,
 * expansion, caret move or Backspace. So a saved text's own words are never "typed here", however the text
 * around them is edited, and a sentence the person types themselves is learned as usual even when a saved
 * text holds the same words. The cost: where that record has just been given up, the word after a word that
 * also stands in a saved text goes unlearned.
 *
 * What it does not reach: a word the person makes by typing letters onto a saved word of three letters or
 * fewer, and a saved text removed from the list while a copy of it still stands in a draft.
 */
object SavedWords {
    /** Every word in [texts], spelled as the word model spells words. */
    fun of(texts: Collection<String>): Set<String> =
        texts.flatMapTo(HashSet()) { text -> AdaptiveNgramModel.WORD_REGEX.findAll(text).map { AdaptiveNgramModel.normalize(it.value) } }

    /**
     * Whether the word after [previous] must teach nothing. [previous] is the word before it as the word model
     * reads the field, or null at a sentence start; [ownPrevious] is the last word the person is known to have
     * completed right there, or null; [saved] is [of] the saved texts, or null while they have not been read
     * yet, when any word not known to be the person's own is treated as one of theirs.
     */
    fun blocks(previous: String?, ownPrevious: String?, saved: Set<String>?): Boolean {
        if (previous == null) return false
        val word = AdaptiveNgramModel.normalize(previous)
        if (ownPrevious != null && AdaptiveNgramModel.normalize(ownPrevious) == word) return false
        return saved == null || holds(word, saved)
    }

    /** Whether any of a model cache's [keys] (words joined by spaces) holds a saved text's word, so must not be written down. */
    fun inKeys(keys: List<String>, saved: Set<String>?): Boolean =
        keys.isNotEmpty() && (saved == null || keys.any { key -> key.split(' ').any { holds(AdaptiveNgramModel.normalize(it), saved) } })

    /**
     * Whether [word] is a saved text's: one of its words, or one of them with letters typed straight onto it
     * ("street" and "hello" into "streethello"). Short words start and end too many others to be told so.
     */
    private fun holds(word: String, saved: Set<String>): Boolean = word in saved ||
        saved.any { it.length >= GROWN_CHARS && it.length < word.length && (word.startsWith(it) || word.endsWith(it)) }

    /** A saved word this long or longer is also known at the start or end of a longer word. */
    private const val GROWN_CHARS = 4
}
