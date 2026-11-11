package dev.localtype.keyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * The person's spellings of their words ([WordSpellings]), one preferences file per language, shared by the
 * keyboard and settings through one copy per process. Main thread only; writes go out with apply(). Forget learned
 * words clears every language's file; removing an added word drops its spelling.
 */
class WordSpellingStore private constructor(private val preferences: SharedPreferences) {
    @Volatile var spellings: Map<String, String> = WordSpellings.decode(preferences.getString(KEY, null).orEmpty())
        private set

    fun display(word: String): String? = WordSpellings.display(spellings, word)

    fun change(word: String, change: WordSpellings.Change) {
        val next = WordSpellings.apply(spellings, word, change) ?: return
        spellings = next
        preferences.edit().putString(KEY, WordSpellings.encode(next)).apply()
    }

    /** Many changes, one write: an imported word list's capitals. */
    fun changeAll(changes: List<Pair<String, WordSpellings.Change>>) {
        val next = WordSpellings.applyAll(spellings, changes) ?: return
        spellings = next
        preferences.edit().putString(KEY, WordSpellings.encode(next)).apply()
    }

    fun clear() {
        spellings = emptyMap()
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "word_spellings"
        private const val KEY = "v1"
        private val stores = HashMap<String, WordSpellingStore>()

        fun preferencesName(language: LanguageProfile): String =
            if (language === LanguageProfile.ENGLISH) PREFS else "${PREFS}_${language.code}"

        fun get(context: Context, language: LanguageProfile = LanguageProfile.ENGLISH): WordSpellingStore = synchronized(stores) {
            val name = preferencesName(language)
            stores.getOrPut(name) { WordSpellingStore(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)) }
        }
    }
}
