package dev.localtype.keyboard

/** Which languages the hold-Space chooser offers, from the switches under Languages. */
object LanguageChoice {
    /**
     * English first and the IPA keyboard last. A language with words always stays: with every one of them
     * switched off, English comes back, so the keyboard never ends up with nothing to suggest or correct.
     */
    fun offered(
        english: Boolean, spanish: Boolean, portuguese: Boolean, ipa: Boolean, hebrew: Boolean = false,
        welsh: Boolean = false, irish: Boolean = false, maori: Boolean = false, hawaiian: Boolean = false,
    ): List<LanguageProfile> = buildList {
        if (english) add(LanguageProfile.ENGLISH)
        if (spanish) add(LanguageProfile.SPANISH)
        if (portuguese) add(LanguageProfile.PORTUGUESE)
        if (hebrew) add(LanguageProfile.HEBREW)
        if (welsh) add(LanguageProfile.WELSH)
        if (irish) add(LanguageProfile.IRISH)
        if (maori) add(LanguageProfile.MAORI)
        if (hawaiian) add(LanguageProfile.HAWAIIAN)
        if (isEmpty()) add(LanguageProfile.ENGLISH)
        if (ipa) add(LanguageProfile.IPA)
    }

    /** Whether [language]'s switch may be turned off: anything but the last language with words that's on. */
    fun canTurnOff(language: LanguageProfile, offered: List<LanguageProfile>): Boolean =
        !language.words || language !in offered || offered.count { it.words } > 1
}
