package dev.localtype.keyboard

/** Why a swipe that travelled like one typed nothing, for the notice that says so. */
enum class SwipeRefusal { FIELD, WORD_START, SELECTION, LANGUAGE }

/** What a press on a letter may become, from the keyboard's state when the finger lands. */
sealed interface SwipeStart {
    data object Accepted : SwipeStart

    /** Swipe typing is off, or the keyboard is busy (a panel, proofreading, loading): taps as ever, no notice. */
    data object Off : SwipeStart

    /** Not here or not now; if the finger travels like a swipe it types nothing and [reason] is shown. */
    data class Refused(val reason: SwipeRefusal) : SwipeStart
}
