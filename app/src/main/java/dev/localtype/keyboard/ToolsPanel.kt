package dev.localtype.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.ceil

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
    private val icons = HashMap<StripTool, ImageView>()
    private val allowedNow = HashMap<StripTool, Boolean>()
    private var minimumTileWidth = dp(76)

    /** [tool]'s tile as last drawn, so TalkBack's focus can go back to it after a redraw; null when it isn't shown. */
    fun tileFor(tool: StripTool): View? = tiles[tool]

    /**
     * Redraws the tiles for this field: [sectionsNow] from [StripTools.panel], each tile dimmed unless [allowed], and
     * with a dot on its icon where [dot] (a password manager's chips waiting).
     */
    fun show(
        sectionsNow: List<Pair<StripTools.Group, List<StripTool>>>, allowed: (StripTool) -> Boolean, pinned: (StripTool) -> Boolean,
        dot: (StripTool) -> Boolean = { false },
    ) {
        val scroll = (content as ScrollView).scrollY
        sections.removeAllViews()
        tiles.clear()
        icons.clear()
        allowedNow.clear()
        minimumTileWidth = dp(76)
        sectionsNow.forEach { (group, tools) ->
            sections.addView(heading(group.label))
            val grid = ToolGrid()
            tools.forEach { tool ->
                val view = tile(tool, allowed(tool), pinned(tool), dot(tool))
                tiles[tool] = view
                grid.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            sections.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // A redraw after a hold keeps the place; a fresh open starts at the top ([scrollToTop]).
        content.post { (content as ScrollView).scrollTo(0, scroll) }
    }

    /** Puts the dot on [tool]'s icon or takes it off, in place, so TalkBack's focus stays where it is. */
    fun setDot(tool: StripTool, on: Boolean) {
        val tile = tiles[tool] ?: return
        icons[tool]?.setImageDrawable(ToolIcon.drawable(context, tool.icon, on))
        tile.contentDescription = ToolIcon.description(context, tool, allowedNow[tool] ?: true, on)
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

    private fun tile(tool: StripTool, allowed: Boolean, pinned: Boolean, dot: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(TILE_DP)
        setPadding(0, dp(6), 0, dp(6))
        background = context.getDrawable(R.drawable.panel_key_background)
        isClickable = true
        isFocusable = true
        alpha = if (allowed) 1f else DIMMED
        val label = context.getString(tool.label)
        contentDescription = ToolIcon.description(context, tool, allowed, dot)
        allowedNow[tool] = allowed
        ViewCompat.setStateDescription(this, if (pinned) context.getString(R.string.tool_in_row) else null)
        addView(ImageView(context).apply {
            setImageDrawable(ToolIcon.drawable(context, tool.icon, dot))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { icons[tool] = it })
        addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.kb_on_key))
            gravity = Gravity.CENTER
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setPadding(dp(4), dp(2), dp(4), 0)
            // Paint holds Android's actual sp conversion, including nonlinear font scaling. Reserve the pin for
            // every label so holding one tile never changes the column count. All sections use the same width.
            val wordWidth = label.split(Regex("\\s+")).maxOfOrNull { paint.measureText(it) } ?: 0f
            val pinWidth = ToolIcon.drawable(context, R.drawable.ic_pin, false)?.intrinsicWidth ?: dp(12)
            minimumTileWidth = maxOf(minimumTileWidth, ceil(wordWidth).toInt() + paddingLeft + paddingRight + pinWidth + dp(6))
            if (pinned) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pin, 0, 0, 0)
                compoundDrawablePadding = dp(2)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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

    /** Re-measure existing tiles when the panel width changes. No rebuilt views, lost focus or fixed row heights. */
    private inner class ToolGrid : ViewGroup(context) {
        private var columns = 1
        private val rowHeights = IntArray(StripTool.entries.size)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) minimumTileWidth * MAX_COLUMNS
                else MeasureSpec.getSize(widthMeasureSpec)
            columns = (width / minimumTileWidth.coerceAtLeast(1)).coerceIn(1, MAX_COLUMNS)
            rowHeights.fill(0)
            for (i in 0 until childCount) {
                val column = i % columns
                val cellWidth = width * (column + 1) / columns - width * column / columns
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                rowHeights[i / columns] = maxOf(rowHeights[i / columns], getChildAt(i).measuredHeight)
            }
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowHeights[i / columns], MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(width, resolveSize(rowHeights.sum(), heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            var y = 0
            for (i in 0 until childCount) {
                val column = i % columns
                if (column == 0 && i > 0) y += rowHeights[i / columns - 1]
                val start = width * column / columns
                val end = width * (column + 1) / columns
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - end else start
                getChildAt(i).layout(x, y, x + end - start, y + rowHeights[i / columns])
            }
        }
    }

    private companion object {
        const val MAX_COLUMNS = 4
        /** A generous minimum; wrapped or enlarged labels grow the row and the existing panel scrolls. */
        const val TILE_DP = 60
        const val DIMMED = 0.45f
    }
}

/**
 * A tool's icon, with a dot at its top end while a password manager's chips are waiting. The dot is a shape on the
 * icon, ringed in the strip's background, so it is not told by color alone, and the spoken label says it too.
 */
internal object ToolIcon {
    private const val DOT_DP = 9

    fun drawable(context: Context, icon: Int, dot: Boolean): Drawable? {
        val base = context.getDrawable(icon) ?: return null
        if (!dot) return base
        val mark = context.getDrawable(R.drawable.tool_dot) ?: return base
        val size = (DOT_DP * context.resources.displayMetrics.density).toInt()
        return LayerDrawable(arrayOf(base, mark)).apply {
            setLayerSize(1, size, size)
            setLayerGravity(1, Gravity.TOP or Gravity.END)
        }
    }

    /** What TalkBack says for [tool]'s tile or icon: its name, that the field forbids it, or that chips are waiting. */
    fun description(context: Context, tool: StripTool, allowed: Boolean, dot: Boolean): String {
        val label = context.getString(tool.label)
        return when {
            !allowed -> context.getString(R.string.tool_off_description, label)
            dot -> context.getString(R.string.tool_suggestions_waiting, label)
            else -> label
        }
    }
}
