package dev.localtype.keyboard

import android.content.res.Resources
import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import android.provider.UserDictionary
import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo

/**
 * Android's spell checker, answered from the keyboard's own word lists and from Android's user dictionary (where
 * the system's Add to dictionary puts words). It is off until the person picks it in Android's settings, under
 * Spell checker.
 *
 * Privacy: the text handed to this service comes from other apps. It is never learned, logged, written to disk
 * or sent anywhere, and nothing here touches the typing log, learned pairs, personal learning, added words,
 * settings or the network. The only things kept in memory are the bundled word lists and a copy of the user
 * dictionary's words; both are dropped when Android unbinds the service. Keep it that way: no Log calls with text
 * or words, no caches, no stores.
 *
 * All decisions are in [SpellCheckRules] (engine), [AcceptedWords] and [SpellCheckLanguage]; this class only loads
 * the lists, reads the user dictionary and translates the answers.
 */
class LocalTypeSpellCheckerService : SpellCheckerService() {
    private lateinit var lists: SpellCheckLists
    private lateinit var userWords: UserDictionaryWords
    private var watcher: ContentObserver? = null
    private var watching = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        lists = SpellCheckLists { assets.open(it).reader() }
        userWords = UserDictionaryWords(::readUserDictionary)
    }

    override fun createSession(): Session = CheckerSession()

    override fun onDestroy() {
        synchronized(this) {
            destroyed = true
            watcher?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
            watcher = null
        }
        userWords.release()
        lists.release()
        super.onDestroy()
    }

    /**
     * Reads Android's user dictionary and watches it for changes, once per binding. Android lets the selected spell
     * checker (and any enabled one) read it with no permission; a phone that refuses answers with no rows, and a
     * missing provider or a refusal leaves the checker answering from its lists alone.
     */
    private fun watchUserDictionary() {
        synchronized(this) {
            if (watching || destroyed) return
            watching = true
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) = userWords.refresh()
            }
            if (runCatching { contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, observer) }.isSuccess) watcher = observer
        }
        userWords.start()
    }

    /** The words and their locales, up to [AcceptedWords.MAX_WORDS] rows; null when the dictionary can't be read. */
    private fun readUserDictionary(): List<UserWord>? = try {
        val cursor = contentResolver.query(UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE), null, null, null)
        try {
            val word = cursor?.getColumnIndex(UserDictionary.Words.WORD) ?: -1
            val locale = cursor?.getColumnIndex(UserDictionary.Words.LOCALE) ?: -1
            if (cursor == null || word < 0) null else {
                val rows = ArrayList<UserWord>()
                while (rows.size < AcceptedWords.MAX_WORDS && cursor.moveToNext()) {
                    rows += UserWord(cursor.getString(word), if (locale < 0) null else cursor.getString(locale))
                }
                rows
            }
        } finally {
            cursor?.close()
        }
    } catch (_: RuntimeException) {
        // SecurityException, a provider that went away, or a bad cursor: as if there were no dictionary.
        null
    }

    /**
     * The language a session checks (see [SpellCheckLanguage]). Android 8.0 to 9 take it from LocalType's own
     * English keyboard subtype when the spell checker's language is automatic, so only there are two system
     * settings read, the current keyboard and whether a spell-checker language was chosen.
     */
    private fun languageFor(sessionLocale: String?): LanguageProfile? {
        val sdk = Build.VERSION.SDK_INT
        if (!SpellCheckLanguage.keyboardDecides(sdk)) return SpellCheckRules.languageFor(sessionLocale)
        val ours = runCatching {
            SpellCheckLanguage.keyboardIsOurs(Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD), packageName)
        }.getOrDefault(false)
        // Android's own name for it (Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, hidden); 0 is automatic.
        val automatic = ours && runCatching { Settings.Secure.getInt(contentResolver, "selected_spell_checker_subtype", 0) == 0 }.getOrDefault(false)
        val system = runCatching { Resources.getSystem().configuration.locales[0].toString() }.getOrNull()
        return SpellCheckLanguage.choose(sessionLocale, sdk, ours, automatic, system)
    }

    private inner class CheckerSession : Session() {
        /** Null for a locale with no lists here: such a session answers nothing, never in English. */
        private var language: LanguageProfile? = null

        override fun onCreate() {
            language = languageFor(locale)
            language?.let {
                watchUserDictionary()
                lists.get(it)
            }
        }

        /**
         * The loaded lists. A request that arrives while they are being read waits for them here, on Android's
         * binder thread: a text field asks about a sentence once per edit and never again, so an empty answer would
         * leave the first words typed in a field unmarked (seen on a Pixel 9a: the read takes 0.6 to 2.4 seconds).
         * The field itself is not held up, because it gets its answer through a callback. Null after
         * [SpellCheckLists.WAIT_MILLIS] (and at once for later requests of a read that ran over), or when the lists
         * could not be read.
         */
        private fun loaded(): SpellCheckLists.Lists? = language?.let { lists.get(it, SpellCheckLists.WAIT_MILLIS) }

        private fun userWords(): AcceptedWords =
            language?.let { userWords.words(it, UserDictionaryWords.WAIT_MILLIS) } ?: AcceptedWords.EMPTY

        /** The lists' answer for [token], then the current words and the user dictionary's say. */
        private fun answer(lists: SpellCheckLists.Lists, user: AcceptedWords, token: String, result: SpellCheckRules.Result, limit: Int) =
            user.apply(token, lists.current.apply(token, result, limit), limit)

        override fun onGetSuggestions(textInfo: TextInfo?, suggestionsLimit: Int): SuggestionsInfo {
            val text = textInfo?.text
            val result = if (text == null) null else runCatching {
                loaded()?.let { answer(it, userWords(), text, it.rules.checkWord(text, limit = suggestionsLimit), suggestionsLimit) }
            }.getOrNull()
            return info(result, textInfo)
        }

        override fun onGetSentenceSuggestionsMultiple(textInfos: Array<out TextInfo>?, suggestionsLimit: Int): Array<SentenceSuggestionsInfo> {
            val loaded = loaded()
            val user = if (loaded == null) AcceptedWords.EMPTY else userWords()
            return Array(textInfos?.size ?: 0) { index ->
                val textInfo = textInfos?.get(index)
                val text = textInfo?.text
                val words = if (loaded == null || text == null) emptyList() else runCatching {
                    loaded.rules.check(text, suggestionsLimit).map {
                        SpellCheckRules.Word(it.start, it.length, answer(loaded, user, text.substring(it.start, it.start + it.length), it.result, suggestionsLimit))
                    }
                }.getOrDefault(emptyList())
                SentenceSuggestionsInfo(
                    Array(words.size) { info(words[it].result, textInfo) },
                    IntArray(words.size) { words[it].start },
                    IntArray(words.size) { words[it].length },
                )
            }
        }

        /** No verdict (not loaded, not judged) carries no attribute, so the app draws nothing. */
        private fun info(result: SpellCheckRules.Result?, textInfo: TextInfo?): SuggestionsInfo {
            val attributes = when (result?.verdict) {
                SpellCheckRules.Verdict.KNOWN -> SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY
                SpellCheckRules.Verdict.MISSPELLED -> SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                else -> 0
            }
            val info = SuggestionsInfo(attributes, result?.suggestions.orEmpty().toTypedArray())
            if (textInfo != null) info.setCookieAndSequence(textInfo.cookie, textInfo.sequence)
            return info
        }
    }
}
