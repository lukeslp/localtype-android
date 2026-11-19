package dev.localtype.keyboard

/**
 * Which language a spell-checker session checks. Android hands the session a locale, one of the checker's own
 * subtypes ("en", "es", "pt"). On Android 10 and later that follows the phone's language, or the language the
 * person chose for the spell checker. On Android 8.0 to 9 (API 26 to 28), when the spell checker's language is
 * left on automatic, Android takes it from the current keyboard's subtype first and the phone's language only after
 * (`TextServicesManagerService.getCurrentSpellCheckerSubtype`). LocalType's keyboard declares one subtype, `en_US`,
 * so with LocalType as the keyboard those versions would check every phone's text as English. [choose] undoes that
 * one case by doing what Android does after the keyboard: it takes the phone's language.
 */
object SpellCheckLanguage {
    /** The Android versions whose automatic spell-checker language follows the current keyboard's subtype. */
    fun keyboardDecides(sdk: Int): Boolean = sdk in 26..28

    /** Whether Android's current input method ("package/.Service", Settings.Secure.DEFAULT_INPUT_METHOD) is this app's. */
    fun keyboardIsOurs(defaultInputMethod: String?, packageName: String): Boolean =
        defaultInputMethod?.substringBefore('/')?.trim() == packageName

    /**
     * The language for a session handed [sessionLocale]. Only when [sdk] is one whose keyboard decides, the keyboard
     * is ours, the spell checker's language is automatic and the session came out as English is the phone's
     * language ([systemLocale]) used instead; a phone language the checker doesn't have then gets no answer, as
     * Android 10 and later give it.
     */
    fun choose(sessionLocale: String?, sdk: Int, keyboardIsOurs: Boolean, automatic: Boolean, systemLocale: String?): LanguageProfile? {
        val handed = SpellCheckRules.languageFor(sessionLocale)
        if (!keyboardDecides(sdk) || !keyboardIsOurs || !automatic || handed !== LanguageProfile.ENGLISH) return handed
        return SpellCheckRules.languageFor(systemLocale)
    }
}
