package dev.localtype.keyboard

/** How the mic key gets speech into the field on this phone. */
enum class VoiceRoute {
    /** Android's on-device recognizer, inside this keyboard. */
    ON_DEVICE,
    /** Another app's voice input method, which this keyboard only switches to. */
    HANDOFF,
    NONE,
}

/** Pure rules for whether and how voice input is offered. */
object VoiceInputRules {
    /** `SpeechRecognizer.createOnDeviceSpeechRecognizer` arrived in Android 12. */
    const val ON_DEVICE_SDK = 31
    /** `InputMethodService.switchInputMethod` arrived in Android 9. */
    const val HANDOFF_SDK = 28

    /** The on-device recognizer wherever the phone has one; otherwise an enabled voice input method, if any. */
    fun route(sdk: Int, onDeviceAvailable: Boolean, voiceInputMethod: String?): VoiceRoute = when {
        sdk >= ON_DEVICE_SDK && onDeviceAvailable -> VoiceRoute.ON_DEVICE
        sdk >= HANDOFF_SDK && !voiceInputMethod.isNullOrEmpty() -> VoiceRoute.HANDOFF
        else -> VoiceRoute.NONE
    }

    /**
     * The mic key shows in ordinary text fields only: never where a password is typed, where the field takes raw
     * keys, or on the number and phone pads.
     */
    fun offered(enabled: Boolean, route: VoiceRoute, sensitive: Boolean, rawKeys: Boolean, letters: Boolean): Boolean =
        enabled && route != VoiceRoute.NONE && !sensitive && !rawKeys && letters
}

/** Fits dictated text to what is already in the field. Pure. */
object DictationText {
    private const val SENTENCE_ENDS = ".!?"
    private const val OPENERS = "([{<\"'“‘/@#$-–—_"

    /**
     * What to commit for [dictated] after [before]. A space goes in front unless the text before ends in whitespace
     * or an opening mark. With [formatted] text the recognizer capitalizes every result as if it began a sentence,
     * so mid-sentence its first letter goes back to lowercase unless the word is "I"; raw text gets a capital at a
     * sentence start.
     */
    fun insertion(before: String, dictated: String, formatted: Boolean): String {
        val text = dictated.trim()
        if (text.isEmpty()) return ""
        val sentenceStart = startsSentence(before)
        val cased = when {
            sentenceStart -> text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            formatted && !firstWordIsI(text) && !secondLetterUpper(text) -> text.replaceFirstChar { it.lowercase() }
            else -> text
        }
        val last = before.lastOrNull()
        val gap = last != null && !last.isWhitespace() && last !in OPENERS
        return if (gap) " $cased" else cased
    }

    /** Empty text, a new line, or a sentence end followed by a space. */
    fun startsSentence(before: String): Boolean {
        val trimmed = before.trimEnd(' ', '\t')
        if (trimmed.isEmpty() || trimmed.last() == '\n') return true
        if (trimmed.length == before.length) return false
        val closed = trimmed.trimEnd('"', '\'', '”', '’', ')', ']')
        return closed.isNotEmpty() && closed.last() in SENTENCE_ENDS
    }

    private fun firstWordIsI(text: String): Boolean {
        val word = text.takeWhile { !it.isWhitespace() && it !in ",.!?;:" }
        return word == "I" || word.startsWith("I'") || word.startsWith("I’")
    }

    /** "USB" or "iPhone"-style words keep their own capitals. */
    private fun secondLetterUpper(text: String): Boolean = text.length > 1 && text[1].isUpperCase()
}

/**
 * One dictation's bookkeeping, apart from the recognizer: whether it is listening, the latest partial text, and what
 * a result or an error means. The recognizer hears one utterance at a time, so a result with words in it asks for
 * another listen and silence ends the session. Pure and main-thread only.
 */
class DictationSession {
    enum class End { SILENCE, PERMISSION, LANGUAGE, BUSY, FAILED }

    sealed interface Step {
        /** Commit [text] and keep listening. */
        data class Insert(val text: String) : Step
        /** Stop, for [reason]; [code] is the recognizer's error, or 0. */
        data class Stop(val reason: End, val code: Int = 0) : Step
        /** A callback that arrived after the session ended. */
        data object Ignore : Step
    }

    var listening = false
        private set
    var partial = ""
        private set
    /** Results committed in this session, for the strip's message when it ends. */
    var inserted = 0
        private set

    fun start(): Boolean {
        if (listening) return false
        listening = true
        partial = ""
        inserted = 0
        return true
    }

    fun partial(text: String) {
        if (listening) partial = text.trim()
    }

    fun result(text: String): Step {
        if (!listening) return Step.Ignore
        partial = ""
        val words = text.trim()
        if (words.isEmpty()) return finish(End.SILENCE)
        inserted++
        return Step.Insert(words)
    }

    fun error(code: Int): Step {
        if (!listening) return Step.Ignore
        return finish(reason(code), code)
    }

    /** Ends the session and hands back the partial text nobody has committed yet. */
    fun stop(): String {
        val pending = partial
        listening = false
        partial = ""
        return pending
    }

    private fun finish(reason: End, code: Int = 0): Step {
        listening = false
        partial = ""
        return Step.Stop(reason, code)
    }

    companion object {
        // android.speech.SpeechRecognizer error codes, named here so this class stays free of Android.
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun reason(code: Int): End = when (code) {
            ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> End.SILENCE
            ERROR_INSUFFICIENT_PERMISSIONS -> End.PERMISSION
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> End.LANGUAGE
            ERROR_RECOGNIZER_BUSY -> End.BUSY
            else -> End.FAILED
        }
    }
}
