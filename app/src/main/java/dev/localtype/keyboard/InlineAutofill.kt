package dev.localtype.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * A password manager's suggestions in the strip (Android 11 and later). Android asks the keyboard where chips
 * may go and how they should look, the manager answers, and Android draws each chip in a view this process
 * holds but cannot look inside: what a chip says and what a tap fills never pass through the keyboard. This
 * class asks, arranges the views it is handed and counts them, and nothing else.
 *
 * Only [request] and [response] touch API 30 types; the service creates one behind a version check.
 */
class InlineAutofill(
    private val context: Context,
    private val dp: (Int) -> Int,
    private val onChanged: () -> Unit,
    /** A chip was tapped. Android fills the field itself; this only hears that a tap happened, never what it filled. */
    private val onTapped: () -> Unit = {},
) {
    /** The answer being shown and its chips as Android hands them over. */
    private var answer = InlineAnswer<View>(emptyList())
    /**
     * Counts answers and give-ups. A chip's view arrives later, from Android: one that arrives for an older count
     * belongs to an answer that is gone, or to one that stopped waiting, and is dropped.
     */
    private var batch = 0
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable {
        batch++
        if (answer.giveUp()) onChanged()
    }

    /** Chips the current answer has or still expects: the row is theirs from the moment the answer arrives. */
    val count: Int get() = answer.count
    val unpinned: Int get() = answer.unpinned
    /** Chips Android has drawn so far: what the Passwords & cards panel can hold, and what its tile's dot means. */
    val drawn: Int get() = answer.ordered().size

    // What dumpsys prints. Counts and sizes only: there is nothing else here to print.
    var requests = 0
        private set
    var responses = 0
        private set
    var inflated = 0
        private set

    /** Each drawn chip's place in its row as left+width×height and the part of it shown, for dumpsys. */
    fun geometry(): String = answer.ordered().joinToString(" ") { (v, pin) ->
        "${v.left}+${v.width}x${v.height}" + (v.clipBounds?.let { "[${it.left},${it.right}]" } ?: "") + if (pin) "p" else ""
    }

    /** Where chips may go for the field being focused, styled like the strip. Chips from the last field are dropped. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun request(uiExtras: Bundle, heightPx: Int, dark: Boolean, textColor: Int, hintColor: Int): InlineSuggestionsRequest {
        requests++
        clear()
        val smallest = Size(dp(MIN_WIDTH_DP), heightPx)
        val largest = Size(dp(MAX_WIDTH_DP), heightPx)
        val builder = InlinePresentationSpec.Builder(smallest, largest)
        // Without a style the chips are Android's default ones, which ignore the keyboard's theme.
        if (UiVersions.INLINE_UI_VERSION_1 in UiVersions.getVersions(uiExtras)) builder.setStyle(style(dark, textColor, hintColor))
        val spec = builder.build()
        return InlineSuggestionsRequest.Builder(listOf(spec, spec, spec)).setMaxSuggestionCount(InlineRowRules.MAX_CHIPS).build()
    }

    /**
     * The manager's answer; an empty one takes the chips away. The chips of the answer before go at once, before
     * any of this one is drawn: Android 11 and 12 draw a suggestion they have drawn before onto the surface the
     * old view still holds, and the old view takes that surface with it when it leaves, so the two never overlap.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun response(response: InlineSuggestionsResponse): Boolean {
        responses++
        val suggestions = response.inlineSuggestions.take(InlineRowRules.MAX_CHIPS)
        show(suggestions.map { it.info.isPinned })
        if (suggestions.isEmpty()) return true
        val current = batch
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        suggestions.forEachIndexed { index, suggestion ->
            // Each suggestion can be drawn once.
            suggestion.inflate(context, size, context.mainExecutor) { view ->
                if (current != batch) return@inflate
                if (view != null) {
                    inflated++
                    // Android calls this after it has handled the tap; the listener learns only that one happened.
                    view.setOnClickListener { onTapped() }
                }
                answer.drawn(index, view)
                onChanged()
            }
        }
        // A row held open for chips that never come would hide the field's own strip.
        handler.postDelayed(giveUp, GIVE_UP_MS)
        return true
    }

    /** Drops every chip and any still being drawn: a new field, a hidden keyboard, or the setting turned off. */
    fun clear() {
        if (answer.count > 0 || answer.ordered().isNotEmpty()) show(emptyList()) else {
            batch++
            handler.removeCallbacks(giveUp)
        }
    }

    /**
     * Brings [row] to the chips drawn so far, pinned first. A chip already in place is left alone: taking one out
     * and putting it back makes Android release and redraw its surface. Chips still in a row that a rotation or a
     * theme change replaced are moved over.
     */
    fun attach(row: InlineChipRow) {
        attachTo(row.chips) { width, height -> LinearLayout.LayoutParams(width, height).apply { marginEnd = dp(CHIP_GAP_DP) } }
        if (answer.ordered().isEmpty()) row.scrollTo(0, 0)
    }

    /**
     * Brings [column], the Passwords & cards panel's place for the chips, to the chips drawn so far, moving them out
     * of the strip's row. Each suggestion can be drawn only once (Android refuses a second inflate), so the panel holds
     * the same views, never copies of them; [attach] puts them back when the panel closes.
     */
    fun attach(column: InlineChipColumn) {
        attachTo(column.flow) { width, height -> ViewGroup.LayoutParams(width, height) }
        if (answer.ordered().isEmpty()) column.scrollTo(0, 0)
    }

    /** [container] gets exactly the drawn chips in order, each at the size Android drew it, from wherever they were. */
    private fun attachTo(container: ViewGroup, params: (Int, Int) -> ViewGroup.LayoutParams) {
        val wanted = answer.ordered().map { it.first }
        for (index in container.childCount - 1 downTo 0) if (container.getChildAt(index) !in wanted) container.removeViewAt(index)
        wanted.forEachIndexed { index, view ->
            if (container.getChildAt(index) === view) return@forEachIndexed
            (view.parent as? ViewGroup)?.removeView(view)
            // Android hands each chip over with the size it drew it at; the view cannot measure its own content.
            val drawn = view.layoutParams
            container.addView(view, index, params(drawn?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT, drawn?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Starts over with an answer of [next] chips, none drawn yet; views still to arrive for the one before are dropped. */
    private fun show(next: List<Boolean>) {
        batch++
        handler.removeCallbacks(giveUp)
        for ((view, _) in answer.ordered()) (view.parent as? ViewGroup)?.removeView(view)
        answer = InlineAnswer(next)
        onChanged()
    }

    // The setters are the library's documented ones; they are inherited from a base class it marks as its own, which lint reports.
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun style(dark: Boolean, textColor: Int, hintColor: Int): Bundle {
        val background = Icon.createWithResource(context, if (dark) R.drawable.inline_chip_dark else R.drawable.inline_chip_light)
        val chip = ViewStyle.Builder().setBackground(background).setPadding(dp(12), 0, dp(12), 0).build()
        val icon = ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build()
        return UiVersions.newStylesBuilder().addStyle(
            InlineSuggestionUi.newStyleBuilder()
                .setChipStyle(chip)
                .setSingleIconChipStyle(ViewStyle.Builder().setBackground(background).setPadding(dp(8), 0, dp(8), 0).build())
                .setStartIconStyle(icon)
                .setEndIconStyle(icon)
                .setSingleIconChipIconStyle(icon)
                .setTitleStyle(TextViewStyle.Builder().setLayoutMargin(dp(6), 0, dp(6), 0).setTextColor(textColor).setTextSize(16f).build())
                .setSubtitleStyle(TextViewStyle.Builder().setLayoutMargin(0, 0, dp(6), 0).setTextColor(hintColor).setTextSize(14f).build())
                .build(),
        ).build()
    }

    private companion object {
        // The widths Android's own sample keyboard asks for, in dp.
        const val MIN_WIDTH_DP = 40
        const val MAX_WIDTH_DP = 280
        const val CHIP_GAP_DP = 6
        const val GIVE_UP_MS = 1_500L
    }
}

/**
 * The strip's row of chips. Each chip is a surface another process draws, so scrolling does not cut it off at
 * the row's edges the way it cuts off ordinary views: each one is told which part of itself is inside the row.
 */
class InlineChipRow(context: Context) : HorizontalScrollView(context) {
    val chips = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(chips, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        clip()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        clip()
    }

    private fun clip() {
        for (index in 0 until chips.childCount) {
            val chip = chips.getChildAt(index)
            val from = (scrollX - chip.left).coerceIn(0, chip.width)
            val to = (scrollX + width - chip.left).coerceIn(0, chip.width)
            chip.clipBounds = Rect(from, 0, to, chip.height)
        }
    }
}

/**
 * The Passwords & cards panel's chips: laid out in lines ([InlineChipFlow]) and scrolled up and down. As in
 * [InlineChipRow], each chip is a surface another process draws, which its parent does not cut off, so each one is told
 * which part of itself is inside the column.
 */
class InlineChipColumn(context: Context) : ScrollView(context) {
    val flow = InlineChipFlow(context)

    init {
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(flow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        clip()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        clip()
    }

    private fun clip() {
        for (index in 0 until flow.childCount) {
            val chip = flow.getChildAt(index)
            val top = flow.top + chip.top
            val from = (scrollY - top).coerceIn(0, chip.height)
            val to = (scrollY + height - top).coerceIn(0, chip.height)
            chip.clipBounds = Rect(0, from, chip.width, to)
        }
    }
}

/** Chips in lines from the start edge, each at the size it was drawn ([InlineRowRules.flow]), [gap] apart. */
class InlineChipFlow(context: Context) : ViewGroup(context) {
    private var placed = InlineRowRules.Flow(emptyList(), 0)

    var gap = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val sizes = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }.map { chip ->
            val params = chip.layoutParams
            val across = if (params.width > 0) MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST)
            val down = if (params.height > 0) MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            chip.measure(across, down)
            chip.measuredWidth to chip.measuredHeight
        }
        placed = InlineRowRules.flow(sizes, inner, gap)
        setMeasuredDimension(width, resolveSize(placed.height + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val inner = right - left - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }.zip(placed.places).forEach { (chip, place) ->
            val x = if (rtl) inner - place.first - chip.measuredWidth else place.first
            val l = paddingLeft + x
            val t = paddingTop + place.second
            chip.layout(l, t, l + chip.measuredWidth, t + chip.measuredHeight)
        }
    }
}
