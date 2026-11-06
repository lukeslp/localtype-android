package dev.localtype.keyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * Letter-key lift means in their own private preferences file, one file per letters layout:
 * a few dozen keys at most, three values each, so rewriting it after a learned word stays small.
 * English keeps the file and key names it always had. The learned-pair journal migrates and deletes
 * the old pair preferences, so the offsets never share that file. Forget learned words clears this too.
 */
class TouchOffsetStore internal constructor(
    private val preferences: SharedPreferences,
    private val language: LanguageProfile = LanguageProfile.ENGLISH,
) {
    constructor(context: Context, language: LanguageProfile = LanguageProfile.ENGLISH) :
        this(context.getSharedPreferences(preferencesName(language), Context.MODE_PRIVATE), language)

    private val keys: List<Char> =
        if (language === LanguageProfile.ENGLISH) LetterGeometry.QWERTY.keys else language.geometry.keys

    fun load(offsets: TouchOffsets) {
        val saved = preferences.all
        offsets.load(keys.mapNotNull { letter ->
            val count = saved["${letter}_count"] as? Int ?: return@mapNotNull null
            val dx = saved["${letter}_dx"] as? Float ?: return@mapNotNull null
            val dy = saved["${letter}_dy"] as? Float ?: return@mapNotNull null
            TouchOffsets.Entry(letter, count, dx, dy)
        })
    }

    fun save(offsets: TouchOffsets) {
        val entries = offsets.entries().associateBy { it.letter }
        val editor = preferences.edit()
        for (letter in keys) {
            val entry = entries[letter]
            if (entry == null) {
                editor.remove("${letter}_count").remove("${letter}_dx").remove("${letter}_dy")
            } else {
                editor.putInt("${letter}_count", entry.count).putFloat("${letter}_dx", entry.dx).putFloat("${letter}_dy", entry.dy)
            }
        }
        editor.apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        const val PREFS = "touch_offsets"

        /** English's file is the one the keyboard always wrote; every other layout has its own beside it. */
        fun preferencesName(language: LanguageProfile): String =
            if (language === LanguageProfile.ENGLISH) PREFS else "${PREFS}_${language.code}"
    }
}
