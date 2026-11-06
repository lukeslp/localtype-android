package dev.localtype.keyboard

import android.content.Context

/** Persists cached model predictions in private storage as `timestamp|word|word…`. */
class PreferenceModelCacheStore(context: Context) : ModelSuggestionCache.Store {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Map<String, ModelSuggestionCache.Entry> {
        // Builds before 0.3.2 cached model guesses for sentence starts under "<s>"; drop them.
        if (preferences.contains(LEGACY_SENTENCE_START)) preferences.edit().remove(LEGACY_SENTENCE_START).apply()
        return loadEntries()
    }

    private fun loadEntries(): Map<String, ModelSuggestionCache.Entry> = preferences.all.filterKeys { it != LEGACY_SENTENCE_START }.mapNotNull { (key, value) ->
        val parts = (value as? String)?.split('|') ?: return@mapNotNull null
        val storedAt = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
        val words = parts.drop(1).filter { it.isNotBlank() }
        if (words.isEmpty()) null else key to ModelSuggestionCache.Entry(words, storedAt)
    }.toMap()

    override fun save(key: String, entry: ModelSuggestionCache.Entry) {
        preferences.edit().putString(key, (listOf(entry.storedAt.toString()) + entry.words).joinToString("|")).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        const val PREFS = "model_suggestion_cache"
        private const val LEGACY_SENTENCE_START = "<s>"
    }
}
