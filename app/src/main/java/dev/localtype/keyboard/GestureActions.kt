package dev.localtype.keyboard

/** What a two-finger swipe can do. [id] is the saved value and [label] its name in settings, notices and TalkBack. */
enum class GestureAction(val id: String, val label: Int) {
    NONE("none", R.string.gesture_action_none),
    DELETE_WORD("delete_word", R.string.key_delete_word),
    UNDO("undo", R.string.gesture_action_undo),
    CURSOR_START("cursor_start", R.string.gesture_action_cursor_start),
    CURSOR_END("cursor_end", R.string.gesture_action_cursor_end),
    SELECT_ALL("select_all", R.string.gesture_action_select_all),
    PASTE("paste", R.string.gesture_action_paste),
    HIDE_KEYBOARD("hide_keyboard", R.string.gesture_action_hide),
    EMOJI("emoji", R.string.gesture_action_emoji),
    SAVED_TEXT("saved_text", R.string.gesture_action_saved),
    SYMBOLS("symbols", R.string.gesture_action_symbols),
    CAPS_LOCK("caps_lock", R.string.gesture_action_caps_lock);

    companion object {
        fun of(id: String?): GestureAction? = entries.firstOrNull { it.id == id }
    }
}

/** Which action each swipe has, and where each action may run. Pure, so the field rules are tested. */
object GestureRules {
    val DEFAULTS: Map<TwoFingerSwipe, GestureAction> = mapOf(
        TwoFingerSwipe.LEFT to GestureAction.DELETE_WORD,
        TwoFingerSwipe.RIGHT to GestureAction.UNDO,
        TwoFingerSwipe.UP to GestureAction.SAVED_TEXT,
        TwoFingerSwipe.DOWN to GestureAction.HIDE_KEYBOARD,
    )

    /** The saved choice for [swipe], or its default when nothing (or something this build does not know) is saved. */
    fun action(saved: String?, swipe: TwoFingerSwipe): GestureAction = GestureAction.of(saved) ?: DEFAULTS.getValue(swipe)

    /** What the keyboard and its field are doing, as far as gestures care. */
    data class State(
        val enabled: Boolean,
        val shown: Boolean = true,
        val connected: Boolean = true,
        val panel: Boolean = false,
        val emojiSearch: Boolean = false,
        val proofreading: Boolean = false,
        val touchExploration: Boolean = false,
        val sensitive: Boolean = false,
        val learning: Boolean = true,
        val terminal: Boolean = false,
        val letters: Boolean = true,
    )

    /**
     * Whether two fingers on the keys may be read as a swipe. Touch exploration owns two-finger swipes (the actions are
     * on the space key there instead), and a panel, emoji search or proofreading owns the strip and the keys.
     */
    fun watches(state: State): Boolean = state.enabled && state.shown && state.connected && !state.panel &&
        !state.emojiSearch && !state.proofreading && !state.touchExploration

    /** The actions that read text (the field's, or the clipboard's): none of them in a password or no-learning field. */
    fun readsText(action: GestureAction): Boolean =
        action == GestureAction.DELETE_WORD || action == GestureAction.UNDO || action == GestureAction.PASTE

    /** Whether [action] may run in [state], from a swipe or from TalkBack's action on the space key. */
    fun allowed(action: GestureAction, state: State): Boolean {
        if (!state.enabled || !state.shown || !state.connected || state.panel || state.emojiSearch || state.proofreading) return false
        if (readsText(action) && (state.sensitive || !state.learning || state.terminal)) return false
        return when (action) {
            GestureAction.NONE -> false
            // A field that takes raw keys has no text to measure words or place a caret on.
            GestureAction.CURSOR_START, GestureAction.CURSOR_END, GestureAction.SELECT_ALL -> !state.terminal
            GestureAction.CAPS_LOCK -> state.letters
            else -> true
        }
    }

    /** The swipes to offer as TalkBack actions, one per distinct allowed action, in the order of [TwoFingerSwipe]. */
    fun offered(actions: Map<TwoFingerSwipe, GestureAction>, state: State): List<Pair<TwoFingerSwipe, GestureAction>> {
        val seen = HashSet<GestureAction>()
        return TwoFingerSwipe.entries.mapNotNull { swipe ->
            val action = actions[swipe] ?: return@mapNotNull null
            if (allowed(action, state) && seen.add(action)) swipe to action else null
        }
    }
}
