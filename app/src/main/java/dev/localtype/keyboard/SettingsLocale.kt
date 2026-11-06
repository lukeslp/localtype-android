package dev.localtype.keyboard

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** What a LocalType screen opens with: the theme from Appearance and the language from [PageLanguage]. */
object SettingsLocale {
    /** The phone's own languages, in its order, before this app overrides anything. */
    fun phoneLanguages(): List<String> =
        Resources.getSystem().configuration.locales.let { list -> (0 until list.size()).map { list[it].language } }

    /** The language the settings screens show now. */
    fun shown(context: Context): String = PageLanguage.shown(KeyboardSettings.settingsLanguage(context), phoneLanguages())

    /**
     * The locale to show the screens in when it isn't the phone's first language, or null to leave the phone's
     * own (with its region, for dates and numbers).
     */
    fun override(context: Context): Locale? {
        val shown = shown(context)
        return if (phoneLanguages().firstOrNull() == shown) null else PageLanguage.locale(shown)
    }

    /** [base]'s configuration with the theme and language applied, or null when neither changes anything. */
    fun configuration(base: Context): Configuration? {
        val themed = ThemeMode.of(base).apply(base.resources.configuration)
        val locale = override(base) ?: return themed
        return Configuration(themed ?: base.resources.configuration).apply { setLocales(LocaleList(locale)) }
    }
}
