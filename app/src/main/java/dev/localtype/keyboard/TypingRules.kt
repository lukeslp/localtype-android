package dev.localtype.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Editing decisions kept free of Android views so they can be unit tested. */
object TypingRules {
    /** The keyboard's own hold time, before anything opens on a held key. */
    const val HOLD_MS = 380L

    /**
     * When a held key starts its hold (alternates, the space chooser, Delete's repeat): the keyboard's own
     * [HOLD_MS], or Android's Touch & hold delay (Accessibility settings) when the person has set it longer.
     * Luke, 2026-10-19 (docs/interaction-models.md in LocalLauncher, decision 7). Read at each press, so a
     * changed setting applies at once.
     */
    fun holdDelayMs(systemLongPressTimeout: Int): Long = maxOf(HOLD_MS, systemLongPressTimeout.toLong())

    /**
     * Whether a key is drawn on its rounded shape. With key shapes off only unpressed character keys lose theirs,
     * so Space, Shift, Delete, Return and the accent keys stay as landmarks and a pressed key still shows.
     */
    fun drawsKeyShape(keyShapes: Boolean, pressed: Boolean, style: KeyStyle): Boolean =
        keyShapes || pressed || style != KeyStyle.CHARACTER

    /** Two quick spaces after a word become ". ", like most phone keyboards. */
    fun shouldInsertPeriodOnSpace(beforeCursor: String): Boolean = TextRules.shouldInsertPeriodOnSpace(beforeCursor)

    /** A standalone lowercase "i" just before the cursor, which space should turn into "I". */
    fun isLoneLowercaseI(beforeCursor: String): Boolean = TextRules.isLoneLowercaseI(beforeCursor)

    /** Punctuation typed right after an automatic space should sit against the word instead. */
    fun attachesToPreviousWord(character: Char): Boolean = TextRules.attachesToPreviousWord(character)

    fun endsSentence(character: Char): Boolean = TextRules.endsSentence(character)

    /** [TextRules.applyCase]. */
    fun applyCase(word: String, typedPrefix: String, sentenceStart: Boolean, capsLock: Boolean = false): String =
        TextRules.applyCase(word, typedPrefix, sentenceStart, capsLock)

    /**
     * Shift after a tap on the shift key. [quick] is a second tap inside the double-tap window: it locks
     * caps from OFF or ON, so a double tap still locks when the field had already turned shift on.
     */
    fun shiftAfterTap(current: KeyboardView.ShiftState, quick: Boolean): KeyboardView.ShiftState = when {
        current == KeyboardView.ShiftState.LOCKED -> KeyboardView.ShiftState.OFF
        quick -> KeyboardView.ShiftState.LOCKED
        current == KeyboardView.ShiftState.OFF -> KeyboardView.ShiftState.ON
        else -> KeyboardView.ShiftState.OFF
    }

    /** [TextRules.shifted]. */
    fun shifted(value: String): String = TextRules.shifted(value)

    /** Three strip slots with the strongest candidate in the middle, where the thumb rests. */
    fun arrangeForStrip(ranked: List<String>): List<String> = listOf(
        ranked.getOrNull(1).orEmpty(),
        ranked.getOrNull(0).orEmpty(),
        ranked.getOrNull(2).orEmpty(),
    )

    fun isSensitive(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** [TextRules.lastGraphemeLength]. */
    fun lastGraphemeLength(text: String): Int = TextRules.lastGraphemeLength(text)

    /** [TextRules.dropLastGrapheme]. */
    fun dropLastGrapheme(text: String): String = TextRules.dropLastGrapheme(text)

    /** Fields that ask for no suggestions still get a keyboard, just no candidate strip content. */
    fun wantsSuggestions(inputType: Int): Boolean {
        if (isSensitive(inputType)) return false
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
        ) return false
        return inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
    }

    /** Why the strip shows a one-line notice instead of suggestions; null when the field gets suggestions. */
    /** Why the strip shows a note instead of words: the field's kind, or [IPA], the symbols keyboard chosen by hand. */
    enum class StripOff { PRIVATE, TERMINAL, FIELD, IPA }

    fun stripOff(inputType: Int): StripOff? = when {
        isSensitive(inputType) -> StripOff.PRIVATE
        // TYPE_NULL and terminals (Termux sends class 0) take raw key events, not words.
        inputType and InputType.TYPE_MASK_CLASS == 0 -> StripOff.TERMINAL
        wantsSuggestions(inputType) || isWebTextWithoutSuggestions(inputType) || predictsInAddressBar(inputType) -> null
        else -> StripOff.FIELD
    }

    /**
     * An address bar is where people now type questions and prompts as well as addresses, so from 0.5.18 it
     * gets word predictions and verified prose learning. It stays outside [wantsSuggestions], so
     * automatic correction and the typing log remain off.
     */
    fun predictsInAddressBar(inputType: Int): Boolean = isUrl(inputType) && !isSensitive(inputType)

    /**
     * Where a tap on the strip's sparkle may ask the model for words: wherever the strip offers words, so from
     * 0.5.26 also address bars and web text boxes that turn suggestions off. The tap is the request; nothing
     * automatic starts in those fields.
     */
    fun offersModelWordsOnTap(inputType: Int): Boolean = stripOff(inputType) == null

    /** Where holding the sparkle proofreads: only fields that want suggestions. */
    fun proofreadsOnHold(inputType: Int): Boolean = wantsSuggestions(inputType)

    /**
     * Chrome turns autocomplete="off", which is about the browser's own autofill, into NO_SUGGESTIONS on
     * ordinary web text boxes; the google.com search box is one. The web-autocorrect setting
     * controls automatic correction there; neural inference and logging still respect the flag.
     */
    fun isWebTextWithoutSuggestions(inputType: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT &&
            inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

    /** Incognito tabs and similar fields set this flag; nothing typed there may be learned. */
    fun allowsLearning(imeOptions: Int, inputType: Int): Boolean =
        imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 && !isSensitive(inputType)

    /**
     * Whether the keyboard covers the app with its own text box. Android's default does that in every landscape window
     * ([landscapeDefault] also carries the editor's IME_FLAG_NO_FULLSCREEN), which suits a phone on its side but hides the
     * app on a tablet, an unfolded Fold or a desktop window. So only a window under [COMPACT_HEIGHT_DP] tall, Android's
     * compact height class, keeps it; an unknown height (0) keeps Android's default.
     */
    fun fullscreen(landscapeDefault: Boolean, screenHeightDp: Int): Boolean =
        landscapeDefault && (screenHeightDp == 0 || screenHeightDp < COMPACT_HEIGHT_DP)

    /** Android's compact height class ends here (window size classes: compact height is under 480 dp). */
    const val COMPACT_HEIGHT_DP = 480

    /** The starting keys follow the field's class; variation and flag bits mean different things in each class. */
    fun layoutFor(inputType: Int): KeyboardLayout.Mode = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER -> KeyboardLayout.Mode.NUMBER
        InputType.TYPE_CLASS_PHONE -> KeyboardLayout.Mode.PHONE
        InputType.TYPE_CLASS_DATETIME -> KeyboardLayout.Mode.SYMBOLS
        else -> KeyboardLayout.Mode.LETTERS
    }

    /**
     * Whether a field start picks fresh keys. A restart usually keeps the person's layer, since some apps
     * restart on every change, but Chrome and WebView also restart to move between a page's fields, so a
     * restart whose field wants different keys ([startedOn] was the last field's) starts over too.
     */
    fun startsNewLayout(restarting: Boolean, startedOn: KeyboardLayout.Mode?, inputType: Int): Boolean =
        !restarting || layoutFor(inputType) != startedOn

    /**
     * A restart of the editor already open: Android passes [restarting] for the same field, and the package, field,
     * type and options in [identity] match. A key already pressed there survives it when the layout is unchanged.
     * Editors without a view id report a field id of 0 or -1, so the id is compared only as part of the identity.
     */
    fun sameEditorRestart(restarting: Boolean, identity: String, previous: String?): Boolean =
        restarting && identity == previous

    /** Keys that change the editor's text, which [PendingKeys] holds while a restart has retired the connection. */
    fun editsText(action: KeyAction): Boolean =
        action is KeyAction.Text || action == KeyAction.Space || action == KeyAction.Backspace || action == KeyAction.Enter

    fun isEmail(inputType: Int): Boolean = isTextVariation(inputType, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) ||
        isTextVariation(inputType, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)

    fun isUrl(inputType: Int): Boolean = isTextVariation(inputType, InputType.TYPE_TEXT_VARIATION_URI)

    fun isNameOrAddress(inputType: Int): Boolean = isTextVariation(inputType, InputType.TYPE_TEXT_VARIATION_PERSON_NAME) ||
        isTextVariation(inputType, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)

    /** Shift turns itself on only where the field asks for capitals and automatic capitalization is on. */
    fun autoShift(fieldCapsMode: Int, autoCapitalize: Boolean): Boolean = autoCapitalize && fieldCapsMode != 0

    /** Suggestions start with a capital at a sentence start only in sentence-caps fields, and only with automatic capitalization on. */
    fun capitalizesSentences(inputType: Int, autoCapitalize: Boolean): Boolean =
        autoCapitalize && inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0

    /** A lone "i" becomes "I" on space or punctuation, except in names and addresses or with automatic capitalization off. */
    fun capitalizesLoneI(inputType: Int, autoCapitalize: Boolean): Boolean = autoCapitalize && !isNameOrAddress(inputType)

    /** Names and addresses keep completion and correction suggestions, but only a tap may replace a word. */
    fun allowsAutocorrect(inputType: Int, webText: Boolean = true): Boolean =
        !isNameOrAddress(inputType) && (wantsSuggestions(inputType) || (webText && isWebTextWithoutSuggestions(inputType)))

    /** Older WebView connections can accept API34 replaceText without applying it. Select and
     * verify web words instead; a qualified automatic late editor uses its separate profile. */
    fun prefersRangeReplacement(inputType: Int, api: Int): Boolean = api >= 34 &&
        !isTextVariation(inputType, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)

    private fun isTextVariation(inputType: Int, variation: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_MASK_VARIATION == variation

    /** The editor action Enter should perform, or null when Enter should insert a newline. */
    fun enterAction(imeOptions: Int): Int? {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED -> null
            else -> action
        }
    }

    fun enterLabel(action: Int?): String = when (action) {
        EditorInfo.IME_ACTION_GO -> "Go"
        EditorInfo.IME_ACTION_SEARCH -> "Search"
        EditorInfo.IME_ACTION_SEND -> "Send"
        EditorInfo.IME_ACTION_NEXT -> "Next"
        EditorInfo.IME_ACTION_DONE -> "Done"
        EditorInfo.IME_ACTION_PREVIOUS -> "Back"
        else -> "Return"
    }
}
