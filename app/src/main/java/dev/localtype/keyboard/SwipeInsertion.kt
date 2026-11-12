package dev.localtype.keyboard

import android.text.InputType
import java.util.Locale

/** A swipe starts a new word only. It never replaces a selection, pasted prefix or identifier. */
object SwipeInsertion {
    private const val OPENERS = "(\"“‘"

    /**
     * [afterJoiners]: in an address, a word may also start right after one of [SwipeRules.JOINERS] ("name@", "example.").
     * [afterSwipe]: the editor is exactly as the last swipe left it, after a word that got no space because text follows
     * it ("(word|)", "word| next"). A swipe may start there and puts the space in first ([lead]).
     */
    fun mayStart(s: VerifiedWordTransaction.Snapshot, afterJoiners: Boolean = false, afterSwipe: Boolean = false): Boolean =
        s.selection.collapsed &&
        (s.before.isNotEmpty() || s.selection.end == 0) &&
        (s.before.isEmpty() || s.before.last().isWhitespace() || s.before.last() in OPENERS || loneLetter(s.before) != null ||
            (afterJoiners && s.before.last() in SwipeRules.JOINERS) || afterSwipe) &&
        (s.after.isEmpty() || s.after.first().isWhitespace() || s.after.first() in "),.!?;:\"”’" ||
            (afterJoiners && s.after.first() in SwipeRules.JOINERS))

    /**
     * The one-letter word "a" or "I" (either case) ending [before], standing alone after a space, an
     * opening mark or the start of the field. A swipe may follow it without a typed space.
     */
    fun loneLetter(before: String): Char? {
        val letter = before.lastOrNull()?.takeIf { it in "aAiI" } ?: return null
        val previous = before.getOrNull(before.length - 2)
        return letter.takeIf { previous == null || previous.isWhitespace() || previous in OPENERS }
    }

    /**
     * A straight quotation mark ending [before] that closes a quotation: it follows a letter, a digit or the
     * punctuation that ends a quoted sentence. The same mark opens one after a space, an opening mark or the start
     * of the field, and after anything else ("class=") it is left as an opening mark too.
     */
    fun closingQuote(before: String): Boolean {
        if (before.lastOrNull() != '"') return false
        val previous = before.getOrNull(before.length - 2) ?: return false
        return previous.isLetterOrDigit() || previous in ".,!?…)"
    }

    /** What a swipe replaces before the caret, and the text it puts ahead of the word. */
    data class Lead(val replace: Int, val text: String)

    /**
     * A space after a lone "a" or "I", after a swiped word that got none ([afterSwipe], see [mayStart]), or after a
     * straight quotation mark that closes a quotation ([closingQuote]: [mayStart] takes the mark as an opening one,
     * and the word would run into it); a lone "i" also becomes "I" where [capitalizeI] allows, as a typed space
     * would. Where words run together ([joined]: an address, a password) nothing goes ahead, so a swipe straight
     * after a swiped word runs on ("correct" "horse").
     */
    fun lead(before: String, capitalizeI: Boolean, afterSwipe: Boolean = false, joined: Boolean = false): Lead =
        if (joined) Lead(0, "") else when (loneLetter(before)) {
            null -> if ((afterSwipe && before.lastOrNull()?.isWhitespace() == false) || closingQuote(before)) Lead(0, " ") else Lead(0, "")
            'i' -> if (capitalizeI) Lead(1, "I ") else Lead(0, " ")
            else -> Lead(0, " ")
        }

    /** A swiped "i", "i'm", "i'll" and the like take a capital I where [capitalizeI] allows, as typed ones do. */
    fun word(word: String, capitalizeI: Boolean): String =
        if (capitalizeI && (word == "i" || word.startsWith("i'"))) "I" + word.drop(1) else word

    /** The word as typed with [shift], and a space after it at the end of the text unless [space] is false (an address, a password). */
    fun text(word: String, shift: KeyboardView.ShiftState, after: String, space: Boolean = true): String {
        val cased = when (shift) {
            KeyboardView.ShiftState.LOCKED -> word.uppercase(Locale.ROOT)
            KeyboardView.ShiftState.ON -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
        return cased + if (after.isEmpty() && space) " " else ""
    }
}

data class SwipeRequest(val serial: Long, val snapshot: VerifiedWordTransaction.Snapshot) {
    fun matches(currentSerial: Long, enabled: Boolean, current: VerifiedWordTransaction.Snapshot?): Boolean =
        enabled && serial == currentSerial && snapshot == current
}

object SwipeRules {
    /**
     * A field a swipe may type a word into: any text field, passwords, web and email addresses and list filters
     * included, and a raw-key field such as a terminal (Luke, 2026-10-19: refusing them was a dealbreaker). A swiped
     * word is never learned or logged anywhere, and where the strip is off its alternatives stay hidden. Number,
     * phone and date fields have no letter keys to swipe on.
     */
    fun field(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT, InputType.TYPE_NULL -> true
        else -> false
    }

    /** Where words run together, as in a web or email address or a password: no space after a swiped word. */
    fun joined(inputType: Int): Boolean = TypingRules.isUrl(inputType) || TypingRules.isEmail(inputType) || TypingRules.isSensitive(inputType)

    /** What a word in an address follows directly, with no space: "name@", "example.", "path/", "first-", "a_", "host:". */
    const val JOINERS = "@./-_:"
}
