package dev.localtype.keyboard


/** Event-owned, bounded runs. Reading editor text can validate a run but cannot start one. */
class OwnedSpacing {
    private data class Run(val identity: RecentWordCorrection.Identity, val context: String,
        val start: Int, val createdAt: Long, val initial: String, var tail: String = "") {
        val text: String get() = initial + tail
        val before: String get() = context + text
    }
    private var parenthesis: Run? = null
    private var comma: Run? = null
    private var dot: Run? = null
    private var boundary: Run? = null
    private var spacedBoundary = false
    val active: Boolean get() = parenthesis != null || comma != null || dot != null || boundary != null

    fun reset() { parenthesis = null; comma = null; dot = null; boundary = null }

    /** Called only for a single literal opening-parenthesis key whose insertion was verified. */
    fun opened(identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot, now: Long) {
        reset()
        if (!end(s) || !s.before.endsWith('(')) return
        val context = s.before.dropLast(1)
        if (!proseContext(context)) return
        parenthesis = Run(identity, context, s.selection.end - 1, now, "(")
    }

    /** The caller certifies an unchanged, fully typed dictionary word ended by this comma key. */
    fun commaTyped(identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot,
        word: String, now: Long, verifiedOrdinary: Boolean) {
        comma = null
        if (!verifiedOrdinary || !ordinary.matches(word) || !end(s) || !s.before.endsWith("$word,")) return
        val context = s.before.dropLast(word.length + 1)
        if (!proseContext(context)) return
        comma = Run(identity, context, s.selection.end - word.length - 1, now, "$word,")
    }

    /**
     * The caller certifies an unchanged, fully typed dictionary word ended by this period key, with no space
     * before the next letters: the start of "of.the", a period typed for the space beside it.
     */
    fun dotTyped(identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot,
        word: String, now: Long, verifiedOrdinary: Boolean) {
        dot = null
        if (!verifiedOrdinary || !ordinary.matches(word) || word.length < 2 || !end(s) || !s.before.endsWith("$word.")) return
        val context = s.before.dropLast(word.length + 1)
        if (!proseContext(context)) return
        dot = Run(identity, context, s.selection.end - word.length - 1, now, "$word.")
    }

    /** A plain keyboard append, checked against the read immediately before that command. */
    fun appended(identity: RecentWordCorrection.Identity, before: String?, text: String, now: Long, allowed: Boolean) {
        if (!allowed) return reset()
        fun extend(run: Run?, parenthetical: Boolean): Run? {
            run ?: return null
            if (!valid(run, identity, now) || before != run.before || text.length != 1 ||
                !(text[0] in 'a'..'z' || text[0] in 'A'..'Z' || text == " ") ||
                (text == " " && ((!parenthetical && run.tail.isEmpty()) || run.tail.endsWith(' '))) ||
                run.text.length >= MAX_RUN || (!parenthetical && run.tail.contains(' '))) return null
            run.tail += text
            return run
        }
        parenthesis = extend(parenthesis, true)
        comma = extend(comma, false)
        dot = extend(dot, false)
        boundary = boundary?.let { run ->
            val tail = run.tail + text
            if (!valid(run, identity, now) || before != run.before || text.length != 1 ||
                run.text.length >= MAX_RUN || !(if (spacedBoundary) spacedTail else unspacedTail).matches(tail)) null
            else run.apply { this.tail = tail }
        }
    }

    /** A punctuation key, never an editor read alone, starts either new boundary rule. */
    fun punctuationTyped(identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot,
        word: String, punctuation: String, now: Long, verifiedOrdinary: Boolean,
        anchor: SmallAccommodations.Anchor? = null) {
        boundary = null
        if (!end(s) || punctuation !in setOf(":", ";", "!", "?")) return
        val run = if (punctuation in setOf(":", ";")) {
            anchor ?: return
            if (anchor.identity != identity || now - anchor.createdAt !in 0..SmallAccommodations.CONTEXT_MS ||
                !anchor.contextComplete || !proseWord(anchor.original) || !verifiedOrdinary ||
                (punctuation == ":" && anchor.original.lowercase() !in colonIntroducers)) return
            Run(identity, anchor.context, anchor.start, now, anchor.original + " " + punctuation)
        } else {
            if (!verifiedOrdinary || !proseWord(word) || word.lowercase() in punctuatedNames ||
                !s.before.endsWith(word + punctuation)) return
            val context = s.before.dropLast(word.length + 1)
            Run(identity, context, s.selection.end - word.length - 1, now, word + punctuation)
        }
        if (!proseContext(run.context) || s.before != run.before || s.selection.end != run.start + run.text.length) return
        spacedBoundary = punctuation in setOf(":", ";")
        boundary = run
    }

    /** Space has completed the confirming word. Never edit an unfinished or corrected word. */
    fun completed(identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot,
        word: String, now: Long, verifiedOrdinary: Boolean): SmallAccommodations.Offer? {
        val gap = boundary
        boundary = null
        if (verifiedOrdinary && proseWord(word)) {
            val opening = parenthesis
            if (opening != null && matches(opening, identity, s, now) && opening.tail == " $word ") {
                parenthesis = null
                return SmallAccommodations.Offer("( $word", "($word", " ", opening.context,
                    opening.start, SmallAccommodations.Kind.OPENING_PARENTHESIS)
            }
            if (gap != null && matches(gap, identity, s, now) &&
                gap.tail == (if (spacedBoundary) " " else "") + word + " ") {
                // A capitalized next sentence avoids bare queries and most stylized compounds.
                if (!spacedBoundary && !word.first().isUpperCase()) return null
                if (spacedBoundary && (!word.first().isLowerCase() || word !in clauseStarters)) return null
                val original = gap.text.dropLast(1)
                val replacement = gap.initial.replace(" ", "") + " " + word
                return SmallAccommodations.Offer(original, replacement, " ", gap.context, gap.start,
                    if (spacedBoundary) SmallAccommodations.Kind.COLON_SEMICOLON else SmallAccommodations.Kind.SENTENCE_SPACE)
            }
        }
        return null
    }

    /** Continue only the own opening repair whose exact effect the caller just verified. */
    fun openingApplied(identity: RecentWordCorrection.Identity, offer: SmallAccommodations.Offer,
        s: VerifiedWordTransaction.Snapshot, now: Long) {
        if (offer.kind != SmallAccommodations.Kind.OPENING_PARENTHESIS || !end(s) ||
            s.before != offer.context + offer.replacement + offer.delimiter ||
            s.selection.end != offer.start + offer.replacement.length + offer.delimiter.length) return
        val word = offer.replacement.removePrefix("(")
        if (!proseWord(word) || offer.original != "( $word" || offer.delimiter != " ") return
        parenthesis = Run(identity, offer.context, offer.start, now, "(", "$word ")
    }

    /** Inspect before the literal ')' is committed. Apply only after that command is verified. */
    fun closing(identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot, now: Long,
        ordinaryWord: (String) -> Boolean): SmallAccommodations.Offer? {
        val run = parenthesis
        reset()
        if (run == null || !matches(run, identity, s, now) || !run.tail.endsWith(' ')) return null
        val words = run.tail.dropLast(1).split(' ')
        if (words.isEmpty() || words.any { !ordinary.matches(it) || !ordinaryWord(it) }) return null
        val last = words.last()
        val context = s.before.dropLast(last.length + 1)
        return SmallAccommodations.Offer("$last ", last, ")", context,
            s.selection.end - last.length - 1, SmallAccommodations.Kind.PARENTHESIS)
    }

    /** A single space has just completed the second unchanged, fully typed dictionary word. */
    fun missingCommaSpace(identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot,
        word: String, now: Long, verifiedOrdinary: Boolean): SmallAccommodations.Offer? {
        val run = comma
        comma = null
        if (run == null || !verifiedOrdinary || !ordinary.matches(word) ||
            !matches(run, identity, s, now) || run.tail != "$word ") return null
        val original = run.initial + word
        return SmallAccommodations.Offer(original, run.initial + " " + word, " ", run.context,
            run.start, SmallAccommodations.Kind.COMMA_SPACE)
    }

    /**
     * A single space has just completed a second unchanged, fully typed word right after the [dotTyped] one, as in
     * "of.the". Apps turn that into a link. [words] says whether both are ordinary prose words and the second is no
     * address ending. A lowercase second word offers the space the period took ("of the"); a capitalized one offers
     * the space a sentence end left out ("end. Then"). Always an explicit Apply offer, never an automatic edit.
     */
    fun dotSpace(identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot,
        word: String, now: Long, words: (String, String) -> Boolean): SmallAccommodations.Offer? {
        val run = dot
        dot = null
        if (run == null || !ordinary.matches(word) || word.length < 2 || !matches(run, identity, s, now) || run.tail != "$word ") return null
        val first = run.initial.dropLast(1)
        if (word.lowercase() in addressEndings || !words(first, word)) return null
        val replacement = if (word[0].isUpperCase()) "$first. $word" else "$first $word"
        return SmallAccommodations.Offer(run.initial + word, replacement, " ", run.context,
            run.start, SmallAccommodations.Kind.DOT_SPACE)
    }

    private fun matches(run: Run, identity: RecentWordCorrection.Identity, s: VerifiedWordTransaction.Snapshot, now: Long) =
        valid(run, identity, now) && end(s) && s.before == run.before && s.selection.end == run.start + run.text.length
    private fun valid(run: Run, identity: RecentWordCorrection.Identity, now: Long) =
        identity == run.identity && now - run.createdAt in 0..SmallAccommodations.CONTEXT_MS
    private fun end(s: VerifiedWordTransaction.Snapshot) = s.beforeComplete && s.selection.collapsed &&
        s.selection.end == s.before.length && s.after.isEmpty()
    private fun proseContext(context: String) = (context.isEmpty() || context.last().isWhitespace()) &&
        context.none { it in "\"“”‘’'`:=/@#_\\()[]{}" }

    companion object {
        private val ordinary = Regex("(?:[A-Z]?[a-z]{1,31}|[A-Z])")
        private val spacedTail = Regex(" (?:[a-zA-Z]+ ?)?")
        private val unspacedTail = Regex("[a-zA-Z]+ ?")
        private val colonIntroducers = setOf("note", "remember", "reminder", "warning", "tip")
        // A dictionary hit alone does not distinguish English prose from labels or French spacing.
        private val clauseStarters = setOf("bring", "please", "remember", "meet", "come", "leave",
            "send", "take", "check", "keep", "call", "wait", "tell", "ask", "make", "try",
            "you", "they", "this", "that", "there", "then", "the")
        private val punctuatedNames = setOf("yahoo", "panic", "jeopardy", "wham", "pink")
        private val abbreviations = setOf("fig", "ref", "vol", "est", "etc")
        private fun proseWord(word: String) = word.length >= 3 && ordinary.matches(word) &&
            word.lowercase() !in abbreviations
        private const val MAX_RUN = 96
        /** Endings that make "word.ending" more likely an address typed on purpose than a missed space. */
        val addressEndings = setOf("com", "org", "net", "edu", "gov", "mil", "int", "io", "co", "uk", "ca", "de", "fr",
            "au", "nz", "eu", "jp", "app", "dev", "ai", "tv", "info", "biz", "xyz", "ly", "gg", "fm", "social", "online", "site")
    }
}
