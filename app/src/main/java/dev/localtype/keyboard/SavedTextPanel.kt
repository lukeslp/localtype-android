package dev.localtype.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat

/**
 * The Saved text panel: its title for the suggestion strip ([header]) and one scrolling list
 * ([content]) in two sections. **Saved** is what the person chose to keep, newest first; **Recent
 * copies** is what was copied in the last hour and not saved, held in memory only. Tap a row to
 * insert it; its one button saves a recent copy or removes a saved item. A row over the list says
 * "Saved" after a save, offers Undo after a removal and says "Restored" after Undo, until the next
 * change or until the panel closes, and TalkBack hears it when it changes. [show] redraws it from
 * the lists as they are now.
 *
 * A redraw replaces the rows, which drops TalkBack's focus from whichever one had it, so the rows
 * are only rebuilt when a list has changed, and then focus is put somewhere on purpose: on Undo
 * after a removal, on the item after a save or an Undo, and otherwise back on the control it was
 * on, if that's still in the list.
 */
class SavedTextPanel(
    private val context: Context,
    private val onInsert: (String) -> Unit,
    private val onSave: (String) -> Unit,
    private val onRemove: (String) -> Unit,
    private val onUndo: () -> Unit,
) {
    private val metrics = context.resources.displayMetrics

    /** The panel's title, shown in the suggestion strip while it's open. */
    val header: TextView = TextView(context).apply {
        text = context.getString(R.string.saved_text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(context.getColor(R.color.kb_on_key))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(8), 0, dp(8), 0)
        maxLines = 1
        // The title also carries a notice for a few seconds; on a narrow phone a long one ends in an ellipsis.
        ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private val statusText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(context.getColor(R.color.kb_on_key))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // Polite: "Saved", "Removed" or "Restored" is spoken after what TalkBack is reading, without moving its focus.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private val undoButton = TextView(context).apply {
        text = context.getString(R.string.saved_text_undo)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(context.getColor(R.color.kb_accent))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minWidth = dp(UNDO_MIN_DP)
        setPadding(dp(12), 0, dp(12), 0)
        background = context.getDrawable(R.drawable.candidate_background)
        setOnClickListener {
            tap(it) {
                // The redraw that follows runs inside onUndo, and needs to know what came back.
                restoring = shownChange as? SavedTexts.Change.Removed
                onUndo()
                restoring = null
            }
        }
    }

    /** Fixed over the list rather than in it, so Undo shows however far down the removed row was. */
    private val statusRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(undoButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // The keyboard's side padding, so rows line up with the bar's keys below.
        setPadding(dp(3), 0, dp(3), dp(4))
    }

    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        addView(rows, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    /** The status row, then the list scrolling under it. */
    val content: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(statusRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(STATUS_DP)))
        addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** What the status row is showing, so a redraw for anything else doesn't announce it again. */
    private var shownChange: SavedTexts.Change? = null
    /** The removal Undo is putting back, set only while its redraw runs. */
    private var restoring: SavedTexts.Change.Removed? = null
    /** The status row says "Restored": Undo put an item back, and nothing has changed since. */
    private var restored = false
    /** Counts status texts, so one posted for the next frame is dropped if the row has moved on by then. */
    private var statusToken = 0

    /** A row's place in the list: its text, and whether it's in Saved or in Recent copies. */
    private data class RowKey(val text: String, val saved: Boolean)

    /** A drawn row's two controls: the preview that inserts, and Save or Remove. */
    private class RowViews(val insert: View, val button: View)

    /** The lists the rows were last built from; an unchanged list isn't rebuilt, so TalkBack's focus stays put. */
    private var drawnSaved: List<String>? = null
    private var drawnCopies: List<String>? = null
    private val drawnRows = HashMap<RowKey, RowViews>()

    /** For a panel that's opening: the list starts at the top, and a "Restored" from the last visit is gone. */
    fun prepareToOpen() {
        scroll.scrollTo(0, 0)
        restored = false
    }

    /** Redraws from [saved] and [copies] (recent copies not already saved), with the status row for [change]. */
    fun show(saved: List<String>, copies: List<String>, change: SavedTexts.Change?) {
        val undone = restoring
        val newChange = change != null && change !== shownChange
        // Remove all in settings changes the list without a Change; "Restored" no longer describes it.
        if (undone == null && saved != drawnSaved) restored = false
        showChange(change, restoredNow = undone != null)
        val changed = saved != drawnSaved || copies != drawnCopies
        val focused = if (changed) focusedControl() else null
        if (changed) rebuild(saved, copies)
        // Rebuilt rows are new views, so TalkBack's focus has gone from whichever control had it.
        val target = when {
            undone != null -> drawnRows[RowKey(undone.text, saved = true)]?.insert
            newChange && change is SavedTexts.Change.Removed -> undoButton
            newChange && change is SavedTexts.Change.Saved -> drawnRows[RowKey(change.text, saved = true)]?.insert
            focused != null -> drawnRows[focused.first]?.let { if (focused.second) it.button else it.insert }
            else -> null
        }
        TalkBackFocus.moveTo(target)
    }

    private fun rebuild(saved: List<String>, copies: List<String>) {
        drawnSaved = saved
        drawnCopies = copies
        drawnRows.clear()
        rows.removeAllViews()
        rows.addView(heading(R.string.saved_text_saved))
        if (saved.isEmpty()) rows.addView(note(R.string.saved_text_empty))
        saved.forEach { rows.addView(row(it, saved = true)) }
        if (copies.isEmpty()) return
        rows.addView(heading(R.string.saved_text_recent))
        copies.forEach { rows.addView(row(it, saved = false)) }
    }

    /** The row whose control TalkBack's focus is on, and whether it's the button; null when it's on none, or TalkBack is off. */
    private fun focusedControl(): Pair<RowKey, Boolean>? {
        if (!TalkBackFocus.isExploring(rows)) return null
        drawnRows.forEach { (key, views) ->
            if (TalkBackFocus.isOn(views.insert)) return key to false
            if (TalkBackFocus.isOn(views.button)) return key to true
        }
        return null
    }

    /** Shows "Saved", "Removed" and Undo, or "Restored" when [restoredNow] says this redraw is Undo's. */
    private fun showChange(change: SavedTexts.Change?, restoredNow: Boolean) {
        if (change != null) restored = false else if (restoredNow) restored = true
        val message = when {
            change is SavedTexts.Change.Removed -> R.string.saved_text_removed_status
            change != null -> R.string.saved_text_saved_status
            restored -> R.string.saved_text_restored_status
            else -> null
        }
        statusRow.visibility = if (message == null) View.GONE else View.VISIBLE
        undoButton.visibility = if (change is SavedTexts.Change.Removed) View.VISIBLE else View.GONE
        val announce = message != null && (change !== shownChange || restoredNow)
        shownChange = change
        if (message == null) statusToken++
        if (!announce) return
        val text = context.getString(message)
        // A live region speaks only while it's on screen, and holding the paste chip saves before the
        // panel shows, so the text goes in on the next frame. Emptied first, a second "Saved" in a
        // row is still a change.
        statusText.text = ""
        val token = ++statusToken
        statusText.post { if (statusToken == token) statusText.text = text }
    }

    /** A preview to tap and insert, with its Save or Remove button. */
    private fun row(text: String, saved: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val insert = TextView(context).apply {
            // Two lines at most; TalkBack reads the same preview, not up to 5,000 characters.
            this.text = SavedTexts.preview(text, PREVIEW_CHARS)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(context.getColor(R.color.kb_on_key))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(ROW_MIN_DP)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = context.getDrawable(R.drawable.panel_key_background)
            setOnClickListener { onInsert(text) }
            // "Double-tap to insert" rather than "to activate".
            ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.saved_text_insert), null)
        }
        addView(insert, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val label = SavedTexts.preview(text, LABEL_CHARS)
        val button = ImageView(context).apply {
            setImageResource(if (saved) R.drawable.ic_delete else R.drawable.ic_bookmark)
            scaleType = ImageView.ScaleType.CENTER
            background = context.getDrawable(R.drawable.candidate_background)
            contentDescription = context.getString(if (saved) R.string.saved_text_remove else R.string.saved_text_save, label)
            setOnClickListener { tap(it) { if (saved) onRemove(text) else onSave(text) } }
        }
        addView(button, LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))
        drawnRows[RowKey(text, saved)] = RowViews(insert, button)
    }

    /** A section name across the list, like the emoji panel's, and a heading for TalkBack. */
    private fun heading(label: Int) = TextView(context).apply {
        text = context.getString(label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(context.getColor(R.color.kb_hint))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(2))
        minHeight = dp(HEADING_MIN_DP)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun note(message: Int) = TextView(context).apply {
        text = context.getString(message)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(context.getColor(R.color.kb_hint))
        setPadding(dp(10), dp(4), dp(10), dp(8))
    }

    private fun tap(view: View, action: () -> Unit) {
        KeyHaptics.perform(view, HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }

    private fun dp(value: Int): Int = (value * metrics.density).toInt()

    private companion object {
        /** Enough for two lines on any phone; the row ellipsizes the rest. */
        const val PREVIEW_CHARS = 200
        /** How much of an item a Save or Remove label quotes. */
        const val LABEL_CHARS = 40
        const val ROW_MIN_DP = 52
        const val BUTTON_DP = 48
        const val STATUS_DP = 48
        const val UNDO_MIN_DP = 64
        const val HEADING_MIN_DP = 32
    }
}
