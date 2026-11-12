package dev.localtype.keyboard

/**
 * What the strip's tools row and the Tools panel can run. [gesture] is the two-finger action that does the same
 * thing, so both share one implementation and one set of field rules. [group] places it in the panel.
 */
enum class StripTool(val id: String, val label: Int, val icon: Int, val group: StripTools.Group, val gesture: GestureAction? = null) {
    CLIPBOARD("clipboard", R.string.tool_clipboard, R.drawable.ic_paste, StripTools.Group.INSERT, GestureAction.SAVED_TEXT),
    PASSWORDS("passwords", R.string.tool_passwords, R.drawable.ic_passwords, StripTools.Group.INSERT),
    EMOJI("emoji", R.string.emoji_panel, R.drawable.ic_emoji, StripTools.Group.INSERT, GestureAction.EMOJI),
    SYMBOLS("symbols", R.string.symbol_tool_title, R.drawable.ic_emoji, StripTools.Group.INSERT),
    TEXT_EFFECTS("text_effects", R.string.text_effects_title, R.drawable.ic_text_case, StripTools.Group.EDIT),
    VOICE("voice", R.string.voice_input, R.drawable.ic_mic, StripTools.Group.INSERT),
    CONTACTS("contacts", R.string.tool_contacts, R.drawable.ic_contacts, StripTools.Group.INSERT),
    TODAY("today", R.string.tool_today, R.drawable.ic_today, StripTools.Group.INSERT),
    TIME_NOW("time_now", R.string.tool_time_now, R.drawable.ic_time, StripTools.Group.INSERT),
    UNDO("undo", R.string.tool_undo, R.drawable.ic_undo, StripTools.Group.EDIT, GestureAction.UNDO),
    SELECT_ALL("select_all", R.string.gesture_action_select_all, R.drawable.ic_select_all, StripTools.Group.EDIT, GestureAction.SELECT_ALL),
    CURSOR_START("cursor_start", R.string.tool_cursor_start, R.drawable.ic_cursor_start, StripTools.Group.EDIT, GestureAction.CURSOR_START),
    CURSOR_END("cursor_end", R.string.tool_cursor_end, R.drawable.ic_cursor_end, StripTools.Group.EDIT, GestureAction.CURSOR_END),
    REDO("redo", R.string.tool_redo, R.drawable.ic_redo, StripTools.Group.EDIT),
    ARROWS("arrows", R.string.tool_arrows, R.drawable.ic_arrows, StripTools.Group.EDIT),
    CHANGE_CASE("change_case", R.string.tool_change_case, R.drawable.ic_text_case, StripTools.Group.EDIT),
    COUNT("count", R.string.tool_count, R.drawable.ic_count, StripTools.Group.EDIT),
    CLEAN_LINKS("clean_links", R.string.tool_clean_links, R.drawable.ic_link, StripTools.Group.EDIT),
    PROOFREAD("proofread", R.string.tool_proofread, R.drawable.ic_apply, StripTools.Group.WRITE),
    REWRITE_SHORTER("rewrite_shorter", R.string.tool_rewrite_shorter, R.drawable.ic_rewrite_shorter, StripTools.Group.WRITE),
    REWRITE_FORMAL("rewrite_formal", R.string.tool_rewrite_formal, R.drawable.ic_rewrite_formal, StripTools.Group.WRITE),
    REWRITE_FRIENDLY("rewrite_friendly", R.string.tool_rewrite_friendly, R.drawable.ic_rewrite_friendly, StripTools.Group.WRITE),
    READ_ALOUD("read_aloud", R.string.tool_read_aloud, R.drawable.ic_read_aloud, StripTools.Group.WRITE),
    SEARCH_WEB("search_web", R.string.tool_search_web, R.drawable.ic_search, StripTools.Group.HAND_OFF),
    SEND_TO_APP("send_to_app", R.string.tool_send_to_app, R.drawable.ic_send, StripTools.Group.HAND_OFF),
    PRIVATE("private", R.string.tool_private, R.drawable.ic_private, StripTools.Group.KEYBOARD),
    ONE_HANDED("one_handed", R.string.tool_one_handed, R.drawable.ic_one_handed, StripTools.Group.KEYBOARD),
    SETTINGS("settings", R.string.tool_settings, R.drawable.ic_settings, StripTools.Group.KEYBOARD),
    HIDE("hide", R.string.gesture_action_hide, R.drawable.ic_hide_keyboard, StripTools.Group.KEYBOARD, GestureAction.HIDE_KEYBOARD),
}

/** Which tools fit in the row and which may run in a field. Pure, so the rules are tested. */
object StripTools {
    enum class Group(val label: Int) {
        INSERT(R.string.tools_group_insert),
        EDIT(R.string.tools_group_edit),
        WRITE(R.string.tools_group_write),
        HAND_OFF(R.string.tools_group_hand_off),
        KEYBOARD(R.string.tools_group_keyboard),
    }

    /**
     * The built-in row: the first ones that are offered in the field and fit, then Keyboard settings and More ([row]).
     * Holding a tile changes it,
     * and a row someone has changed is kept as they left it ([rowOrder]), so a tool added here never moves into it.
     */
    val ROW_ORDER = listOf(
        StripTool.CLIPBOARD, StripTool.PASSWORDS, StripTool.EMOJI, StripTool.VOICE, StripTool.UNDO, StripTool.PROOFREAD,
        StripTool.SELECT_ALL,
    )

    /** At most this many tools are pinned to the row; it shows only as many as fit. */
    const val MAX_PINNED = 8

    /** The row's tools from the saved ids: unknown and repeated ids are dropped; null (never changed) is [ROW_ORDER]. */
    fun rowOrder(saved: List<String>?): List<StripTool> {
        if (saved == null) return ROW_ORDER
        val byId = StripTool.entries.associateBy { it.id }
        return saved.mapNotNull { byId[it] }.distinct().take(MAX_PINNED)
    }

    /**
     * Holding a tile in the panel: a tool in the row comes out, any other goes in at the end. Null when the row is
     * already full, so the panel can say so.
     */
    fun toggle(order: List<StripTool>, tool: StripTool): List<StripTool>? = when {
        tool in order -> order - tool
        order.size >= MAX_PINNED -> null
        else -> order + tool
    }

    /** The editor's own undo and redo, asked through its text menu: what a standard text field and Chrome answer. */
    const val EDITOR_UNDO = android.R.id.undo
    const val EDITOR_REDO = android.R.id.redo

    /** Every cell in the row is 48 dp. */
    const val CELL_DP = 48

    /**
     * What the strip spends besides the row: its padding (6 + 4 dp) and the handle (4 + 48). The lead button is hidden
     * while the row shows, so its 52 dp go to the row.
     */
    const val STRIP_FIXED_DP = 10 + 52

    /** Cells the row has at a strip [widthDp] wide, More included: never fewer than two. */
    fun cells(widthDp: Int): Int = ((widthDp - STRIP_FIXED_DP) / CELL_DP).coerceAtLeast(2)

    /** What a field offers at all. A tool that is offered but not [allowed] is drawn dimmed and says why when tapped. */
    data class Field(
        val voice: Boolean,
        val proofread: Boolean,
        /** A password manager's chips can reach the keyboard at all: Android 11 and later ([PasswordsTile.available]). */
        val passwords: Boolean = false,
    )

    fun offered(tool: StripTool, field: Field): Boolean = when (tool) {
        StripTool.VOICE -> field.voice
        StripTool.PASSWORDS -> field.passwords
        // Rewriting asks the same on-device model as proofreading, so it goes where proofreading does.
        StripTool.PROOFREAD, StripTool.REWRITE_SHORTER, StripTool.REWRITE_FORMAL, StripTool.REWRITE_FRIENDLY -> field.proofread
        else -> true
    }

    /**
     * The tools the row shows before More, at a strip [widthDp] wide. Keyboard settings is always the last of them,
     * whatever the row's order holds, so the gear is one tap from the arrow in every field; the others fill the cells
     * before it.
     */
    fun row(widthDp: Int, field: Field, order: List<StripTool> = ROW_ORDER): List<StripTool> =
        order.filter { it != StripTool.SETTINGS && offered(it, field) }.take(cells(widthDp) - 2) + StripTool.SETTINGS

    /** The Tools panel's sections, in [Group] order, with the tools each offers in this field. */
    fun panel(field: Field): List<Pair<Group, List<StripTool>>> = Group.entries.map { group ->
        group to StripTool.entries.filter { it.group == group && offered(it, field) }
    }.filter { it.second.isNotEmpty() }

    /**
     * Whether [tool] may run now. A tool shared with the two-finger gestures follows the gesture's field rules, with the
     * gestures' own switch ignored: tools are always on. The rest need only a field.
     */
    fun allowed(tool: StripTool, state: GestureRules.State): Boolean {
        val gesture = tool.gesture
        // The panel is closed before a tool runs, so an open one never counts against it.
        val now = state.copy(enabled = true, panel = false)
        if (gesture != null) return GestureRules.allowed(gesture, now)
        // Redo goes where Undo goes, so a field never offers one without the other.
        if (tool == StripTool.REDO) return GestureRules.allowed(GestureAction.UNDO, now)
        return now.shown && now.connected && !now.emojiSearch && when (tool) {
            StripTool.PROOFREAD, StripTool.REWRITE_SHORTER, StripTool.REWRITE_FORMAL, StripTool.REWRITE_FRIENDLY ->
                !now.proofreading && !now.sensitive && !now.terminal
            // Speaking text out loud reads it: never a password, and nothing in a terminal.
            StripTool.READ_ALOUD -> !now.sensitive && !now.terminal
            // Reading the text to count or recase it: never a password.
            StripTool.TEXT_EFFECTS -> !now.sensitive && now.learning && !now.terminal
            StripTool.CHANGE_CASE, StripTool.COUNT -> !now.sensitive && !now.terminal
            // Cleaning a link only removes from it, but it reads the text: never a password or a terminal.
            StripTool.CLEAN_LINKS -> !now.sensitive && !now.terminal
            // The text leaves for another app, so only where the field lets the keyboard keep what's typed:
            // never a password, an incognito or private-for-now field, or a terminal.
            StripTool.SEARCH_WEB, StripTool.SEND_TO_APP -> !now.sensitive && now.learning && !now.terminal
            // A contact goes into any field but a password: it's the person's own choice of text, never learned.
            StripTool.CONTACTS -> !now.sensitive
            // Wherever the manager's chips can be, password fields above all. The panel shows views Android draws and
            // opens the manager's app; it reads nothing, so no field rule applies.
            StripTool.PASSWORDS -> true
            else -> true
        }
    }
}
