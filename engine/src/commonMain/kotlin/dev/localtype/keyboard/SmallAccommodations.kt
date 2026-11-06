package dev.localtype.keyboard


/** Small owned-text repairs. Editor text validates an event anchor; it never establishes ownership. */
object SmallAccommodations {
    const val VERSION = "small-v4"
    const val CONTEXT_MS = 10_000L
    const val OFFER_MS = 5_000L
    private val REPEATED = setOf("the", "a", "an", "to", "of", "in", "on", "for", "with")
    private val WORD = Regex("[a-zA-Z]{1,32}")
    private val EXTRA_INITIAL_CAP = Regex("[A-Z]{2}[a-z]{2,}")
    // Pronunciation lists, not a first-letter heuristic. Ambiguous dialects/names stay literal.
    private val AN_FOLLOWERS = setOf("apple", "apricot", "avocado", "egg", "elephant", "engine",
        "envelope", "idea", "insect", "invitation", "octopus", "onion", "orange", "oven", "umbrella", "hour")
    private val A_FOLLOWERS = setOf("banana", "bicycle", "book", "bottle", "car", "cat", "chair",
        "coat", "cup", "dog", "door", "house", "mouse", "phone", "table", "ticket", "tree",
        "university", "uniform", "user", "unicorn")
    private val ARTICLE_PRECEDERS = setOf("have", "has", "had", "need", "needs", "want", "wants",
        "buy", "bought", "bring", "brought", "see", "saw", "find", "found", "get", "got",
        "eat", "ate", "with", "without", "for", "is", "was", "as", "order", "ordered")

    data class Anchor(val identity: RecentWordCorrection.Identity, val original: String,
        val context: String, val start: Int, val createdAt: Long, val contextComplete: Boolean)
    enum class Kind { COMMA, PUNCTUATION, PARENTHESIS, COMMA_SPACE, REPEATED, CAPITALS, YOUR,
        OPENING_PARENTHESIS, COLON_SEMICOLON, SENTENCE_SPACE, ARTICLE, DOT_SPACE }
    data class Offer(val original: String, val replacement: String, val delimiter: String,
        val context: String, val start: Int, val kind: Kind) {
        val automatic: Boolean get() = kind in setOf(Kind.COMMA, Kind.PUNCTUATION, Kind.PARENTHESIS,
            Kind.OPENING_PARENTHESIS, Kind.COLON_SEMICOLON, Kind.SENTENCE_SPACE)
    }

    /** A single keyboard space completed an unchanged, wholly typed word. */
    fun remember(identity: RecentWordCorrection.Identity, snapshot: VerifiedWordTransaction.Snapshot,
        word: String, now: Long, verifiedOwn: Boolean, protected: Boolean = false): Anchor? {
        if (!verifiedOwn || protected || !WORD.matches(word) || !snapshot.selection.collapsed ||
            snapshot.after.isNotEmpty() || !snapshot.before.endsWith("$word ")) return null
        val context = snapshot.before.dropLast(word.length + 1)
        if (context.any { it in "\"“”‘’'`:=/@#_\\" } || context.lastOrNull()?.isWhitespace() == false) return null
        val start = snapshot.selection.end - word.length - 1
        return Anchor(identity, word, context, start, now, snapshot.beforeComplete).takeIf { start >= 0 }
    }

    /** Punctuation is committed as typed first. Only a verified unchanged result permits cleanup. */
    fun comma(anchor: Anchor?, identity: RecentWordCorrection.Identity,
        snapshot: VerifiedWordTransaction.Snapshot, now: Long, punctuation: String = ","): Offer? {
        anchor ?: return null
        if (punctuation !in setOf(",", "!", "?") || !valid(anchor, identity, now) || !snapshot.selection.collapsed || snapshot.after.isNotEmpty() ||
            snapshot.before != anchor.context + anchor.original + " " + punctuation ||
            snapshot.selection.end != anchor.start + anchor.original.length + 2) return null
        return Offer(anchor.original + " ", anchor.original, punctuation, anchor.context, anchor.start,
            if (punctuation == ",") Kind.COMMA else Kind.PUNCTUATION)
    }

    /** Word repairs at the current word's space require explicit Apply. */
    fun word(previous: Anchor?, current: Anchor?, identity: RecentWordCorrection.Identity,
        snapshot: VerifiedWordTransaction.Snapshot, ownPrevious: String?, now: Long,
        known: Boolean, capsLockUsed: Boolean = false): Offer? {
        current ?: return null
        if (!valid(current, identity, now) || snapshot.before != current.context + current.original + " " ||
            !snapshot.selection.collapsed || snapshot.after.isNotEmpty() ||
            snapshot.selection.end != current.start + current.original.length + 1) return null
        val raw = current.original
        val key = raw.lowercase()
        if (!capsLockUsed && known && current.contextComplete && EXTRA_INITIAL_CAP.matches(raw)) {
            val fix = key.replaceFirstChar { it.uppercaseChar() }
            return Offer(raw, fix, " ", current.context, current.start, Kind.CAPITALS)
        }
        previous ?: return null
        if (!valid(previous, identity, now) || ownPrevious != previous.original.lowercase() ||
            current.context != previous.context + previous.original + " ") return null
        if (key in REPEATED && raw == key && previous.original in setOf(key, key.replaceFirstChar { it.uppercaseChar() }))
            return Offer(previous.original + " " + raw, previous.original, " ", previous.context, previous.start, Kind.REPEATED)
        if (previous.original in setOf("your", "Your") && raw == "not")
            return Offer(previous.original, if (previous.original == "Your") "You're" else "you're",
                " not ", previous.context, previous.start, Kind.YOUR)
        if (known && previous.contextComplete && current.contextComplete && raw == key && articleContext(previous.context)) {
            val article = previous.original
            val replacement = when {
                article in setOf("a", "A") && key in AN_FOLLOWERS -> if (article == "A") "An" else "an"
                article in setOf("an", "An") && key in A_FOLLOWERS -> if (article == "An") "A" else "a"
                else -> null
            }
            if (replacement != null) return Offer(article, replacement, " $raw ", previous.context, previous.start, Kind.ARTICLE)
        }
        return null
    }

    private fun articleContext(context: String): Boolean {
        val tail = context.trimEnd()
        if (tail.isEmpty() || context.endsWith('\n') || tail.last() in ".!?") return true
        return tail.takeLastWhile { it.isLetter() }.lowercase() in ARTICLE_PRECEDERS
    }

    /** Multiword/whitespace targets do not fit the legacy single-word FIX grammar. */
    fun loggable(original: String, replacement: String): Boolean =
        AdaptiveNgramModel.WORD_REGEX.matches(original) && AdaptiveNgramModel.WORD_REGEX.matches(replacement)

    private fun valid(anchor: Anchor, identity: RecentWordCorrection.Identity, now: Long): Boolean =
        anchor.identity == identity && now - anchor.createdAt in 0..CONTEXT_MS
}
