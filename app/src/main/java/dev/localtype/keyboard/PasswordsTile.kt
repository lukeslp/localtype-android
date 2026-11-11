package dev.localtype.keyboard

/**
 * The Passwords & cards tool: when it is offered, what its panel says and what its button opens, from counts and the
 * system's autofill setting only. The manager decides what to offer, through the inline chips Android draws; the
 * keyboard never asks for a fill and never sees what a chip holds. Pure, so the rules are tested without a phone.
 */
object PasswordsTile {
    /** Android 11 (API 30) is the first to hand a keyboard inline chips; before it the tile is hidden. */
    const val MIN_SDK = 30

    fun available(sdk: Int): Boolean = sdk >= MIN_SDK

    /** What the panel shows. */
    enum class State {
        /** Chips are drawn; the panel holds them. */
        CHIPS,
        /** The manager answered and Android is still drawing its chips. */
        WAITING,
        /** Nothing was offered for this field. */
        NONE,
        /** The keyboard asks for no chips (the setting is off), so the manager shows its own list under the field. */
        OFF,
        /** With TalkBack on, Android gives the manager's list to the field instead of to the keyboard. */
        TALKBACK,
        /** No autofill service is selected on the phone. */
        NO_MANAGER,
    }

    /**
     * @param drawn chips Android has drawn for this field
     * @param expected chips the latest answer still has or expects, drawn or not
     * @param inlineOn the keyboard asks for chips (Password manager suggestions in settings)
     * @param touchExploration TalkBack or another screen reader is exploring by touch
     * @param manager the autofill service's package, or null when none is selected
     */
    fun state(drawn: Int, expected: Int, inlineOn: Boolean, touchExploration: Boolean, manager: String?): State = when {
        drawn > 0 -> State.CHIPS
        !inlineOn -> State.OFF
        touchExploration -> State.TALKBACK
        manager == null -> State.NO_MANAGER
        expected > 0 -> State.WAITING
        else -> State.NONE
    }

    /** The dot on the tile's icon: chips are waiting for this field, whether or not the strip is showing them. */
    fun dot(drawn: Int): Boolean = drawn > 0

    /**
     * The manager's package from the secure setting `autofill_service`, a flattened component name such as
     * `com.example.vault/.Autofill`; null when it is unset, empty or not a component.
     */
    fun managerPackage(setting: String?): String? {
        val value = setting?.trim().orEmpty()
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.length - 1) return null
        val name = value.substring(0, slash)
        return name.takeIf { PACKAGE.matches(it) }
    }

    /** Where the panel's button goes. */
    enum class Opens { MANAGER_APP, AUTOFILL_SETTINGS }

    /** The manager's own app when it has one to open; otherwise Android's choice of autofill service. */
    fun opens(manager: String?, launchable: Boolean): Opens =
        if (manager != null && launchable) Opens.MANAGER_APP else Opens.AUTOFILL_SETTINGS

    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
}
