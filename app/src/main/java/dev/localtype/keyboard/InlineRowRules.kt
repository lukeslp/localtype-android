package dev.localtype.keyboard

/** When a password manager's chips take the middle of the strip, and in what order they sit. Pure. */
object InlineRowRules {
    /** The most chips asked for; a manager with more shows its own list from a pinned chip. */
    const val MAX_CHIPS = 6

    /**
     * Whether the chips show. A field without word suggestions (a password, an email or web address, a field
     * that turns suggestions off) has only a one-line notice for them to cover, so any chip shows. Where there are word slots, a suggestion for this
     * field shows for as long as the manager keeps offering it, and the manager's own pinned buttons alone
     * give the strip back at the first key, so a button that is always there never hides predictions for a
     * whole message.
     *
     * @param chips chips ready to show
     * @param unpinned how many of them are suggestions for this field; the rest are pinned by the manager
     * @param wordSlots the field has word suggestions
     * @param typed a key has been pressed since this field asked for chips
     */
    fun shows(chips: Int, unpinned: Int, wordSlots: Boolean, typed: Boolean): Boolean = when {
        chips <= 0 -> false
        !wordSlots -> true
        unpinned > 0 -> true
        else -> !typed
    }

    /** Display order as indexes into [pinned]: pinned chips first, each group in the order the manager sent it. */
    fun order(pinned: List<Boolean>): List<Int> = pinned.indices.sortedBy { !pinned[it] }

    /** Each chip's left and top from the start edge, and the height of all the lines. */
    data class Flow(val places: List<Pair<Int, Int>>, val height: Int)

    /**
     * The Passwords & cards panel's chips in lines, each at its drawn width and height: a chip that would run past
     * [width] starts the next line, [gap] apart from its neighbours and from the line above. A chip wider than [width]
     * has a line of its own.
     */
    fun flow(sizes: List<Pair<Int, Int>>, width: Int, gap: Int): Flow {
        val places = ArrayList<Pair<Int, Int>>(sizes.size)
        var x = 0
        var y = 0
        var line = 0
        for ((w, h) in sizes) {
            if (x > 0 && x + w > width) {
                x = 0
                y += line + gap
                line = 0
            }
            places += x to y
            x += w + gap
            line = maxOf(line, h)
        }
        return Flow(places, if (sizes.isEmpty()) 0 else y + line)
    }
}

/**
 * One answer from the password manager: which of its chips are pinned, which have been drawn (as a [V], the
 * view Android hands over) and which never will be. Pure, so the counting can be tested without a phone.
 * The row belongs to the answer from the moment it arrives, so the counts start at the chips expected; a chip
 * whose drawing failed or timed out stops counting, or one failed suggestion would keep the row open over the
 * word slots with nothing in it.
 */
class InlineAnswer<V : Any>(private val pinned: List<Boolean>) {
    private val views = ArrayList<V?>(pinned.map { null })
    private val failed = BooleanArray(pinned.size)

    /** Chips this answer still expects or has. */
    val count: Int get() = pinned.indices.count { !failed[it] }
    /** Of those, the ones that are suggestions for the field. */
    val unpinned: Int get() = pinned.indices.count { !failed[it] && !pinned[it] }

    /** Android's reply for chip [index]: its view, or null when it could not be drawn. Indexes outside the answer are ignored. */
    fun drawn(index: Int, view: V?) {
        if (index !in pinned.indices || failed[index]) return
        if (view == null) failed[index] = true else views[index] = view
    }

    /** Stops waiting: every chip not drawn yet is counted out. True when that changed anything. */
    fun giveUp(): Boolean {
        var changed = false
        for (index in pinned.indices) if (views[index] == null && !failed[index]) {
            failed[index] = true
            changed = true
        }
        return changed
    }

    /** The drawn chips in display order with whether each is pinned. */
    fun ordered(): List<Pair<V, Boolean>> = InlineRowRules.order(pinned).mapNotNull { n -> views[n]?.let { it to pinned[n] } }
}
