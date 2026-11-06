package dev.localtype.keyboard

import java.util.Locale
import java.util.MissingResourceException

/**
 * Which text-to-speech voice Read aloud may speak with. Typing never touches the network, and the text can come from
 * an incognito or private-for-now field, so only a voice the engine has installed and that speaks without a
 * connection will do (`Voice.isNetworkConnectionRequired` false, no `Engine.KEY_FEATURE_NOT_INSTALLED`). Pure, so the
 * choice is tested.
 */
object ReadAloud {
    /** What the choice needs of one of the engine's voices. */
    data class VoiceOption(val name: String, val locale: Locale, val offline: Boolean, val installed: Boolean, val quality: Int = 0)

    /**
     * The voice for the first of [wanted] that has an offline, installed one: the text's language, then the fallbacks
     * after it. Among a language's voices the engine's own [defaultName] comes first, then one for the same country,
     * then the best quality. Null when none of [wanted] has one, and then nothing is spoken.
     */
    fun choose(options: List<VoiceOption>, wanted: List<Locale>, defaultName: String? = null): VoiceOption? {
        val usable = options.filter { it.offline && it.installed }
        for (locale in wanted) {
            val language = language(locale)
            if (language.isEmpty()) continue
            val country = country(locale)
            usable.filter { language(it.locale) == language }
                .minWithOrNull(compareBy({ it.name != defaultName }, { country(it.locale) != country }, { -it.quality }, { it.name }))
                ?.let { return it }
        }
        return null
    }

    /** Whether the engine takes [text] in one request; a longer one would fail while the strip said it was reading. */
    fun fits(text: String, maxLength: Int): Boolean = text.length <= maxLength

    // An engine may name a language or country by its two-letter or three-letter code ("en" or "eng").
    private fun language(locale: Locale): String = try { locale.isO3Language } catch (_: MissingResourceException) { locale.language }
    private fun country(locale: Locale): String = try { locale.isO3Country } catch (_: MissingResourceException) { locale.country }
}
