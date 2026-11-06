package dev.localtype.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

/**
 * Loads the emoji inventory once per process, off the main thread, keeping only emoji this phone's
 * fonts draw as a single glyph, so Android 8 never shows a box or a sequence in pieces. The check
 * asks the typeface the grid draws with. A font update only reaches a new process, which loads
 * again, so one load per process is one per system build and font set.
 */
internal object EmojiCatalog {
    private const val ASSET = "emoji_inventory.txt"

    /** What the grid's cells draw with, and so what the glyph check asks. */
    val typeface: Typeface get() = Typeface.DEFAULT

    @Volatile private var loaded: EmojiInventory? = null

    /** Calls [onReady] on the main thread with the inventory and how long loading took (0 when it was already loaded). */
    fun load(context: Context, onReady: (EmojiInventory, Long) -> Unit) {
        loaded?.let { return onReady(it, 0L) }
        val assets = context.applicationContext.assets
        val main = Handler(Looper.getMainLooper())
        thread(name = "localtype-emoji", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val startedAt = SystemClock.elapsedRealtime()
            // Each call lays out the whole sequence, so the 3,583 checks run here, never while typing.
            val paint = Paint().apply { typeface = EmojiCatalog.typeface }
            val inventory = runCatching {
                assets.open(ASSET).bufferedReader().useLines { lines -> EmojiInventory.parse(lines) { paint.hasGlyph(it) } }
            }.getOrDefault(EmojiInventory.EMPTY)
            // A failed load isn't kept, so the next keyboard start tries again.
            if (inventory.size > 0) loaded = inventory
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            main.post { onReady(inventory, elapsed) }
        }
    }
}

/**
 * The emoji panel: category tabs for the suggestion strip ([header]) and one continuous grid
 * ([grid]) of every emoji this phone can draw, Recent first, with no pages. Tapping a tab jumps to
 * its section, and scrolling moves the selected tab along. Tap an emoji to insert it; hold one that
 * has skin tones, or use TalkBack's Skin tones action, to pick from the base and its five tones in
 * [popup]. [show] refills it each time the panel opens. Until the inventory has loaded, or if it
 * didn't, [content] says so in place of the grid, and search says so in place of its matches.
 *
 * 🔍, first in the tab row, asks for search ([onSearch]). The service owns the query and its keys;
 * this draws [searchBar] in the strip, with the query, ✕ ([onSearchDone]) and the matches, which
 * insert and offer tones just as the grid's cells do.
 */
class EmojiPanel(
    private val context: Context,
    private val popup: SuggestionPopup,
    private val tones: EmojiTones,
    private val onInsert: (String) -> Unit,
    private val onSearch: () -> Unit,
    private val onSearchDone: () -> Unit,
) {
    private val metrics = context.resources.displayMetrics
    private val toneNames: Array<String> = context.resources.getStringArray(R.array.emoji_tone_names)
    private var inventory = EmojiInventory.EMPTY
    /** The inventory is still loading; with an empty one, that means wait rather than that it failed. */
    private var loading = false
    private var recent: List<String> = emptyList()
    private var layout = EmojiPanelLayout(emptyList())
    private val tabViews = LinkedHashMap<EmojiTab, TextView>()
    private var activeTab: EmojiTab? = null
    /** The tab just tapped, kept selected until a scroll starts, whatever the first visible row says. */
    private var jumpedTo: EmojiTab? = null

    private val tabRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** The category tabs, shown in the suggestion strip while the panel is open. */
    val header: HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        // Tabs share the strip when they fit and scroll when they don't.
        isFillViewport = true
        addView(tabRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private val manager = Grid()

    val grid: RecyclerView = RecyclerView(context).apply {
        layoutManager = manager
        adapter = Adapter()
        setHasFixedSize(true)
        itemAnimator = null
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // A drag, a fling or a TalkBack scroll hands the tab back to the scroll position.
                if (newState != RecyclerView.SCROLL_STATE_IDLE) jumpedTo = null
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (jumpedTo != null) return
                val first = manager.findFirstVisibleItemPosition()
                if (first != RecyclerView.NO_POSITION) select(layout.tabAt(first))
            }
        })
    }

    /** In place of the grid while there's nothing in it: the inventory is still loading, or didn't load. */
    private val unavailableNote = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(context.getColor(R.color.kb_on_key))
        gravity = Gravity.CENTER
        setPadding(dp(24), 0, dp(24), 0)
        visibility = View.GONE
        // Polite: "didn't load" is read if it replaces "Loading emoji…" while TalkBack is in the panel.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** What the panel shows over its bar: [grid], or the note while there's nothing in it. */
    val content: FrameLayout = FrameLayout(context).apply {
        addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(unavailableNote, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** True until an inventory with something in it has been shown. */
    val isEmpty: Boolean get() = layout.size == 0

    private val ready: Boolean get() = inventory.size > 0

    /** First in the tab row: the letters come back, and [searchBar] takes the strip. TalkBack's focus returns here when search ends. */
    val searchButton: TextView = TextView(context).apply {
        text = SEARCH_GLYPH
        typeface = EmojiCatalog.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_PX, glyphSize(TAB_GLYPH_SP, TAB_GLYPH_MAX_DP))
        gravity = Gravity.CENTER
        minWidth = dp(TAB_MIN_DP)
        contentDescription = context.getString(R.string.emoji_search)
        background = context.getDrawable(R.drawable.panel_tab_background)
        setOnClickListener { onSearch() }
    }

    /** The matches for the query now shown, best first. */
    private var found: List<EmojiInventory.Emoji> = emptyList()
    /** Built for one inventory and one strip list, and again only when either is replaced. */
    private var search: EmojiSearch? = null
    private var searchedInventory: EmojiInventory? = null
    private var searchedBoosts: Map<String, List<String>>? = null

    /**
     * What's been typed, or the hint. A live region, so TalkBack reads the query and the count as
     * they change. TalkBack's focus goes here when search starts, since 🔍 has gone.
     */
    val queryView: TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(context.getColor(R.color.kb_on_key))
        setHintTextColor(context.getColor(R.color.kb_hint))
        hint = context.getString(R.string.emoji_search)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(12), 0, dp(2), 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private val results = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter = ResultsAdapter()
        setHasFixedSize(true)
        itemAnimator = null
    }

    private val noResults = TextView(context).apply {
        text = context.getString(R.string.emoji_search_none)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(context.getColor(R.color.kb_hint))
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    /**
     * The suggestion strip while searching: the query and ✕ in a key-shaped field of fixed width, so
     * ✕ never moves, then the matches in one row that scrolls sideways. It starts GONE, so showing
     * it is the visibility change that has TalkBack announce its title.
     */
    val searchBar: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        ViewCompat.setAccessibilityPaneTitle(this, context.getString(R.string.emoji_search_title))
        val field = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.panel_key_background)
            addView(queryView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_dismiss)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = context.getString(R.string.emoji_search_close)
                background = context.getDrawable(R.drawable.candidate_background)
                setOnClickListener { onSearchDone() }
            }, LinearLayout.LayoutParams(dp(TAB_MIN_DP), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        addView(field, LinearLayout.LayoutParams(dp(SEARCH_FIELD_DP), LinearLayout.LayoutParams.MATCH_PARENT))
        val matches = FrameLayout(context).apply {
            addView(results, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(noResults, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(matches, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    /**
     * Shows [searchBar] with the matches for [query], best first, each in its effective skin tone.
     * [boosts] is the strip's word-to-emoji list, which [EmojiSearch] ranks first.
     */
    fun showSearch(query: String, boosts: Map<String, List<String>>) {
        found = searchOver(boosts).search(query)
        redrawResults()
        results.scrollToPosition(0)
        queryView.text = query
        val shown = query.trim()
        // With nothing loaded, no matches says nothing about the query.
        noResults.text = context.getString(
            when {
                ready -> R.string.emoji_search_none
                loading -> R.string.emoji_loading
                else -> R.string.emoji_load_failed
            },
        )
        noResults.visibility = if (!ready || (shown.isNotEmpty() && found.isEmpty())) View.VISIBLE else View.GONE
        queryView.contentDescription = when {
            !ready -> context.getString(
                if (loading) R.string.emoji_search_loading else R.string.emoji_search_load_failed,
                shown.ifEmpty { context.getString(R.string.emoji_search) },
            )
            shown.isEmpty() -> context.getString(R.string.emoji_search)
            found.isEmpty() -> context.getString(R.string.emoji_search_no_results, shown)
            else -> context.resources.getQuantityString(R.plurals.emoji_search_results, found.size, shown, found.size)
        }
        searchBar.visibility = View.VISIBLE
    }

    /** Hides and empties [searchBar], ready for the next search. */
    fun hideSearch() {
        searchBar.visibility = View.GONE
        found = emptyList()
        redrawResults()
        queryView.text = ""
        noResults.visibility = View.GONE
    }

    private fun searchOver(boosts: Map<String, List<String>>): EmojiSearch {
        search?.takeIf { searchedInventory === inventory && searchedBoosts === boosts }?.let { return it }
        searchedInventory = inventory
        searchedBoosts = boosts
        return EmojiSearch(inventory, boosts).also { search = it }
    }

    // A new default tone can change every match, as it does every cell.
    @SuppressLint("NotifyDataSetChanged")
    private fun redrawResults() {
        results.adapter?.notifyDataSetChanged()
    }

    /**
     * Fills the panel from [inventory], the [recent] list as it is now, and the current skin tones,
     * scrolled to the top: on Recent when it has anything. Recent stays put while the panel is
     * open, so the grid never shifts under the finger. An empty [inventory] shows "Loading emoji…"
     * while [loading], and otherwise that it didn't load.
     */
    fun show(inventory: EmojiInventory, recent: List<String>, loading: Boolean) {
        this.inventory = inventory
        this.loading = loading
        this.recent = recent
        rebuild()
        unavailableNote.text = if (ready) "" else context.getString(if (loading) R.string.emoji_loading else R.string.emoji_load_failed_retry)
        unavailableNote.visibility = if (ready) View.GONE else View.VISIBLE
        grid.visibility = if (ready) View.VISIBLE else View.INVISIBLE
        tabViews.clear()
        tabRow.removeAllViews()
        tabRow.addView(searchButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        layout.tabs.forEach { tab ->
            val view = tabView(tab)
            tabViews[tab] = view
            tabRow.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        jumpedTo = null
        activeTab = null
        grid.stopScroll()
        manager.scrollToPositionWithOffset(0, 0)
        header.scrollTo(0, 0)
        select(layout.tabs.firstOrNull())
    }

    // Every cell can change at once: a new default tone reaches them all.
    @SuppressLint("NotifyDataSetChanged")
    private fun rebuild() {
        layout = EmojiPanelLayout.build(inventory, recent, tones::display)
        grid.adapter?.notifyDataSetChanged()
    }

    private fun tabView(tab: EmojiTab) = TextView(context).apply {
        text = tab.glyph
        typeface = EmojiCatalog.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_PX, glyphSize(TAB_GLYPH_SP, TAB_GLYPH_MAX_DP))
        gravity = Gravity.CENTER
        minWidth = dp(TAB_MIN_DP)
        contentDescription = context.getString(tabLabel(tab))
        background = context.getDrawable(R.drawable.panel_tab_background)
        setOnClickListener { jumpTo(tab) }
    }

    /** Puts [tab]'s first section at the top at once; there are no pages to flip. */
    private fun jumpTo(tab: EmojiTab) {
        val position = layout.tabPosition(tab).takeIf { it >= 0 } ?: return
        grid.stopScroll()
        manager.scrollToPositionWithOffset(position, 0)
        jumpedTo = tab
        select(tab)
    }

    /** Marks [tab] selected (the state TalkBack reads, drawn with more than colour) and scrolls the tab row to show it. */
    private fun select(tab: EmojiTab?) {
        if (tab == activeTab) return
        activeTab = tab
        tabViews.forEach { (each, view) -> view.isSelected = each == tab }
        val view = tabViews[tab] ?: return
        header.post { reveal(view) }
    }

    private fun reveal(view: View) {
        val scroll = header.scrollX
        val target = when {
            view.left < scroll -> view.left
            view.right > scroll + header.width -> view.right - header.width
            else -> return
        }
        if (ValueAnimator.areAnimatorsEnabled()) header.smoothScrollTo(target, 0) else header.scrollTo(target, 0)
    }

    /** Offers the base and its five tones above [anchor]; the pick inserts and becomes the remembered tone. */
    private fun showTones(anchor: View, cell: EmojiPanelLayout.Cell?): Boolean {
        val emoji = cell?.emoji?.takeIf { it.tones.isNotEmpty() } ?: return false
        KeyHaptics.perform(anchor, HapticFeedbackConstants.LONG_PRESS)
        popup.show(
            anchor = anchor,
            options = listOf(emoji.value) + emoji.tones,
            describe = { describe(emoji, EmojiInventory.toneOf(it)) },
            title = context.getString(R.string.emoji_skin_tones_action),
            // The options run base, then tones 1 to 5, so the cell's own tone is the index of the one it shows.
            selected = EmojiInventory.toneOf(cell.text),
            onPick = { picked ->
                tones.choose(emoji, EmojiInventory.toneOf(picked))
                onInsert(picked)
                // The pick is also the new default, so every emoji without its own choice redraws.
                rebuild()
                redrawResults()
            },
        )
        return true
    }

    /** CLDR's name, plus the tone when there is one ("thumbs up, medium skin tone"). */
    private fun describe(emoji: EmojiInventory.Emoji, tone: Int): String =
        toneNames.getOrNull(tone - 1)?.let { context.getString(R.string.emoji_with_tone, emoji.name, it) } ?: emoji.name

    private fun title(group: String?): String = when (group) {
        null -> R.string.emoji_recent
        "Smileys & Emotion" -> R.string.emoji_smileys
        "People & Body" -> R.string.emoji_people
        "Animals & Nature" -> R.string.emoji_animals
        "Food & Drink" -> R.string.emoji_food
        "Travel & Places" -> R.string.emoji_travel
        "Activities" -> R.string.emoji_activities
        "Objects" -> R.string.emoji_objects
        "Symbols" -> R.string.emoji_symbols
        "Flags" -> R.string.emoji_flags
        else -> null
    }?.let(context::getString) ?: group.orEmpty().replace("&", "and")

    private fun tabLabel(tab: EmojiTab): Int = when (tab) {
        EmojiTab.RECENT -> R.string.emoji_recent
        EmojiTab.SMILEYS -> R.string.emoji_smileys_and_people
        EmojiTab.ANIMALS -> R.string.emoji_animals
        EmojiTab.FOOD -> R.string.emoji_food
        EmojiTab.TRAVEL -> R.string.emoji_travel
        EmojiTab.ACTIVITIES -> R.string.emoji_activities
        EmojiTab.OBJECTS -> R.string.emoji_objects
        EmojiTab.SYMBOLS -> R.string.emoji_symbols
        EmojiTab.FLAGS -> R.string.emoji_flags
    }

    /** A glyph that grows with the font size setting but stays inside its cell. */
    private fun glyphSize(sp: Float, maxDp: Float): Float =
        minOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics), maxDp * metrics.density)

    private fun dp(value: Int): Int = (value * metrics.density).toInt()

    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text) {
        /** The Skin tones action this cell carries, removed when it's bound to an emoji without tones. */
        var tonesAction = View.NO_ID
    }

    private fun cellOf(holder: Holder): EmojiPanelLayout.Cell? =
        holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(layout::cellAt)

    /** A match as a cell: shown, inserted and offered in its effective tone, as the grid does. */
    private fun resultAt(position: Int): EmojiPanelLayout.Cell? =
        found.getOrNull(position)?.let { EmojiPanelLayout.Cell(tones.display(it), it) }

    private fun resultOf(holder: Holder): EmojiPanelLayout.Cell? =
        holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::resultAt)

    /** Tap inserts the cell [current] finds for the holder; holding one with tones offers them. */
    private fun listen(holder: Holder, current: () -> EmojiPanelLayout.Cell?) {
        holder.text.setOnClickListener { current()?.let { onInsert(it.text) } }
        KeyHaptics.onHold(holder.text) { view -> showTones(view, current()) }
    }

    /** Puts [cell] in [holder]: its glyph, its spoken name and, when it has tones, the hold and the Skin tones action. */
    private fun bindCell(holder: Holder, cell: EmojiPanelLayout.Cell, current: () -> EmojiPanelLayout.Cell?) {
        val view = holder.text
        view.text = cell.text
        view.contentDescription = describe(cell.emoji, EmojiInventory.toneOf(cell.text))
        val toned = cell.emoji.tones.isNotEmpty()
        view.isLongClickable = toned
        if (holder.tonesAction != View.NO_ID) {
            ViewCompat.removeAccessibilityAction(view, holder.tonesAction)
            holder.tonesAction = View.NO_ID
        }
        if (toned) {
            holder.tonesAction = ViewCompat.addAccessibilityAction(view, context.getString(R.string.emoji_skin_tones_action)) { target, _ ->
                showTones(target, current())
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = layout.size

        override fun getItemViewType(position: Int): Int = if (layout.isHeader(position)) HEADER else CELL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == HEADER) return Holder(headerView())
            return Holder(cellView()).also { holder -> listen(holder) { cellOf(holder) } }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (holder.itemViewType == HEADER) {
                holder.text.text = title(layout.sections[layout.sectionAt(position)].group)
                return
            }
            layout.cellAt(position)?.let { cell -> bindCell(holder, cell) { cellOf(holder) } }
        }
    }

    /** The matches in the search bar, one row that scrolls sideways. */
    private inner class ResultsAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = found.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = cellView(TAB_GLYPH_SP, TAB_GLYPH_MAX_DP).apply {
                layoutParams = RecyclerView.LayoutParams(dp(TAB_MIN_DP), RecyclerView.LayoutParams.MATCH_PARENT)
            }
            return Holder(view).also { holder -> listen(holder) { resultOf(holder) } }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            resultAt(position)?.let { cell -> bindCell(holder, cell) { resultOf(holder) } }
        }
    }

    private fun headerView() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(context.getColor(R.color.kb_hint))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(2))
        minHeight = dp(HEADER_MIN_DP)
        ViewCompat.setAccessibilityHeading(this, true)
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
    }

    private fun cellView(glyphSp: Float = CELL_GLYPH_SP, glyphMaxDp: Float = CELL_GLYPH_MAX_DP) = TextView(context).apply {
        typeface = EmojiCatalog.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_PX, glyphSize(glyphSp, glyphMaxDp))
        setTextColor(context.getColor(R.color.kb_on_key))
        gravity = Gravity.CENTER
        maxLines = 1
        background = context.getDrawable(R.drawable.candidate_background)
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, dp(EmojiPanelLayout.CELL_DP))
    }

    /**
     * Fits as many 48 dp columns as the width allows, with headings across the
     * row. RecyclerView no longer fills in grid and heading semantics for screen readers, so the
     * row and column of every cell come from [EmojiPanelLayout] here.
     */
    private inner class Grid : GridLayoutManager(context, EmojiPanelLayout.INITIAL_COLUMNS) {
        init {
            spanSizeLookup = object : SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (layout.isHeader(position)) spanCount else 1
            }.apply { isSpanIndexCacheEnabled = true }
        }

        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
            if (width > 0) EmojiPanelLayout.columns(width, metrics.density).let { if (it != spanCount) spanCount = it }
            super.onLayoutChildren(recycler, state)
        }

        override fun onInitializeAccessibilityNodeInfo(
            recycler: RecyclerView.Recycler, state: RecyclerView.State, info: AccessibilityNodeInfoCompat,
        ) {
            super.onInitializeAccessibilityNodeInfo(recycler, state, info)
            info.setCollectionInfo(CollectionInfoCompat.obtain(layout.rowCount(spanCount), spanCount, false, CollectionInfoCompat.SELECTION_MODE_NONE))
        }

        override fun onInitializeAccessibilityNodeInfoForItem(
            recycler: RecyclerView.Recycler, state: RecyclerView.State, host: View, info: AccessibilityNodeInfoCompat,
        ) {
            val position = getPosition(host)
            if (position !in 0 until layout.size) return super.onInitializeAccessibilityNodeInfoForItem(recycler, state, host, info)
            val heading = layout.isHeader(position)
            info.setCollectionItemInfo(
                CollectionItemInfoCompat.obtain(
                    layout.rowOf(position, spanCount), 1,
                    layout.columnOf(position, spanCount), if (heading) spanCount else 1,
                    heading, false,
                ),
            )
        }
    }

    private companion object {
        const val HEADER = 0
        const val CELL = 1
        const val CELL_GLYPH_SP = 26f
        const val CELL_GLYPH_MAX_DP = 36f
        const val TAB_GLYPH_SP = 22f
        const val TAB_GLYPH_MAX_DP = 30f
        const val TAB_MIN_DP = 48
        const val HEADER_MIN_DP = 32
        const val SEARCH_GLYPH = "🔍"
        /** Keep query room while giving ✕ a 48 dp target; matches scroll in the remaining strip. */
        const val SEARCH_FIELD_DP = 140
    }
}
