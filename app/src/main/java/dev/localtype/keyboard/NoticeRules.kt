package dev.localtype.keyboard

/**
 * The short messages the keyboard draws itself where it once showed a toast. Android 13 and later drop a toast
 * from an app that is not in the foreground unless that app may post notifications, and a keyboard is never the
 * foreground app, so a toast from the service or a panel is not seen on most phones.
 */
object NoticeRules {
    enum class Message { REFUSED, UNVERIFIED, SWIPE_OFF_HERE, SWIPE_NEW_WORD, SWIPE_SELECTION, SWIPE_LANGUAGE, LANGUAGE, TOOL_PINNED, TOOL_UNPINNED, TOOLS_ROW_FULL, NOT_OPENED, EFFECT_SOURCE_CHANGED, COPY_FAILED }

    enum class Place { STATUS_ROW, SAVED_TITLE, OVER_HEADER }

    /**
     * What to say after text from the clipboard, Saved text, dictation or the emoji panel was sent to the field,
     * or null when it went in. A field that took the command and shows no sign of it gets the same answer as one
     * whose text could not be read back: look before sending it again.
     */
    fun message(outcome: VerifiedWordTransaction.Insertion): Message? = when (outcome) {
        VerifiedWordTransaction.Insertion.APPLIED -> null
        VerifiedWordTransaction.Insertion.REJECTED -> Message.REFUSED
        VerifiedWordTransaction.Insertion.UNCHANGED, VerifiedWordTransaction.Insertion.UNVERIFIED -> Message.UNVERIFIED
    }

    /**
     * Where it is drawn. With the keys showing, the strip's status row has it, in place of the words or a field's
     * note. Saved text has the strip for its title, so the title says it. The emoji panel has the strip for its tabs
     * or its search bar, which TalkBack may be on, so the line is laid over them and they stay as they are.
     */
    fun place(panel: KeyboardPanel?): Place = when (panel) {
        null -> Place.STATUS_ROW
        KeyboardPanel.SAVED -> Place.SAVED_TITLE
        KeyboardPanel.EMOJI, KeyboardPanel.TOOLS, KeyboardPanel.ARROWS, KeyboardPanel.CONTACTS, KeyboardPanel.PASSWORDS,
        KeyboardPanel.SYMBOLS, KeyboardPanel.TEXT_EFFECTS -> Place.OVER_HEADER
    }

    /**
     * The text size for a sentence the strip shows on two lines. [size] is the person's own; [twoLines] is the
     * height two lines of it take and [available] the strip's height, which does not grow. Where they fit, the
     * size stands. Where they do not, it is the largest size whose two lines do, since a line's height follows its
     * text size.
     */
    fun twoLineSize(size: Float, twoLines: Float, available: Float): Float =
        if (twoLines <= available) size else kotlin.math.floor(size * available / twoLines)
}
