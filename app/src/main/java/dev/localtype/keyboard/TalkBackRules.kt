package dev.localtype.keyboard

import android.view.View

/**
 * What TalkBack is told when the keyboard changes under the person's finger, and how a few strip controls present
 * themselves. Pure, so the decisions are tested; the service does the speaking.
 */
object TalkBackRules {
    /** The keys a layer change shows, each said once as "Showing …". */
    enum class Layer { LETTERS, SYMBOLS, MORE_SYMBOLS, DIACRITICS, NUMBER_PAD, PHONE_PAD, DATE_TIME_PAD }

    /**
     * The keys to announce after the layer went from [from] to [to], or null when nothing changed. [diacritics]: the
     * second symbols page is the IPA keyboard's diacritics rather than =\<. A field's first keys are never
     * announced (the field itself is), so the service asks only when a key, a space or a panel changed the layer.
     */
    fun layerShown(from: KeyboardLayout.Mode, to: KeyboardLayout.Mode, diacritics: Boolean = false): Layer? =
        if (from == to) null else layer(to, diacritics)

    fun layer(mode: KeyboardLayout.Mode, diacritics: Boolean = false): Layer = when (mode) {
        KeyboardLayout.Mode.LETTERS -> Layer.LETTERS
        KeyboardLayout.Mode.SYMBOLS -> Layer.SYMBOLS
        KeyboardLayout.Mode.MORE_SYMBOLS -> if (diacritics) Layer.DIACRITICS else Layer.MORE_SYMBOLS
        KeyboardLayout.Mode.NUMBER -> Layer.NUMBER_PAD
        KeyboardLayout.Mode.PHONE -> Layer.PHONE_PAD
        KeyboardLayout.Mode.DATETIME -> Layer.DATE_TIME_PAD
    }

    fun announcement(layer: Layer): Int = when (layer) {
        Layer.LETTERS -> R.string.layer_letters
        Layer.SYMBOLS -> R.string.layer_symbols
        Layer.MORE_SYMBOLS -> R.string.layer_more_symbols
        Layer.DIACRITICS -> R.string.layer_diacritics
        Layer.NUMBER_PAD -> R.string.layer_number_pad
        Layer.PHONE_PAD -> R.string.layer_phone_pad
        Layer.DATE_TIME_PAD -> R.string.layer_date_time_pad
    }

    /**
     * The tools handle's name and state. With a panel open it closes the panel and opens the tools row, so it says
     * that and carries no open-or-closed state (it read "Closed. Tools" over an open panel, 2026-11-25); otherwise
     * it is the tools row's handle and says whether the row is open.
     */
    fun toolsHandle(panelOpen: Boolean, toolsOpen: Boolean): Pair<Int, Int?> =
        if (panelOpen) R.string.tools_handle_close_panel to null
        else R.string.tools_title to (if (toolsOpen) R.string.tools_open else R.string.tools_closed)

    /** An empty word slot draws nothing and does nothing, so TalkBack skips it rather than saying "disabled". */
    fun slotImportance(shown: Boolean): Int =
        if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
}
