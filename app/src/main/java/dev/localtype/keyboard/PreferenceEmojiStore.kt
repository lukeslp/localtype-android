package dev.localtype.keyboard

import android.content.Context
import android.content.SharedPreferences

/** Recent emoji and skin-tone choices in private app storage, apart from the learned words. */
class PreferenceEmojiStore(context: Context) : EmojiChoiceStore {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun recent(): List<String> = RecentEmoji.decode(preferences.getString(RECENT, null))

    override fun setRecent(recent: List<String>) {
        preferences.edit().putString(RECENT, RecentEmoji.encode(recent)).apply()
    }

    override fun tones(): Map<String, Int> = preferences.all.mapNotNull { (key, value) ->
        val tone = value as? Int ?: return@mapNotNull null
        if (!key.startsWith(TONE_PREFIX) || key.length == TONE_PREFIX.length) return@mapNotNull null
        key.substring(TONE_PREFIX.length) to tone
    }.toMap()

    /** Forget learned words: the recent list is history and goes; skin-tone choices are preferences and stay. */
    fun forgetRecent() {
        preferences.edit().remove(RECENT).apply()
    }

    override fun defaultTone(): Int = preferences.getInt(DEFAULT_TONE, 0)

    override fun setTone(base: String, tone: Int, defaultTone: Int) {
        preferences.edit().putInt(TONE_PREFIX + base, tone).putInt(DEFAULT_TONE, defaultTone).apply()
    }

    private companion object {
        const val PREFS = "emoji"
        const val RECENT = "recent"
        const val DEFAULT_TONE = "default_tone"
        const val TONE_PREFIX = "tone:"
    }
}
