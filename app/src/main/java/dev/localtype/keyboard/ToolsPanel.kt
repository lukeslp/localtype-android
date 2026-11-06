package dev.localtype.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * The Tools panel: every [StripTool] the field offers, in labelled sections of large tiles, over the keys. Its title
 * goes in the suggestion strip ([header]). A tile the field forbids is drawn dimmed but still answers a tap, so it
 * can say why. Holding a tile puts it in the tools row or takes it out ([onHold]); a tile in the row carries a pin.
 * Nothing here reads or edits the field: [onTool] does.
 */
class ToolsPanel(
    private val context: Context, private val onTool: (StripTool) -> Unit, private val onHold: (StripTool) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    val header: TextView = TextView(context).apply {
        text = context.getString(R.string.tools_title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(context.getColor(R.color.kb_on_key))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(8), 0, dp(8), 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private val sections = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(6))
    }

    val content: View = ScrollView(context).apply {
        isFillViewport = true
        // A tile cut off at the bottom fades, so it reads as more below rather than as hidden under the bar.
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(16))
        addView(sections)
    }

    private val tiles = HashMap<StripTool, View>()

    /** [tool]'s tile as last drawn, so TalkBack's focus can go back to it after a redraw; null when it isn't shown. */
    fun tileFor(tool: StripTool): View? = tiles[tool]

    /** Redraws the tiles for this field: [sectionsNow] from [StripTools.panel], each tile dimmed unless [allowed]. */
    fun show(sectionsNow: List<Pair<StripTools.Group, List<StripTool>>>, allowed: (StripTool) -> Boolean, pinned: (StripTool) -> Boolean) {
        val scroll = (content as ScrollView).scrollY
        sections.removeAllViews()
        tiles.clear()
        sectionsNow.forEach { (group, tools) ->
            sections.addView(heading(group.label))
            tools.chunked(COLUMNS).forEach { line ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                line.forEach { row.addView(tile(it, allowed(it), pinned(it)).also { view -> tiles[it] = view }, LinearLayout.LayoutParams(0, dp(TILE_DP), 1f)) }
                // A short last line keeps its tiles the width of the others.
                repeat(COLUMNS - line.size) { row.addView(View(context), LinearLayout.LayoutParams(0, dp(TILE_DP), 1f)) }
                sections.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        // A redraw after a hold keeps the place; a fresh open starts at the top ([scrollToTop]).
        content.post { (content as ScrollView).scrollTo(0, scroll) }
    }

    /** Posted, so it runs after the place that [show] restores, which is posted too. */
    fun scrollToTop() {
        content.post { (content as ScrollView).scrollTo(0, 0) }
    }

    private fun heading(label: Int) = TextView(context).apply {
        text = context.getString(label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(context.getColor(R.color.kb_hint))
        isAllCaps = true
        letterSpacing = 0.06f
        setPadding(dp(6), dp(4), dp(6), dp(2))
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun tile(tool: StripTool, allowed: Boolean, pinned: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = context.getDrawable(R.drawable.panel_key_background)
        isClickable = true
        isFocusable = true
        alpha = if (allowed) 1f else DIMMED
        val label = context.getString(tool.label)
        contentDescription = if (allowed) label else context.getString(R.string.tool_off_description, label)
        ViewCompat.setStateDescription(this, if (pinned) context.getString(R.string.tool_in_row) else null)
        addView(ImageView(context).apply { setImageResource(tool.icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.kb_on_key))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(2), dp(4), 0)
            if (pinned) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pin, 0, 0, 0)
                compoundDrawablePadding = dp(2)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        setOnClickListener { onTool(tool) }
        KeyHaptics.onHold(this) { onHold(tool); true }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
                // TalkBack names the hold for what it does here.
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK,
                    context.getString(if (pinned) R.string.tool_unpin else R.string.tool_pin)))
            }
        }
    }

    private companion object {
        const val COLUMNS = 4
        /**
         * Well past 48 dp and room for a two-line label, yet short enough that on a phone the first two sections'
         * first rows fit above the panel's bar (about 180 dp there) without scrolling.
         */
        const val TILE_DP = 60
        const val DIMMED = 0.45f
    }
}
