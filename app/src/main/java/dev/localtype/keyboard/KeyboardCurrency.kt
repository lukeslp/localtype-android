package dev.localtype.keyboard

/** Currency on the keys, chosen independently of English spelling. */
enum class KeyboardCurrency {
    FOLLOW_SPELLING, DOLLAR, POUND;

    /** The default preserves existing American/$ and British/£ layouts without migrating preferences. */
    fun poundFirst(british: Boolean): Boolean = when (this) {
        FOLLOW_SPELLING -> british
        DOLLAR -> false
        POUND -> true
    }

    companion object {
        fun of(name: String?): KeyboardCurrency = entries.firstOrNull { it.name == name } ?: FOLLOW_SPELLING
    }
}
