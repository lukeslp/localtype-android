package dev.localtype.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * The arrow pad: nine large keys over the keyboard for moving the caret and selecting, for anyone who finds the Space
 * slide fiddly. Every key is a tap; nothing repeats on hold, so a long press never overshoots. Select stays on until
 * it is tapped again or the panel closes. [onMove] sends the key; this view only draws.
 */
class ArrowPadPanel(private val context: Context, private val onMove: (ArrowMove, Boolean) -> Unit) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    /** Whether the moves extend a selection. */
    var selecting = false
        private set

    val header: TextView = TextView(context).apply {
        text = context.getString(R.string.tool_arrows)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(context.getColor(R.color.kb_on_key))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(8), 0, dp(8), 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private var selectKey: TextView? = null

    val content: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
        ArrowPad.GRID.chunked(ArrowPad.COLUMNS).forEach { line ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEach { move -> row.addView(key(move), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)) }
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** Opening the pad starts with selecting off. */
    fun reset() {
        selecting = false
        drawSelect()
    }

    private fun key(move: ArrowMove) = TextView(context).apply {
        text = move.glyph ?: context.getString(move.label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (move.ctrl || move == ArrowMove.SELECT) 16f else 24f)
        setTextColor(context.getColor(R.color.kb_on_key))
        gravity = Gravity.CENTER
        maxLines = 2
        minHeight = dp(56)
        background = context.getDrawable(if (move.ctrl || move == ArrowMove.SELECT) R.drawable.panel_modifier_background else R.drawable.panel_key_background)
        contentDescription = context.getString(move.label)
        setOnClickListener {
            if (move == ArrowMove.SELECT) {
                selecting = !selecting
                drawSelect()
                announceForAccessibility(context.getString(if (selecting) R.string.arrow_selecting else R.string.arrow_not_selecting))
            } else onMove(move, selecting)
        }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
                if (move == ArrowMove.SELECT) {
                    info.isCheckable = true
                    info.isChecked = selecting
                }
            }
        }
        if (move == ArrowMove.SELECT) selectKey = this
    }

    private fun drawSelect() {
        selectKey?.isSelected = selecting
        selectKey?.setTextColor(context.getColor(if (selecting) R.color.kb_accent else R.color.kb_on_key))
        // Not by color alone: a check mark goes before the label while selecting. The key keeps its cell's size.
        val label = context.getString(R.string.arrow_select)
        selectKey?.text = if (selecting) "✓ $label" else label
    }
}
