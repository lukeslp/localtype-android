package dev.localtype.keyboard

/** A two-word accommodation, using completion evidence supplied by the caller, never inferred ownership. */
object ContextualContractions {
    const val VERSION = "its-context-v1"
    const val CONTEXT_MS = 10_000L
    const val OFFER_MS = 5_000L
    data class Anchor(
        val identity: RecentWordCorrection.Identity, val original: String, val context: String,
        val start: Int, val createdAt: Long, val contextComplete: Boolean,
    )
    data class Offer(val anchor: Anchor, val replacement: String, val delimiter: String, val automatic: Boolean)

    /** Call only after a fully typed, unchanged word and its single space are verified. */
    fun remember(identity: RecentWordCorrection.Identity, snapshot: VerifiedWordTransaction.Snapshot,
                 original: String, now: Long, verifiedOwn: Boolean = false): Anchor? {
        if (!verifiedOwn || original !in setOf("its", "Its") || !snapshot.selection.collapsed || snapshot.after.isNotEmpty() ||
            !snapshot.before.endsWith("$original ")) return null
        val context = snapshot.before.dropLast(original.length + 1)
        // Quoted/literal examples and token-like surroundings are outside this accommodation.
        if (context.any { it in "\"“”‘’'`:=/@#_\\" } || context.lastOrNull()?.isWhitespace() == false) return null
        val start = snapshot.selection.end - original.length - 1
        return Anchor(identity, original, context, start, now, snapshot.beforeComplete).takeIf { start >= 0 }
    }

    fun propose(anchor: Anchor?, identity: RecentWordCorrection.Identity,
                snapshot: VerifiedWordTransaction.Snapshot, follower: String, verifiedFollower: Boolean,
                ownPrevious: String?, now: Long): Offer? {
        anchor ?: return null
        if (!verifiedFollower || ownPrevious != "its" || identity != anchor.identity ||
            now - anchor.createdAt !in 0..CONTEXT_MS || follower !in setOf("been", "a", "an", "not") ||
            !snapshot.selection.collapsed || snapshot.after.isNotEmpty()) return null
        val delimiter = " $follower "
        val expected = anchor.context + anchor.original + delimiter
        if (snapshot.before != expected || snapshot.selection.end != anchor.start + anchor.original.length + delimiter.length) return null
        val context = anchor.context
        val start = context.isBlank() || context.endsWith('\n') || context.trimEnd().lastOrNull() in setOf('.', '!', '?')
        return Offer(anchor, if (anchor.original == "Its") "It's" else "it's", delimiter,
            automatic = follower == "been" && start && anchor.contextComplete)
    }
}
