package dev.localtype.keyboard

/**
 * Follows the word being typed: where each of its letters was tapped, and whether the person came
 * back to it. A word is "revisited" when typing resumes inside a word that was already finished
 * (backspacing over the space after it, moving the cursor into it, or undoing a correction). A
 * revisited word is never corrected automatically, so a deliberate edit is not undone on space.
 */
class WordTrace {
    private val touches = ArrayList<Touch?>()

    var revisited = false
        private set

    /**
     * A letter was typed after [prefixBefore] (the word's letters before it), tapped at [touch].
     * Returns true when this letter revealed a word the trace did not see being written.
     */
    fun letter(prefixBefore: String, touch: Touch?): Boolean {
        var mismatch = false
        if (prefixBefore.isEmpty()) {
            touches.clear()
            revisited = false
        } else if (touches.size != prefixBefore.length) {
            // Typing into a word this trace didn't see being written.
            touches.clear()
            revisited = true
            mismatch = true
        }
        touches += touch
        return mismatch
    }

    /** A character was deleted; [prefixAfter] is what remains of the word before the cursor. Returns true on a deletion into a finished word. */
    fun backspace(prefixAfter: String): Boolean {
        if (touches.isNotEmpty()) touches.removeAt(touches.lastIndex)
        when {
            prefixAfter.isEmpty() -> {
                touches.clear()
                revisited = false
            }
            touches.size != prefixAfter.length -> {
                // Deleted back into a finished word.
                touches.clear()
                revisited = true
                return true
            }
        }
        return false
    }

    /** The word ended (space, punctuation, a tapped suggestion); the next one starts fresh. */
    fun finish() {
        touches.clear()
        revisited = false
    }

    /** The cursor moved or a correction was undone: whatever is typed next edits an existing word. */
    fun revisit() {
        touches.clear()
        revisited = true
    }

    /** One touch per letter of [prefix], or null when the letters weren't all tapped in this word. */
    fun touchesFor(prefix: String): List<Touch?>? = touches.takeIf { it.size == prefix.length && it.isNotEmpty() }?.toList()

    /** How many letters of the current word were tapped here; a size read, so the typing log can check without copying. */
    val touchCount: Int get() = touches.size

    /** True when [touchesFor] would return a list: every letter of [prefix] was tapped in this word. */
    fun hasTouchesFor(prefix: String): Boolean = touches.size == prefix.length && touches.isNotEmpty()
}
