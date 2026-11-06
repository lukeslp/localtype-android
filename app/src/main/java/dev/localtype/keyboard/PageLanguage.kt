package dev.localtype.keyboard

import java.util.Locale

/**
 * The language of the settings screen. It follows the phone's languages where LocalType has a translation,
 * unless one is chosen under Languages → Settings shown in. Spanish and Portuguese are machine-translated and
 * marked experimental on the screen itself.
 */
object PageLanguage {
    /** What the settings screen is translated into, English first. */
    val TRANSLATED = listOf("en", "es", "pt")

    /**
     * The language shown: [chosen] when it's translated, otherwise the first of the phone's languages that is,
     * otherwise English. A phone set to German then English shows English, not whichever translation Android
     * would match first.
     */
    fun shown(chosen: String, phoneLanguages: List<String>): String =
        chosen.takeIf { it in TRANSLATED } ?: phoneLanguages.firstOrNull { it in TRANSLATED } ?: "en"

    /**
     * Settings shown in appears when there's a real choice: a language was chosen before, the phone's own
     * language has a translation other than English, or a typing language other than English is on.
     */
    fun rowShown(chosen: String, phoneLanguages: List<String>, typing: List<LanguageProfile>): Boolean =
        chosen in TRANSLATED || shown("", phoneLanguages) != "en" || typing.any { it.words && !it.english }

    /** The locale for [code]; Portuguese is Brazilian, as its word lists are. */
    fun locale(code: String): Locale = if (code == "pt") Locale("pt", "BR") else Locale(code)
}
