package dev.localtype.keyboard

import android.content.Context

/** Keyboard preferences shared by settings and the running keyboard. */
object KeyboardSettings {
    private const val PREFS = "keyboard_state"
    private const val AUTOCORRECT = "autocorrect"
    private const val WEB_AUTOCORRECT = "web_autocorrect"
    private const val AUTO_CAPITALIZE = "auto_capitalize"
    private const val PROFANITY = "profanity"
    private const val EMOJI = "emoji"
    private const val NEURAL_PREDICTIONS = "neural_predictions"
    private const val NUMBER_ROW = "number_row"
    const val VIBRATE_ON_KEY_PRESS = "vibrate_on_key_press"
    private const val BOTTOM_CLEARANCE = "bottom_clearance"
    private const val THEME = "theme"
    private const val MODEL_ON_TAP = "model_on_tap"
    private const val KEEP_MODEL_LOADED = "keep_model_loaded"
    const val SPARKLE_BUTTON = "sparkle_button"
    const val MODEL_SET_UP = "model_set_up"
    private const val TRIPLE_SPACE_PROOFREAD = "triple_space_proofread"
    private const val LEARNED_GENERATION = "learned_generation"
    private const val PREDICTION_LOG = "prediction_log"
    private const val VOICE_INPUT = "voice_input"
    private const val VOICE_KEY = "voice_key"
    private const val SLIDE_GESTURES = "slide_gestures"
    private const val SLIDE_SPEED = "slide_speed"
    private const val TOOLS_ROW = "tools_row"
    private const val KEY_SHAPES = "key_shapes"
    private const val SPACE_BAR = "space_bar"
    private const val ONE_HANDED = "one_handed"
    private const val WIDE_KEYS = "wide_keys"
    private const val BRITISH_SPELLING = "british_spelling"
    private const val CURRENCY = "currency"
    private const val COMMON_SYMBOL_HOLDS = "common_symbol_holds"
    private const val STRIP_HEIGHT = "strip_height"
    private const val EXPERIMENTAL_SWIPE = "experimental_swipe"
    private const val TWO_FINGER_GESTURES = "two_finger_gestures"
    private const val IGNORE_REPEAT_MS = "ignore_repeat_ms"
    private const val KEY_HOLD_MS = "key_hold_ms"
    private const val DWELL_MS = "dwell_ms"
    private const val STEADY_KEYS = "steady_keys"
    private const val AIM_FROM_TOUCHES = "aim_from_touches"
    const val INACTIVE_GAPS = "inactive_gaps"
    private const val GESTURE_PREFIX = "gesture_"
    private const val PASSWORD_MANAGER_SUGGESTIONS = "password_manager_suggestions"
    private const val CLEAN_PASTED_LINKS = "clean_pasted_links"
    private const val REMEMBER_DOMAINS = "remember_domains"
    const val ENGLISH = "language_english"
    const val SPANISH = "language_spanish"
    const val IPA = "language_ipa"
    const val PORTUGUESE = "language_portuguese"
    const val HEBREW = "language_hebrew"
    const val WELSH = "language_welsh"
    const val IRISH = "language_irish"
    const val MAORI = "language_maori"
    const val HAWAIIAN = "language_hawaiian"
    const val LANGUAGE = "language"
    private const val SETTINGS_LANGUAGE = "settings_language"
    private const val AUTO_UPDATE_CHECK = "auto_update_check"
    private const val UPDATE_CHECKED_AT = "update_checked_at"
    private const val UPDATE_LATEST_CODE = "update_latest_code"
    private const val UPDATE_LATEST_NAME = "update_latest_name"
    private const val UPDATE_INSTALLABLE = "update_installable"
    private const val UPDATE_DISMISSED_CODE = "update_dismissed_code"
    private const val SETUP_COMPLETE = "setup_complete"
    private const val SETUP_STEP = "setup_step"
    private const val TOUR_SEEN = "tour_seen"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autocorrect(context: Context) = prefs(context).getBoolean(AUTOCORRECT, true)
    fun setAutocorrect(context: Context, on: Boolean) = prefs(context).edit().putBoolean(AUTOCORRECT, on).apply()

    fun webAutocorrect(context: Context) = prefs(context).getBoolean(WEB_AUTOCORRECT, true)
    fun setWebAutocorrect(context: Context, on: Boolean) = prefs(context).edit().putBoolean(WEB_AUTOCORRECT, on).apply()

    /** Sentence-start Shift, sentence-start suggestions and the lone "i" fix; on by default. Read once per field. */
    fun autoCapitalize(context: Context) = prefs(context).getBoolean(AUTO_CAPITALIZE, true)
    fun setAutoCapitalize(context: Context, on: Boolean) = prefs(context).edit().putBoolean(AUTO_CAPITALIZE, on).apply()

    /** Swear words are suggested by default; turning this off hides them from suggestions only. */
    fun profanity(context: Context) = prefs(context).getBoolean(PROFANITY, true)
    fun setProfanity(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PROFANITY, on).apply()

    /** Light, dark, or follow the system (the default). */
    fun theme(context: Context): String = prefs(context).getString(THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
    fun setTheme(context: Context, mode: ThemeMode) = prefs(context).edit().putString(THEME, mode.name).apply()

    fun numberRow(context: Context) = prefs(context).getBoolean(NUMBER_ROW, false)
    fun setNumberRow(context: Context, on: Boolean) = prefs(context).edit().putBoolean(NUMBER_ROW, on).apply()

    /** Optional common symbols before accents on Latin letter holds. Read once per field; off by default. */
    fun commonSymbolHolds(context: Context) = prefs(context).getBoolean(COMMON_SYMBOL_HOLDS, false)
    fun setCommonSymbolHolds(context: Context, on: Boolean) = prefs(context).edit().putBoolean(COMMON_SYMBOL_HOLDS, on).apply()

    /**
     * Every keyboard vibration ([KeyHaptics]), on by default; a saved off choice still wins. While on, Android's
     * touch feedback setting still decides. Read once per field and again when it changes.
     */
    fun vibrateOnKeyPress(context: Context) = prefs(context).getBoolean(VIBRATE_ON_KEY_PRESS, true)
    fun setVibrateOnKeyPress(context: Context, on: Boolean) = prefs(context).edit().putBoolean(VIBRATE_ON_KEY_PRESS, on).apply()

    /** Extra clearance above Android's navigation buttons; existing key geometry stays intact. */
    fun bottomClearance(context: Context) = prefs(context).getInt(BOTTOM_CLEARANCE, 0).coerceIn(0, 48)
    fun setBottomClearance(context: Context, dp: Int) = prefs(context).edit().putInt(BOTTOM_CLEARANCE, dp.coerceIn(0, 48)).apply()

    /** Emoji suggestions in the strip, on by default. */
    fun emoji(context: Context) = prefs(context).getBoolean(EMOJI, true)
    fun setEmoji(context: Context, on: Boolean) = prefs(context).edit().putBoolean(EMOJI, on).apply()

    /** Bundled next-word model, on by default; a saved off choice still wins. Read once per field. */
    fun neuralPredictions(context: Context) = prefs(context).getBoolean(NEURAL_PREDICTIONS, true)
    fun setNeuralPredictions(context: Context, on: Boolean) = prefs(context).edit().putBoolean(NEURAL_PREDICTIONS, on).apply()

    /** When on, the model only runs after tapping the ✦ button in the suggestion strip. */
    fun modelOnTap(context: Context) = prefs(context).getBoolean(MODEL_ON_TAP, false)
    fun setModelOnTap(context: Context, on: Boolean) = prefs(context).edit().putBoolean(MODEL_ON_TAP, on).apply()

    /**
     * The sparkle in the suggestion strip, on by default. Off, the lead button never shows it; the paste chip and
     * the Undo arrow stay. On, it still shows only where a model is set up ([SparkleRules]). Applied at once.
     */
    fun sparkleButton(context: Context) = prefs(context).getBoolean(SPARKLE_BUTTON, true)
    fun setSparkleButton(context: Context, on: Boolean) = prefs(context).edit().putBoolean(SPARKLE_BUTTON, on).apply()

    /**
     * The last answer to "is a model set up" ([SparkleRules.modelSetUp]), kept so a new keyboard process draws the
     * strip right before it has asked. Not a choice: the keyboard and settings write what they find.
     */
    fun modelSetUp(context: Context) = prefs(context).getBoolean(MODEL_SET_UP, false)
    fun rememberModelSetUp(context: Context, setUp: Boolean) {
        if (!prefs(context).contains(MODEL_SET_UP) || modelSetUp(context) != setUp) prefs(context).edit().putBoolean(MODEL_SET_UP, setUp).apply()
    }

    /** Explicit sentence review; double-space punctuation works independently of this choice. */
    fun tripleSpaceProofread(context: Context) = prefs(context).getBoolean(TRIPLE_SPACE_PROOFREAD, false)
    fun setTripleSpaceProofread(context: Context, on: Boolean) = prefs(context).edit().putBoolean(TRIPLE_SPACE_PROOFREAD, on).apply()

    /** When on, the model stays in memory instead of unloading after a few idle minutes. */
    fun keepModelLoaded(context: Context) = prefs(context).getBoolean(KEEP_MODEL_LOADED, false)
    fun setKeepModelLoaded(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEEP_MODEL_LOADED, on).apply()

    /** The opt-in typing log, off by default; read once per field like the other switches. */
    fun predictionLog(context: Context) = prefs(context).getBoolean(PREDICTION_LOG, false)
    fun setPredictionLog(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PREDICTION_LOG, on).apply()

    /** The mic key, on by default; it only shows where the phone has a way to hear. Read once per field. */
    fun voiceInput(context: Context) = prefs(context).getBoolean(VOICE_INPUT, true)
    fun setVoiceInput(context: Context, on: Boolean) = prefs(context).edit().putBoolean(VOICE_INPUT, on).apply()
    /** Where the voice key goes besides the tools row ([VoiceKey]); off by default, so the strip keeps its room. */
    fun voiceKey(context: Context): VoiceKey = VoiceKey.of(prefs(context).getString(VOICE_KEY, null))
    fun setVoiceKey(context: Context, value: VoiceKey) = prefs(context).edit().putString(VOICE_KEY, value.name).apply()

    /** The caret slide on space and the word slide on delete, on by default. Read once per field. */
    fun slideGestures(context: Context) = prefs(context).getBoolean(SLIDE_GESTURES, true)
    fun setSlideGestures(context: Context, on: Boolean) = prefs(context).edit().putBoolean(SLIDE_GESTURES, on).apply()

    /** Caret steps and words per distance on the space and delete slides; read once per field. */
    fun slideSpeed(context: Context): CursorGestures.Speed = CursorGestures.Speed.of(prefs(context).getString(SLIDE_SPEED, null))
    fun setSlideSpeed(context: Context, speed: CursorGestures.Speed) = prefs(context).edit().putString(SLIDE_SPEED, speed.name).apply()

    /** The tools row's tools by id, in order ([StripTools.rowOrder]); null is the built-in row. Read at each open. */
    fun toolsRow(context: Context): List<String>? = prefs(context).getString(TOOLS_ROW, null)?.split(',')?.filter { it.isNotEmpty() }
    fun setToolsRow(context: Context, ids: List<String>?) = prefs(context).edit().apply {
        if (ids == null) remove(TOOLS_ROW) else putString(TOOLS_ROW, ids.joinToString(","))
    }.apply()

    /** A rounded shape behind each letter key; on by default. Off leaves the letters on the background. */
    fun keyShapes(context: Context) = prefs(context).getBoolean(KEY_SHAPES, true)
    fun setKeyShapes(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SHAPES, on).apply()

    /**
     * English's Spelling: British choice, off by default: the strip and corrections offer "colour" and "organise"
     * rather than "color" and "organize". Currency is a separate choice. Read once per field.
     */
    fun britishSpelling(context: Context) = prefs(context).getBoolean(BRITISH_SPELLING, false)
    fun setBritishSpelling(context: Context, on: Boolean) = prefs(context).edit().putBoolean(BRITISH_SPELLING, on).apply()

    /** Currency defaults to following spelling, preserving existing layouts. Read once per field. */
    fun currency(context: Context): KeyboardCurrency = KeyboardCurrency.of(prefs(context).getString(CURRENCY, null))
    fun setCurrency(context: Context, value: KeyboardCurrency) = prefs(context).edit().putString(CURRENCY, value.name).apply()

    /** How wide the space bar is; read once per field. */
    fun spaceBar(context: Context): SpaceBar = SpaceBar.of(prefs(context).getString(SPACE_BAR, null))
    fun setSpaceBar(context: Context, value: SpaceBar) = prefs(context).edit().putString(SPACE_BAR, value.name).apply()

    /** One-handed keys and their side ([OneHanded]); read once per field and when the tool or the rail changes it. */
    fun oneHanded(context: Context): OneHanded = OneHanded.of(prefs(context).getString(ONE_HANDED, null))
    fun setOneHanded(context: Context, value: OneHanded) = prefs(context).edit().putString(ONE_HANDED, value.name).apply()

    /** Keys on a window 600 dp wide or more ([WideKeys], [KeyWidthRules]): centered at a maximum width by default. Read once per field. */
    fun wideKeys(context: Context): WideKeys = WideKeys.of(prefs(context).getString(WIDE_KEYS, null))
    fun setWideKeys(context: Context, value: WideKeys) = prefs(context).edit().putString(WIDE_KEYS, value.name).apply()

    /** How tall the suggestion strip is, and how large its words may grow; read once per field. */
    fun stripHeight(context: Context): StripHeight = StripHeight.of(prefs(context).getString(STRIP_HEIGHT, null))
    fun setStripHeight(context: Context, value: StripHeight) = prefs(context).edit().putString(STRIP_HEIGHT, value.name).apply()

    /** Touch accommodations for a tremor or limited reach ([MotorSettings]), all off by default. Read once per field. */
    fun motor(context: Context): MotorSettings = prefs(context).let {
        MotorSettings(
            ignoreRepeatMs = MotorSettings.choice(it.getInt(IGNORE_REPEAT_MS, 0), MotorSettings.REPEAT_CHOICES),
            holdMs = MotorSettings.choice(it.getInt(KEY_HOLD_MS, 0), MotorSettings.HOLD_CHOICES),
            dwellMs = MotorSettings.choice(it.getInt(DWELL_MS, 0), MotorSettings.DWELL_CHOICES),
            steadyKeys = it.getBoolean(STEADY_KEYS, false),
            aimFromTouches = it.getBoolean(AIM_FROM_TOUCHES, false),
        )
    }
    fun setIgnoreRepeatMs(context: Context, ms: Int) = prefs(context).edit().putInt(IGNORE_REPEAT_MS, ms).apply()
    fun setKeyHoldMs(context: Context, ms: Int) = prefs(context).edit().putInt(KEY_HOLD_MS, ms).apply()
    fun setDwellMs(context: Context, ms: Int) = prefs(context).edit().putInt(DWELL_MS, ms).apply()
    fun setSteadyKeys(context: Context, on: Boolean) = prefs(context).edit().putBoolean(STEADY_KEYS, on).apply()
    fun setAimFromTouches(context: Context, on: Boolean) = prefs(context).edit().putBoolean(AIM_FROM_TOUCHES, on).apply()

    /** Physical row margins and the band below suggestions reject touches. Existing choices stay off. */
    fun inactiveGaps(context: Context) = prefs(context).getBoolean(INACTIVE_GAPS, false)
    fun setInactiveGaps(context: Context, on: Boolean) = prefs(context).edit().putBoolean(INACTIVE_GAPS, on).apply()

    /** English letter swipe prototype; a new or existing installation starts off. */
    fun experimentalSwipe(context: Context) = prefs(context).getBoolean(EXPERIMENTAL_SWIPE, false)
    fun setExperimentalSwipe(context: Context, on: Boolean) = prefs(context).edit().putBoolean(EXPERIMENTAL_SWIPE, on).apply()

    /** Two-finger swipes on the keys; off for a new or existing installation. Read once per field. */
    fun twoFingerGestures(context: Context) = prefs(context).getBoolean(TWO_FINGER_GESTURES, false)
    fun setTwoFingerGestures(context: Context, on: Boolean) = prefs(context).edit().putBoolean(TWO_FINGER_GESTURES, on).apply()

    /** What [swipe] does: the saved choice, or [GestureRules.DEFAULTS]'s. Read once per field. */
    fun gestureAction(context: Context, swipe: TwoFingerSwipe): GestureAction =
        GestureRules.action(prefs(context).getString(GESTURE_PREFIX + swipe.id, null), swipe)
    fun setGestureAction(context: Context, swipe: TwoFingerSwipe, action: GestureAction) =
        prefs(context).edit().putString(GESTURE_PREFIX + swipe.id, action.id).apply()

    /** A password manager's suggestions in the strip (Android 11+), on by default. Read each time a field asks. */
    fun passwordManagerSuggestions(context: Context) = prefs(context).getBoolean(PASSWORD_MANAGER_SUGGESTIONS, true)
    fun setPasswordManagerSuggestions(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PASSWORD_MANAGER_SUGGESTIONS, on).apply()

    /** Tracking parameters come off links pasted through the keyboard, on by default. Read once per field. */
    fun cleanPastedLinks(context: Context) = prefs(context).getBoolean(CLEAN_PASTED_LINKS, true)
    fun setCleanPastedLinks(context: Context, on: Boolean) = prefs(context).edit().putBoolean(CLEAN_PASTED_LINKS, on).apply()

    /** Hosts of addresses typed in full are remembered and offered, on by default. Read once per field. */
    fun rememberDomains(context: Context) = prefs(context).getBoolean(REMEMBER_DOMAINS, true)
    fun setRememberDomains(context: Context, on: Boolean) = prefs(context).edit().putBoolean(REMEMBER_DOMAINS, on).apply()

    /** English in the hold-Space chooser, on unless turned off with another language with words on ([LanguageChoice]). */
    fun english(context: Context) = prefs(context).getBoolean(ENGLISH, true)
    fun setEnglish(context: Context, on: Boolean) = setOffered(context, ENGLISH, LanguageProfile.ENGLISH, on)

    /** Spanish in the hold-Space chooser: experimental, off for a new or existing installation. */
    fun spanish(context: Context) = prefs(context).getBoolean(SPANISH, false)

    /** Turning Spanish off while it's in use switches to the first language left, so nobody is left typing in a hidden one. */
    fun setSpanish(context: Context, on: Boolean) = setOffered(context, SPANISH, LanguageProfile.SPANISH, on)

    /** Portuguese in the hold-Space chooser: experimental, off for a new or existing installation. */
    fun portuguese(context: Context) = prefs(context).getBoolean(PORTUGUESE, false)
    fun setPortuguese(context: Context, on: Boolean) = setOffered(context, PORTUGUESE, LanguageProfile.PORTUGUESE, on)

    /** Hebrew in the hold-Space chooser: experimental, off for a new or existing installation. */
    fun hebrew(context: Context) = prefs(context).getBoolean(HEBREW, false)
    fun setHebrew(context: Context, on: Boolean) = setOffered(context, HEBREW, LanguageProfile.HEBREW, on)

    /** Welsh, Irish, Māori and Hawaiian in the hold-Space chooser: experimental, off for a new or existing installation. */
    fun welsh(context: Context) = prefs(context).getBoolean(WELSH, false)
    fun setWelsh(context: Context, on: Boolean) = setOffered(context, WELSH, LanguageProfile.WELSH, on)
    fun irish(context: Context) = prefs(context).getBoolean(IRISH, false)
    fun setIrish(context: Context, on: Boolean) = setOffered(context, IRISH, LanguageProfile.IRISH, on)
    fun maori(context: Context) = prefs(context).getBoolean(MAORI, false)
    fun setMaori(context: Context, on: Boolean) = setOffered(context, MAORI, LanguageProfile.MAORI, on)
    fun hawaiian(context: Context) = prefs(context).getBoolean(HAWAIIAN, false)
    fun setHawaiian(context: Context, on: Boolean) = setOffered(context, HAWAIIAN, LanguageProfile.HAWAIIAN, on)

    /**
     * The languages added from November 2026 ([LanguageProfile.EXTRA]: Arabic, Persian and those after them) share one
     * pair of functions; each one's switch is `language_<code>`, experimental and off for a new or existing installation.
     */
    fun extraKey(language: LanguageProfile) = "language_${language.code}"
    val EXTRA_KEYS: Set<String> = LanguageProfile.EXTRA.mapTo(HashSet()) { extraKey(it) }
    fun extraLanguage(context: Context, language: LanguageProfile) = prefs(context).getBoolean(extraKey(language), false)
    fun setExtraLanguage(context: Context, language: LanguageProfile, on: Boolean) = setOffered(context, extraKey(language), language, on)

    /** The IPA keyboard in the hold-Space chooser, off for a new or existing installation. */
    fun ipa(context: Context) = prefs(context).getBoolean(IPA, false)
    fun setIpa(context: Context, on: Boolean) = setOffered(context, IPA, LanguageProfile.IPA, on)

    private fun setOffered(context: Context, key: String, language: LanguageProfile, on: Boolean) {
        // apply() updates the in-memory values at once, so languages() below already sees the change.
        prefs(context).edit().putBoolean(key, on).apply()
        if (!on && prefs(context).getString(LANGUAGE, null) == language.code) {
            prefs(context).edit().putString(LANGUAGE, languages(context).first().code).apply()
        }
    }

    /** What the chooser offers, English first and the IPA keyboard last; always at least one language with words. */
    fun languages(context: Context): List<LanguageProfile> =
        LanguageChoice.offered(english(context), spanish(context), portuguese(context), ipa(context), hebrew(context),
            welsh = welsh(context), irish = irish(context), maori = maori(context), hawaiian = hawaiian(context),
            extra = LanguageProfile.EXTRA.filter { extraLanguage(context, it) })

    /** The language being typed, switched by hand; the first one offered unless another is on and chosen. */
    fun language(context: Context): LanguageProfile {
        val code = prefs(context).getString(LANGUAGE, null)
        val offered = languages(context)
        return offered.firstOrNull { it.code == code } ?: offered.first()
    }

    /** The settings screen's language code ([PageLanguage]), or empty to follow the phone. */
    fun settingsLanguage(context: Context): String = prefs(context).getString(SETTINGS_LANGUAGE, "") ?: ""
    fun setSettingsLanguage(context: Context, code: String) = prefs(context).edit().putString(SETTINGS_LANGUAGE, code).apply()

    fun setLanguage(context: Context, language: LanguageProfile) = prefs(context).edit().putString(LANGUAGE, language.code).apply()

    fun setupComplete(context: Context) = prefs(context).getBoolean(SETUP_COMPLETE, false)
    fun setSetupComplete(context: Context) = prefs(context).edit().putBoolean(SETUP_COMPLETE, true).apply()
    fun setupStep(context: Context): String? = prefs(context).getString(SETUP_STEP, null)
    fun setSetupStep(context: Context, step: String) = prefs(context).edit().putString(SETUP_STEP, step).apply()

    /** Whether the tour of holds, slides and the clipboard has been shown or declined. */
    fun tourSeen(context: Context) = prefs(context).getBoolean(TOUR_SEEN, false)
    fun setTourSeen(context: Context) = prefs(context).edit().putBoolean(TOUR_SEEN, true).apply()

    /** Settings looks for a newer version on its own when it opens (UpdateBanner); on by default. */
    fun autoUpdateCheck(context: Context) = prefs(context).getBoolean(AUTO_UPDATE_CHECK, true)
    fun setAutoUpdateCheck(context: Context, on: Boolean) = prefs(context).edit().putBoolean(AUTO_UPDATE_CHECK, on).apply()

    /** The newest release the last check found, kept so the banner shows at once the next time settings opens. */
    class SeenRelease(val code: Int, val name: String, val installable: Boolean, val checkedAt: Long)

    fun seenRelease(context: Context): SeenRelease = prefs(context).let {
        SeenRelease(it.getInt(UPDATE_LATEST_CODE, 0), it.getString(UPDATE_LATEST_NAME, null).orEmpty(),
            it.getBoolean(UPDATE_INSTALLABLE, false), it.getLong(UPDATE_CHECKED_AT, 0L))
    }

    /** A finished check: [release] is null when the page couldn't be read, which still counts as a check. */
    fun setChecked(context: Context, release: ReleaseInfo?, installable: Boolean, at: Long) {
        val edit = prefs(context).edit().putLong(UPDATE_CHECKED_AT, at)
        if (release != null) edit.putInt(UPDATE_LATEST_CODE, release.versionCode).putString(UPDATE_LATEST_NAME, release.versionName)
            .putBoolean(UPDATE_INSTALLABLE, installable)
        edit.apply()
    }

    /** Not now on the banner: it stays away until a release newer than [code]. */
    fun updateDismissedCode(context: Context) = prefs(context).getInt(UPDATE_DISMISSED_CODE, 0)
    fun setUpdateDismissedCode(context: Context, code: Int) = prefs(context).edit().putInt(UPDATE_DISMISSED_CODE, code).apply()

    /**
     * The settings that travel in a copy to another phone ([KeyboardCopy]), by how each is stored. Every key in
     * this file is either here or in [STAYS]; `CopyMergeTest` fails when a new one is in neither.
     */
    private val PORTABLE_BOOLEANS: Set<String> = setOf(
        AUTOCORRECT, WEB_AUTOCORRECT, AUTO_CAPITALIZE, PROFANITY, EMOJI, NEURAL_PREDICTIONS, NUMBER_ROW, VIBRATE_ON_KEY_PRESS,
        MODEL_ON_TAP, KEEP_MODEL_LOADED, SPARKLE_BUTTON, TRIPLE_SPACE_PROOFREAD, VOICE_INPUT, SLIDE_GESTURES, KEY_SHAPES,
        BRITISH_SPELLING, COMMON_SYMBOL_HOLDS, EXPERIMENTAL_SWIPE, TWO_FINGER_GESTURES, STEADY_KEYS, AIM_FROM_TOUCHES,
        INACTIVE_GAPS, PASSWORD_MANAGER_SUGGESTIONS, CLEAN_PASTED_LINKS, REMEMBER_DOMAINS, AUTO_UPDATE_CHECK,
        ENGLISH, SPANISH, IPA, PORTUGUESE, HEBREW, WELSH, IRISH, MAORI, HAWAIIAN,
    ) + LanguageProfile.EXTRA.map(::extraKey)
    private val PORTABLE_INTS: Map<String, IntRange> = mapOf(
        BOTTOM_CLEARANCE to 0..48, IGNORE_REPEAT_MS to 0..10_000, KEY_HOLD_MS to 0..10_000, DWELL_MS to 0..10_000,
    )
    private val PORTABLE_STRINGS: Set<String> = setOf(
        THEME, VOICE_KEY, SLIDE_SPEED, TOOLS_ROW, SPACE_BAR, ONE_HANDED, WIDE_KEYS, CURRENCY, STRIP_HEIGHT, LANGUAGE, SETTINGS_LANGUAGE,
    ) + TwoFingerSwipe.entries.map { GESTURE_PREFIX + it.id }

    /**
     * What stays on the phone it was set on: whether a model is set up here, the counters and marks of this
     * install, what the update check last saw, and the typing log's switch, which is turned on by hand on each phone.
     */
    private val STAYS: Set<String> = setOf(
        MODEL_SET_UP, LEARNED_GENERATION, PREDICTION_LOG, UPDATE_CHECKED_AT, UPDATE_LATEST_CODE, UPDATE_LATEST_NAME,
        UPDATE_INSTALLABLE, UPDATE_DISMISSED_CODE, SETUP_COMPLETE, SETUP_STEP, TOUR_SEEN,
    )
    /** Names, codes and comma-separated ids: nothing a setting holds needs more than this. */
    private val PORTABLE_VALUE = Regex("[A-Za-z0-9_,.-]{0,256}")

    internal val portableKeys: Set<String> get() = PORTABLE_BOOLEANS + PORTABLE_INTS.keys + PORTABLE_STRINGS
    internal val stayingKeys: Set<String> get() = STAYS

    /** The portable settings among [stored], only those the person has set: a default stays a default on the new phone. */
    internal fun portableFrom(stored: Map<String, *>): List<KeyboardCopy.Setting> = stored.entries.sortedBy { it.key }.mapNotNull { (key, value) ->
        when {
            key in PORTABLE_BOOLEANS && value is Boolean -> KeyboardCopy.Setting('b', key, if (value) "1" else "0")
            key in PORTABLE_INTS && value is Int -> KeyboardCopy.Setting('i', key, value.toString())
            key in PORTABLE_STRINGS && value is String && PORTABLE_VALUE.matches(value) -> KeyboardCopy.Setting('s', key, value)
            else -> null
        }
    }

    /** The value [setting] would store, or null when its key does not travel or its value is not one a setting holds. */
    internal fun portableValue(setting: KeyboardCopy.Setting): Any? = when {
        setting.type == 'b' && setting.key in PORTABLE_BOOLEANS -> when (setting.value) { "1" -> true; "0" -> false; else -> null }
        setting.type == 'i' && setting.key in PORTABLE_INTS -> setting.value.toIntOrNull()?.takeIf { it in PORTABLE_INTS.getValue(setting.key) }
        setting.type == 's' && setting.key in PORTABLE_STRINGS -> setting.value.takeIf { PORTABLE_VALUE.matches(it) }
        else -> null
    }

    fun portable(context: Context): List<KeyboardCopy.Setting> = portableFrom(prefs(context).all)

    /** Stores the settings of a copy that pass [portableValue], in one write. Returns how many were taken. */
    fun applyPortable(context: Context, settings: List<KeyboardCopy.Setting>): Int {
        val edit = prefs(context).edit()
        var taken = 0
        for (setting in settings) {
            when (val value = portableValue(setting)) {
                is Boolean -> edit.putBoolean(setting.key, value)
                is Int -> edit.putInt(setting.key, value)
                is String -> edit.putString(setting.key, value)
                else -> continue
            }
            taken++
        }
        if (taken > 0) edit.apply()
        return taken
    }

    fun learnedGeneration(context: Context) = prefs(context).getInt(LEARNED_GENERATION, 0)

    /** Called after learned words change in settings so a running keyboard reloads them. */
    fun bumpLearnedGeneration(context: Context) {
        val p = prefs(context)
        p.edit().putInt(LEARNED_GENERATION, p.getInt(LEARNED_GENERATION, 0) + 1).apply()
    }
}
