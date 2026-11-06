package dev.localtype.keyboard

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * A small row of alternatives above a suggestion slot or an emoji, for holding the emoji suggestion
 * to pick a variation or an emoji to pick a skin tone. Options are tappable views rather than a
 * slide-to-select overlay, so a screen reader can reach them. It's a window of its own, which
 * TalkBack's swipes wouldn't enter from the control that opened it, so TalkBack's focus moves to
 * the current option when it opens and back to that control after a pick.
 */
class SuggestionPopup(private val context: Context) {
    private var window: PopupWindow? = null

    val isShowing: Boolean get() = window?.isShowing == true

    /**
     * Shows [options] above [anchor], titled [title] for TalkBack. [selected] is the index of the
     * option in use (a skin tone), drawn and read as selected, or -1 when none is. [words] draws each
     * option as wide as its text, for names rather than emoji (the language chooser). Options that
     * don't fit across the screen continue on another row, whole, rather than being squeezed.
     */
    fun show(
        anchor: View,
        options: List<String>,
        describe: (String) -> String,
        title: String,
        selected: Int = -1,
        words: Boolean = false,
        onPick: (String) -> Unit,
    ) {
        dismiss()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = context.getDrawable(R.drawable.popup_background)
            elevation = 6f * density
            ViewCompat.setAccessibilityPaneTitle(this, title)
        }
        val cells = options.mapIndexed { index, option ->
            TextView(context).apply {
                text = option
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (words) 16f else 22f)
                // At a large font size a name grows taller than the cell rather than being cut off, and the
                // vertical padding keeps it clear of the selected tab's bar, which sits 6dp above the bottom.
                if (words) setPadding(dp(14), dp(10), dp(14), dp(10))
                minHeight = dp(46)
                setTextColor(context.getColor(R.color.kb_on_key))
                // An emoji tab's background: selected is a key-shaped fill with a bar, not colour alone.
                background = context.getDrawable(R.drawable.panel_tab_background)
                isSelected = index == selected
                contentDescription = describe(option)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismiss()
                    onPick(option)
                    TalkBackFocus.moveTo(anchor)
                }
            }
        }
        val screenWidth = context.resources.displayMetrics.widthPixels
        val widths = cells.map { cell ->
            if (!words) return@map dp(46)
            cell.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            cell.measuredWidth
        }
        // The screen less the popup's 4dp margins and its own 4dp padding, each side.
        for (range in PopupRows.pack(widths, screenWidth - dp(16))) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (index in range) {
                row.addView(cells[index], LinearLayout.LayoutParams(
                    if (words) LinearLayout.LayoutParams.WRAP_CONTENT else dp(46),
                    if (words) LinearLayout.LayoutParams.WRAP_CONTENT else dp(46)))
            }
            panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        panel.measure(View.MeasureSpec.makeMeasureSpec(screenWidth - dp(8), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val shownAt = android.os.SystemClock.uptimeMillis()
        // Not focusable: a focusable popup takes window focus from the text field, which restarts
        // input and closes the popup. Touches still reach it.
        val popup = PopupWindow(panel, panel.measuredWidth, panel.measuredHeight, false).apply {
            // Touch outside closes it; the keyboard underneath keeps working.
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            isClippingEnabled = false
            // The finger that held the suggestion is still down: ignore its release, which would
            // otherwise count as a touch outside and close the popup immediately.
            setTouchInterceptor { _, event ->
                event.action == android.view.MotionEvent.ACTION_OUTSIDE &&
                    android.os.SystemClock.uptimeMillis() - shownAt < IGNORE_RELEASE_MS
            }
        }
        window = popup
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val x = (location[0] + anchor.width / 2 - panel.measuredWidth / 2).coerceIn(dp(4), maxOf(dp(4), screenWidth - panel.measuredWidth - dp(4)))
        // Its bottom stays 2dp above the anchor's top however many rows it has; one row is 54dp, as before.
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, location[1] - panel.measuredHeight - dp(2))
        TalkBackFocus.moveTo(cells.getOrNull(if (selected in options.indices) selected else 0))
    }

    private companion object {
        const val IGNORE_RELEASE_MS = 400L
    }

    fun dismiss() {
        window?.let { runCatching { it.dismiss() } }
        window = null
    }
}

/** How a popup's options share rows: in order, as many to a row as fit, so none is squeezed or cut off. */
internal object PopupRows {
    /** Index ranges, one per row, for options [widths] wide in a row [available] wide; one wider than that has a row to itself. */
    fun pack(widths: List<Int>, available: Int): List<IntRange> {
        val rows = mutableListOf<IntRange>()
        var start = 0
        var used = 0
        widths.forEachIndexed { index, width ->
            if (index > start && used + width > available) {
                rows += start until index
                start = index
                used = 0
            }
            used += width
        }
        if (widths.isNotEmpty()) rows += start until widths.size
        return rows
    }
}
